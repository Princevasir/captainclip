package app.clipbridge.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import app.clipbridge.MainActivity
import app.clipbridge.capture.GhostActivity
import app.clipbridge.capture.OwnWrites
import app.clipbridge.history.Direction
import android.content.ClipDescription
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import app.clipbridge.core.FileLimit
import app.clipbridge.core.Message
import app.clipbridge.files.Files
import java.io.File
import java.util.concurrent.Executors

/** A shared file already copied into ClipBridge's cache by the Share screen. */
data class OutgoingFile(val file: File, val name: String, val mime: String, val purpose: String = "file")

/** What happened to a send: it went to the Mac, it waits in [SendQueueHub] for the Mac, or nothing could be sent. */
enum class SendResult { SENT, QUEUED, FAILED }

/**
 * Foreground service hosting the [SyncLoop]. Clips from the Mac go straight onto the phone clipboard.
 * Only START (when sync is enabled and paired) or RECONNECT bring it up; sending never does.
 */
class SyncService : Service() {
    companion object {
        private const val ACTION_START = "app.clipbridge.START"
        private const val ACTION_STOP = "app.clipbridge.STOP"
        private const val ACTION_RECONNECT = "app.clipbridge.RECONNECT"
        private const val CHANNEL = "sync"
        private const val NOTIFICATION_ID = 1
        private const val OFFER_NOTIFICATION_ID = 2
        private const val TRANSFER_NOTIFICATION_ID = 3
        private const val FILES_CHANNEL = "files"
        private const val ACTION_SAVE_OFFER = "app.clipbridge.SAVE_OFFER"
        private const val EXTRA_OFFER_ID = "offer_id"
        private const val PAUSE_CHECK_MS = 30_000L

        @Volatile private var instance: SyncService? = null

        /** Human-readable status for the main screen. */
        @Volatile var status: String = "Stopped"
            private set

        /** Structured status for the main screen's colour/wording. */
        @Volatile var state: SyncLoop.Status = SyncLoop.Status.STOPPED
            private set

        /** True while connected to the Mac (not just running). */
        val isConnected: Boolean get() = instance?.loop != null && state == SyncLoop.Status.CONNECTED

        /** True while the Mac link is Bluetooth. */
        val onBluetooth: Boolean get() = instance?.loop?.path == SyncLoop.Path.BLUETOOTH

        /** The connected Mac's name, if any. */
        val macName: String? get() = instance?.loop?.macName

        /** In-app "Save to Downloads" for the Mac's offered file. [onDone] gets the saved Uri or an error, on the main thread. */
        fun saveOffer(id: String, onDone: (Uri?, String?) -> Unit) {
            val service = instance ?: return onDone(null, "Not connected to your Mac")
            service.saveOffer(id, onDone)
        }
        var onStatusChanged: (() -> Unit)? = null

        /**
         * Share: fetches every offered file into the cache first (so the receiving app never waits on the Mac).
         * [onDone] runs on the main thread with null on success, or an error message.
         */
        fun prepareOffers(ids: List<String>, onDone: (String?) -> Unit) {
            val service = instance ?: return onDone("Not connected to your Mac")
            service.prepare(ids, onDone)
        }

        fun start(ctx: Context) = command(ctx, ACTION_START, foreground = true)
        fun stop(ctx: Context) { if (instance != null) command(ctx, ACTION_STOP, foreground = false) }

        /** After re-pairing: (re)start and connect with the new pairing right away. Waiting copies are dropped
         *  so they never reach a different Mac. */
        fun reconnect(ctx: Context) {
            SendQueueHub.queue.clear()
            command(ctx, ACTION_RECONNECT, foreground = true)
        }

        /** True while a timed pause (15 minutes / 1 hour) is running. "Until I resume" stops sync instead. */
        fun isPaused(ctx: Context): Boolean = Pause.isTimedPause(Prefs.pausedUntil(ctx), System.currentTimeMillis())

        /**
         * Timed choices keep the connection but ignore the Mac's clips and hold sends until the deadline;
         * [PauseChoice.UNTIL_RESUMED] stops sync as the old Pause did.
         */
        fun pauseFor(ctx: Context, choice: PauseChoice) {
            val until = Pause.until(choice, System.currentTimeMillis())
            ConnectionLogHub.log.add("paused")
            if (until == null) {
                Prefs.setPausedUntil(ctx, 0)
                Prefs.setSyncEnabled(ctx, false)
                stop(ctx)
            } else {
                Prefs.setPausedUntil(ctx, until)
                Prefs.setSyncEnabled(ctx, true)
                checkPauseOrStart(ctx)
            }
        }

        /** Ends any pause: sync on, service up, and whatever waited is sent once connected. */
        fun resume(ctx: Context) {
            val wasPaused = isPaused(ctx) || !Prefs.syncEnabled(ctx)
            Prefs.setPausedUntil(ctx, 0)
            Prefs.setSyncEnabled(ctx, true)
            if (wasPaused) ConnectionLogHub.log.add("resumed")
            checkPauseOrStart(ctx)
        }

        /** A running service re-checks the pause (and drains); otherwise start it, which does the same. */
        private fun checkPauseOrStart(ctx: Context) {
            val service = instance
            if (service != null) service.main.post { service.checkPause() } else start(ctx)
        }

        /** The service, if a send can go to the Mac right now (connected and not paused). */
        private fun ready(): SyncService? = instance?.takeIf { isConnected && !isPaused(it) }

        /** Something was just queued: if the Mac is (or just became) reachable, send it now rather than at the
         *  next status change. [checkPause] also ends a timed pause whose deadline has passed. */
        private fun queued(result: SendResult): SendResult {
            val service = instance
            if (result == SendResult.QUEUED && service != null && isConnected) service.main.post { service.checkPause() }
            return result
        }

        /** Held while a clip goes to the Mac, so a stale queued clip can't overtake a newer direct send. */
        private val clipSendLock = Any()

        /**
         * Send text to the Mac, in-process (no Intent, so no Binder size limit). Never starts the service:
         * when not connected (or paused) the text waits in [SendQueueHub] until the Mac is back.
         */
        fun send(text: String, html: String? = null, userInitiated: Boolean = false): SendResult {
            if (text.isEmpty()) return SendResult.FAILED
            val service = ready() ?: return queued(SendResult.QUEUED.also { SendQueueHub.queue.putClip(QueuedClip.Text(text, html)) })
            val sent = synchronized(clipSendLock) {
                SendQueueHub.queue.supersedeClip()  // newest wins: a waiting older clip must not follow this one
                service.loop?.send(text, html, userInitiated) ?: false
            }
            if (sent) { service.history().recordText(text, html, Direction.TO_MAC); service.historyChanged() }  // it crossed
            return if (sent) SendResult.SENT else SendResult.FAILED
        }

        /** Fetch (and verify) the Mac's offered file. Blocking; used by FileOfferProvider on a binder thread. */
        fun fetchOffer(id: String, dir: File): File {
            val loop = instance?.loop ?: throw java.io.FileNotFoundException("not connected to your Mac")
            return try {
                val offer = Files.cache(instance!!).offer(id)
                val size = offer?.size ?: 0L
                val (total, first) = SyncLoop.fetchTimeouts(size, loop.isOnBluetooth)
                loop.fetch(id, dir, timeoutMs = total, firstDataMs = first, size = size)  // the pasting app is waiting
                    .also { offer?.let { o -> instance?.recordFetched(o) } }
            } catch (e: FileFetchException) {
                instance?.toast(when (e.reason) {
                    "expired" -> "That file is no longer on your Mac's clipboard"
                    "changed" -> "The file changed on your Mac — copy it again"
                    "too-large-bluetooth" -> "Too big for Bluetooth — connect both to the same Wi-Fi"
                    "timeout", "not-connected", "io" -> "Couldn't reach your Mac"
                    else -> "Couldn't get the file from your Mac"
                })
                throw java.io.FileNotFoundException(e.reason)
            }
        }

        val isRunning: Boolean get() = instance?.loop != null

        /**
         * A copied image (already copied into our cache) → the Mac's clipboard, or the queue while away. Duplicate
         * triggers for the same image within a few seconds (copy button + "Copied" toast) are handled once.
         */
        fun sendClipboardImage(file: OutgoingFile, key: String): SendResult {
            val now = android.os.SystemClock.elapsedRealtime()
            if (key == lastImageKey && now - lastImageAt < 5_000) { file.file.delete(); return lastImageResult }
            lastImageKey = key; lastImageAt = now
            val service = ready()
            lastImageResult = if (service == null) {
                SendQueueHub.queue.putClip(QueuedClip.Image(file, key))
                SendResult.QUEUED
            } else {
                SendQueueHub.queue.supersedeClip()  // newest wins: a waiting older clip must not follow this one
                service.uploadFiles(listOf(file))
                SendResult.SENT
            }
            return queued(lastImageResult)
        }
        @Volatile private var lastImageKey: String? = null
        @Volatile private var lastImageAt = 0L
        @Volatile private var lastImageResult = SendResult.SENT

        /** Share → Send to Mac: send already-copied files (deleted afterwards), or queue them while away. */
        fun sendLocalFiles(files: List<OutgoingFile>): SendResult {
            if (files.isEmpty()) return SendResult.FAILED
            val service = ready()
                ?: return queued(if (SendQueueHub.queue.addFiles(files) > 0) SendResult.QUEUED else SendResult.FAILED)
            service.uploadFiles(files)
            return SendResult.SENT
        }

        private fun command(ctx: Context, action: String, foreground: Boolean) {
            val intent = Intent(ctx, SyncService::class.java).setAction(action)
            runCatching { if (foreground) ctx.startForegroundService(intent) else ctx.startService(intent) }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var loop: SyncLoop? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { shutdown(); return START_NOT_STICKY }
            ACTION_RECONNECT -> if (ensureRunning()) { loop?.reconnect(); checkPause() }
            // Notification "Save": every file of the set (an id saves just that one).
            ACTION_SAVE_OFFER -> if (loop != null) {
                val id = intent.getStringExtra(EXTRA_OFFER_ID)
                (if (id != null) listOf(id) else Files.cache(this).currentAll().map { it.id }).forEach { saveOffer(it) }
            }
            else -> {  // START, or a sticky restart with a null intent
                if (!Prefs.syncEnabled(this) || Prefs.pairing(this) == null) { shutdown(); return START_NOT_STICKY }
                if (ensureRunning()) checkPause()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    /** Returns false if Android refused to let us run in the foreground. */
    // The manifest declares foregroundServiceType="connectedDevice" (checked in both built APKs); lint misreads it
    // once the standard/pro flavours exist.
    @android.annotation.SuppressLint("ForegroundServiceType")
    private fun ensureRunning(): Boolean {
        if (loop != null) return true
        createChannel()
        val started = runCatching {
            val notification = buildNotification("Looking for your Mac…")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.isSuccess
        if (!started) { stopSelf(); return false }

        val newLoop = SyncLoop(
            pairing = { Prefs.pairing(this) },
            locate = { MacLocator(this).find(it) },
            // The phone's own name from Settings → About phone (e.g. "Alex's Galaxy S25"), else the model.
            device = runCatching { android.provider.Settings.Global.getString(contentResolver, "device_name") }
                .getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL,
            bluetoothLink = if (app.clipbridge.SetupStep.bluetoothFallbackAvailable(Build.VERSION.SDK_INT, app.clipbridge.Edition.LOCATION_FOR_BLUETOOTH))
                { info -> BleLink.find(this, info) } else null,
            lastAddress = { Prefs.lastMacAddress(this) },
            callbacks = object : SyncLoop.Callbacks {
                override fun onStatus(status: SyncLoop.Status) {
                    val previous = state
                    state = status
                    logStatus(previous, status)
                    main.post { checkPause() }  // drains once connected and not paused
                    setStatus(describe(status))
                    app.clipbridge.capture.SendTileService.refresh(this@SyncService)
                    app.clipbridge.capture.ConnectionTileService.refresh(this@SyncService)
                    app.clipbridge.SendWidget.refresh(this@SyncService)
                }
                override fun onClip(text: String) = writeClipboard(text, null)
                override fun onClip(text: String, html: String?) = writeClipboard(text, html)
                override fun onOffer(offer: Message.Offer?) = handleOffers(listOfNotNull(offer))
                override fun onOffers(offers: List<Message.Offer>) = handleOffers(offers)
                override fun onWifiConnected(host: String, port: Int) = Prefs.setLastMacAddress(this@SyncService, host, port)
                override fun onLost() = ConnectionLogHub.log.add("lost")  // not for a Wi-Fi switch or reconnect
            },
        )
        loop = newLoop
        instance = this
        watchNetwork()
        newLoop.start()
        return true
    }

    private val io = Executors.newSingleThreadExecutor()

    /** Connection log: connects by path and "auth-failed" ("lost" comes from [SyncLoop.Callbacks.onLost]). */
    private var loggedPath: SyncLoop.Path? = null
    private fun logStatus(previous: SyncLoop.Status, status: SyncLoop.Status) {
        when (status) {
            SyncLoop.Status.CONNECTED -> {
                val path = loop?.path
                if (previous != SyncLoop.Status.CONNECTED || path != loggedPath) {
                    loggedPath = path
                    ConnectionLogHub.log.add(if (path == SyncLoop.Path.BLUETOOTH) "connected-bluetooth" else "connected-wifi")
                }
            }
            SyncLoop.Status.PAIRING_INVALID -> ConnectionLogHub.log.add("auth-failed")
            else -> {}
        }
    }

    private val pauseTick = Runnable { checkPause() }

    /**
     * Main thread. While a timed pause runs, look again in 30 s (or right at the deadline, if sooner); once it
     * has ended, log "resumed" and send what waited (if connected). Also runs on every status change and queued send.
     */
    private fun checkPause() {
        main.removeCallbacks(pauseTick)
        if (instance !== this) return
        val until = Prefs.pausedUntil(this)
        val now = System.currentTimeMillis()
        if (Pause.isTimedPause(until, now)) { main.postDelayed(pauseTick, minOf(PAUSE_CHECK_MS, until - now + 1)); return }
        if (until != 0L) {
            Prefs.setPausedUntil(this, 0)
            ConnectionLogHub.log.add("resumed")
            onStatusChanged?.invoke()
        }
        drainQueue()
    }

    /**
     * Sends what waited while away: the clip first, then the files in order (one [io] task, so order holds).
     * Over Bluetooth, anything too big for it stays queued for Wi-Fi. Whatever fails goes back into the queue
     * (with its original time, so it still expires); a clip superseded by a newer direct send is dropped.
     */
    private fun drainQueue() {
        val queue = SendQueueHub.queue
        if (!isConnected || isPaused(this) || queue.isEmpty) return
        val onBluetooth = loop?.isOnBluetooth == true
        val pending = queue.drain(maxFileBytes = if (onBluetooth) FileLimit.BLUETOOTH_MAX_BYTES else Long.MAX_VALUE)
        io.execute {
            when (val clip = pending.clip) {
                is QueuedClip.Text -> {
                    // As userInitiated, so the echo guard can't swallow a copy that already waited for the Mac.
                    val sent = synchronized(clipSendLock) {
                        queue.isLatestClip(pending) && loop?.send(clip.text, clip.html, userInitiated = true) == true
                    }
                    if (sent) { history().recordText(clip.text, clip.html, Direction.TO_MAC); historyChanged() }
                    else queue.requeueClip(pending)  // drops it if a newer clip exists
                }
                is QueuedClip.Image ->
                    if (queue.isLatestClip(pending)) uploadNow(listOf(clip.file)) { queue.requeueClip(pending) }
                    else queue.requeueClip(pending)  // superseded: deletes the image
                null -> {}
            }
            if (pending.files.isNotEmpty()) uploadNow(pending.files) { queue.requeueFile(pending, it) }
            main.post { onStatusChanged?.invoke() }
        }
        main.post { onStatusChanged?.invoke() }
    }

    fun toast(text: String) = main.post { android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show() }

    /**
     * Tell Android (13+) this clip came from another device (ClipDescription.EXTRA_IS_REMOTE_DEVICE).
     * The system clipboard UI can then treat it as a cross-device copy instead of a normal local "Copied".
     */
    private fun markFromMac(description: ClipDescription) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            description.extras = android.os.PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_REMOTE_DEVICE, true) }
        }
    }

