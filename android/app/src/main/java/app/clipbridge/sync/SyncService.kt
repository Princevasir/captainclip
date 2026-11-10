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

        /** After re-pairing: (re)start and connect with the new pairing right away. */
        fun reconnect(ctx: Context) = command(ctx, ACTION_RECONNECT, foreground = true)

        /**
         * Send text to the Mac, in-process (no Intent, so no Binder size limit). Never starts the
         * service: if sync is stopped, nothing is sent. Returns true if queued for the Mac.
         */
        fun send(text: String, html: String? = null): Boolean = instance?.loop?.send(text, html) ?: false

        /** Fetch (and verify) the Mac's offered file. Blocking; used by FileOfferProvider on a binder thread. */
        fun fetchOffer(id: String, dir: File): File {
            val loop = instance?.loop ?: throw java.io.FileNotFoundException("not connected to your Mac")
            return try {
                val size = Files.cache(instance!!).offer(id)?.size ?: 0L
                val (total, first) = SyncLoop.fetchTimeouts(size, loop.isOnBluetooth)
                loop.fetch(id, dir, timeoutMs = total, firstDataMs = first, size = size)  // the pasting app is waiting
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

        /** Share → Send to Mac: send already-copied files (deleted afterwards). In-process, no URI grants. */
        /**
         * A copied image (already copied into our cache) → the Mac's clipboard. Duplicate triggers for the same
         * image within a few seconds (copy button + "Copied" toast) are sent once. Returns true if queued.
         */
        fun sendClipboardImage(file: OutgoingFile, key: String): Boolean {
            val service = instance ?: return false.also { file.file.delete() }
            val now = android.os.SystemClock.elapsedRealtime()
            if (key == lastImageKey && now - lastImageAt < 5_000) { file.file.delete(); return true }
            lastImageKey = key; lastImageAt = now
            service.uploadFiles(listOf(file))
            return true
        }
        @Volatile private var lastImageKey: String? = null
        @Volatile private var lastImageAt = 0L

        fun sendLocalFiles(files: List<OutgoingFile>): Boolean {
            val service = instance ?: return false
            service.uploadFiles(files)
            return true
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
            ACTION_RECONNECT -> if (ensureRunning()) loop?.reconnect()
            // Notification "Save": every file of the set (an id saves just that one).
            ACTION_SAVE_OFFER -> if (loop != null) {
                val id = intent.getStringExtra(EXTRA_OFFER_ID)
                (if (id != null) listOf(id) else Files.cache(this).currentAll().map { it.id }).forEach { saveOffer(it) }
            }
            else -> {  // START, or a sticky restart with a null intent
                if (!Prefs.syncEnabled(this) || Prefs.pairing(this) == null) { shutdown(); return START_NOT_STICKY }
                ensureRunning()
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
    // once the full/lite flavours exist.
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
            bluetoothLink = { info -> BleLink.find(this, info) },
            callbacks = object : SyncLoop.Callbacks {
                override fun onStatus(status: SyncLoop.Status) {
                    state = status
                    setStatus(describe(status))
                }
                override fun onClip(text: String) = writeClipboard(text, null)
                override fun onClip(text: String, html: String?) = writeClipboard(text, html)
                override fun onOffer(offer: Message.Offer?) = handleOffers(listOfNotNull(offer))
                override fun onOffers(offers: List<Message.Offer>) = handleOffers(offers)
            },
        )
        loop = newLoop
        instance = this
        watchNetwork()
        newLoop.start()
        return true
    }

    private val io = Executors.newSingleThreadExecutor()

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
                    l.fetch(id, cache.fetchDir, timeoutMs = total, firstDataMs = first, size = offer.size).also { cache.store(id, it) }
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
    private fun uploadFiles(files: List<OutgoingFile>) {
        io.execute {
            var sent = 0
            for (f in files) {
                try {
                    if (f.file.length() > FileLimit.MAX_BYTES) { toast("${f.name} is too large (max 25 MB)"); continue }
                    if (f.purpose != "clipboard") transferNotice("Sending ${f.name} to Mac…", null, ongoing = true)
                    val ok = runCatching { loop?.sendFile(f.name, f.mime, f.file.length(), f.purpose) { f.file.inputStream() } ?: false }
                        .onFailure { android.util.Log.w("ClipBridgeFiles", "upload of ${f.name} failed", it) }
                        .getOrDefault(false)
                    android.util.Log.i("ClipBridgeFiles", "upload of ${f.name} ok=$ok")
                    if (ok) sent++ else if (f.purpose != "clipboard") toast(
                        if (loop?.isOnBluetooth == true && f.file.length() > app.clipbridge.core.FileLimit.BLUETOOTH_MAX_BYTES)
                            "${f.name} is too big for Bluetooth — connect both to the same Wi-Fi"
                        else "Couldn't send ${f.name} — not connected to your Mac")
                } finally {
                    f.file.delete()
                }
            }
            val done = if (sent == 1) "Sent to Mac" else "Sent $sent files to Mac"
            if (sent > 0 && files.any { it.purpose != "clipboard" }) { toast(done); transferNotice(done, null) }
        }
    }

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
        main.post {
            runCatching {
                OwnWrites.markNow()  // so copy detection ignores the system "Copied" overlay this causes
                val clip = if (html != null) ClipData.newHtmlText("ClipBridge", text, html) else ClipData.newPlainText("ClipBridge", text)
                markFromMac(clip.description)
                getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
            }
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