    /** Mac offered one or several files: placeholders on the clipboard (no download yet) and "Save". */
    private fun handleOffers(offers: List<Message.Offer>) {
        if (isPaused(this)) return  // a timed pause ignores the Mac's clips
        val cache = Files.cache(this)
        val notifications = getSystemService(NotificationManager::class.java)
        val offer = offers.firstOrNull()
        if (offer == null) {
            cache.clear()
            notifications.cancel(OFFER_NOTIFICATION_ID)
            main.post { onStatusChanged?.invoke() }
            return
        }
        if (!cache.setAll(offers)) return  // already have it: don't overwrite newer copies made on the phone
        main.post { onStatusChanged?.invoke() }
        main.post {
            runCatching {
                OwnWrites.markNow()
                // One clip item per file: apps that take several files paste them all.
                val label = if (offers.size == 1) offer.name else "${offers.size} files from your Mac"
                val clip = ClipData(ClipDescription(label, offers.map { it.mime }.distinct().toTypedArray()),
                    ClipData.Item(Files.uriFor(offer.id, offer.name)))
                offers.drop(1).forEach { clip.addItem(ClipData.Item(Files.uriFor(it.id, it.name))) }
                markFromMac(clip.description)
                getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
            }
        }
        val save = PendingIntent.getService(
            this, 3, Intent(this, SyncService::class.java).setAction(ACTION_SAVE_OFFER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching {
            notifications.notify(OFFER_NOTIFICATION_ID, Notification.Builder(this, FILES_CHANNEL)
                .setSmallIcon(app.clipbridge.R.drawable.ic_stat_clipbridge)
                .setContentTitle(if (offers.size == 1) "${offer.name} is on your Mac" else "${offers.size} files are on your Mac")
                .setContentText("Paste it into an app, or save it to Downloads")
                .setTimeoutAfter(10 * 60_000L)
                .setAutoCancel(true)
                .addAction(Notification.Action.Builder(null, if (offers.size == 1) "Save" else "Save all", save).build())
                .build())
        }
    }

    private fun prepare(ids: List<String>, onDone: (String?) -> Unit) {
        io.execute {
            val cache = Files.cache(this)
            val error = runCatching {
                for (id in ids) {
                    val offer = cache.offer(id) ?: throw FileFetchException("expired")
                    if (cache.cachedFile(id) != null) continue
                    val l = loop ?: throw FileFetchException("not-connected")
                    val (total, first) = SyncLoop.saveTimeouts(offer.size, l.isOnBluetooth)
                    cache.store(id, l.fetch(id, cache.fetchDir, timeoutMs = total, firstDataMs = first, size = offer.size))
                    recordFetched(offer)
                }
            }.exceptionOrNull()?.let {
                when ((it as? FileFetchException)?.reason) {
                    "expired" -> "Those files are no longer on your Mac's clipboard"
                    "changed" -> "A file changed on your Mac — copy it again"
                    "too-large-bluetooth" -> "Too big for Bluetooth — connect both to the same Wi-Fi"
                    else -> "Couldn't get the files from your Mac"
                }
            }
            main.post { onDone(error) }
        }
    }

    /** "Save" (notification or main screen): fetch the offer and copy it into Downloads/ClipBridge. */
    private fun saveOffer(id: String, onDone: ((Uri?, String?) -> Unit)? = null) {
        val offer = Files.cache(this).offer(id) ?: run {
            transferNotice("File offer expired", null)
            onDone?.invoke(null, "That file is no longer on your Mac's clipboard")
            return
        }
        transferNotice("Saving ${offer.name}…", null, ongoing = true)
        io.execute {
            val cache = Files.cache(this)
            val result = runCatching {
                val file = cache.cachedFile(id) ?: run {
                    val l = loop!!
                    val (total, first) = SyncLoop.saveTimeouts(offer.size, l.isOnBluetooth)
                    l.fetch(id, cache.fetchDir, timeoutMs = total, firstDataMs = first, size = offer.size)
                        .also { cache.store(id, it); recordFetched(offer) }
                }
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, offer.name)
                    put(MediaStore.Downloads.MIME_TYPE, offer.mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/ClipBridge")
                }
                val target = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
                contentResolver.openOutputStream(target)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                target
            }
            result.onSuccess { uri ->
                val open = PendingIntent.getActivity(this, 4,
                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, offer.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    PendingIntent.FLAG_IMMUTABLE)
                transferNotice("Saved ${offer.name} to Downloads/ClipBridge", open)
                getSystemService(NotificationManager::class.java).cancel(OFFER_NOTIFICATION_ID)
                main.post { onDone?.invoke(uri, null) }
            }.onFailure {
                val reason = (it as? FileFetchException)?.reason ?: it.message
                transferNotice("Couldn't save ${offer.name}: $reason", null)
                main.post { onDone?.invoke(null, when (reason) {
                    "changed" -> "The file changed on your Mac — copy it again"
                    "too-large-bluetooth" -> "Too big for Bluetooth — connect both to the same Wi-Fi"
                    else -> "Couldn't get the file from your Mac"
                }) }
            }
        }
    }

    /** Share → Send to Mac with files: send each (≤ 25 MB), one after another, then delete the copies. */
    private fun uploadFiles(files: List<OutgoingFile>) = io.execute { uploadNow(files) }

    /**
     * On [io]. Sends [files] in order and deletes each afterwards, except that a failed one goes to [requeue]
     * (queued items that waited for the Mac) instead, to be tried again on the next connection.
     */
    private fun uploadNow(files: List<OutgoingFile>, requeue: ((OutgoingFile) -> Unit)? = null) {
        var sent = 0
        for (f in files) {
            var kept = false
            try {
                if (f.file.length() > FileLimit.MAX_BYTES) { toast("${f.name} is too large (max 25 MB)"); continue }
                if (f.purpose != "clipboard") transferNotice("Sending ${f.name} to Mac…", null, ongoing = true)
                // Logs carry sizes only, never file names.
                val ok = runCatching { loop?.sendFile(f.name, f.mime, f.file.length(), f.purpose) { f.file.inputStream() } ?: false }
                    .onFailure { android.util.Log.w("ClipBridgeFiles", "upload of ${f.file.length()} bytes failed: ${it.javaClass.simpleName}") }
                    .getOrDefault(false)
                android.util.Log.i("ClipBridgeFiles", "upload of ${f.file.length()} bytes ok=$ok")
                if (ok) history().recordFile(java.util.UUID.randomUUID().toString(), f.name, f.mime, f.file.length(), Direction.TO_MAC)
                if (ok) sent++ else if (requeue != null) {
                    requeue(f)  // the queue now owns the file (or has deleted it if expired)
                    kept = true
                    if (f.purpose != "clipboard") transferNotice("Waiting — sends when your Mac is back", null)
                } else if (f.purpose != "clipboard") toast(
                    if (loop?.isOnBluetooth == true && f.file.length() > app.clipbridge.core.FileLimit.BLUETOOTH_MAX_BYTES)
                        "${f.name} is too big for Bluetooth — connect both to the same Wi-Fi"
                    else "Couldn't send ${f.name} — not connected to your Mac")
            } finally {
                if (!kept) f.file.delete()
            }
        }
        val done = if (sent == 1) "Sent to Mac" else "Sent $sent files to Mac"
        if (sent > 0 && files.any { it.purpose != "clipboard" }) { toast(done); transferNotice(done, null) }
    }

    private fun history() = app.clipbridge.history.HistoryHub.store(this)

    /** A Mac file reached the phone: one history entry per offer id (repeat reads add nothing). Details are captured
     *  before the fetch, so a Mac copy that replaces the offer meanwhile doesn't lose the entry. */
    private fun recordFetched(offer: app.clipbridge.core.Message.Offer) {
        history().recordFile(offer.id, offer.name, offer.mime, offer.size, Direction.FROM_MAC)
        historyChanged()
    }

    /** Lets an open main screen refresh its "Recent clips" card. */
    private fun historyChanged() { main.post { onStatusChanged?.invoke() } }

    private fun transferNotice(text: String, tap: PendingIntent?, ongoing: Boolean = false) {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(TRANSFER_NOTIFICATION_ID,
                Notification.Builder(this, FILES_CHANNEL)
                    .setSmallIcon(app.clipbridge.R.drawable.ic_stat_clipbridge)
                    .setContentTitle("ClipBridge")
                    .setContentText(text)
                    .setOngoing(ongoing)
                    .setAutoCancel(!ongoing)
                    .apply { tap?.let { setContentIntent(it) } }
                    .build())
        }
    }

    private fun writeClipboard(text: String, html: String?) {
        if (isPaused(this)) return  // a timed pause ignores the Mac's clips
        main.post {
            runCatching {
                OwnWrites.markNow()  // so copy detection ignores the system "Copied" overlay this causes
                val clip = if (html != null) ClipData.newHtmlText("ClipBridge", text, html) else ClipData.newPlainText("ClipBridge", text)
                markFromMac(clip.description)
                getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
            }.onSuccess { history().recordText(text, html, Direction.FROM_MAC); historyChanged() }  // on the phone's clipboard: it crossed
        }
    }

    private fun describe(status: SyncLoop.Status) = when (status) {
        SyncLoop.Status.STOPPED -> "Stopped"
        SyncLoop.Status.NOT_PAIRED -> "Not paired — scan the Mac's QR code"
        SyncLoop.Status.SEARCHING -> "Looking for your Mac…"
        SyncLoop.Status.CONNECTED -> if (loop?.path == SyncLoop.Path.BLUETOOTH) "Connected to Mac via Bluetooth" else "Connected to Mac"
        SyncLoop.Status.RETRYING -> "Mac not reachable — retrying"
        SyncLoop.Status.PAIRING_INVALID -> "Mac rejected this phone — scan the Mac's QR code again"
    }

    /** Wake the reconnect loop early when the network comes back. */
    private fun watchNetwork() {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { loop?.wake() }
        }
        runCatching { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(callback) }
        networkCallback = callback
    }

    private fun shutdown() {
        main.removeCallbacks(pauseTick)
        Files.cache(this).clear()
        runCatching { getSystemService(NotificationManager::class.java).cancel(OFFER_NOTIFICATION_ID) }
        instance = null
        val old = loop
        loop = null
        networkCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        networkCallback = null
        if (old != null) Thread { old.stop() }.start()  // stop() joins the worker; keep it off the main thread
        setStatus("Stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun setStatus(text: String) {
        status = text
        if (loop != null) {
            runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text)) }
        }
        main.post { onStatusChanged?.invoke() }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Clipboard sync", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(FILES_CHANNEL, "Files from your Mac", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val sendNow = PendingIntent.getActivity(
            this, 2, GhostActivity.readIntent(this, announce = true), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(app.clipbridge.R.drawable.ic_stat_clipbridge)
            .setContentTitle("ClipBridge")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Send clipboard to Mac", sendNow).build())
            .build()
    }
}
