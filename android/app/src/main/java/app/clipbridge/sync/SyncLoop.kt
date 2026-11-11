package app.clipbridge.sync

import app.clipbridge.core.FileLimit
import app.clipbridge.core.FileReceiver
import app.clipbridge.core.FileSender
import app.clipbridge.core.LoopGuard
import app.clipbridge.core.Message
import app.clipbridge.core.PairingInfo
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A Mac file couldn't be fetched. [reason]: expired, changed, too-large, io, cancelled, bad-data, timeout, not-connected. */
class FileFetchException(val reason: String) : IOException(reason)

/**
 * The phone's reconnect loop, free of Android types so it can be unit-tested:
 * find Mac → connect → wait until closed → back off → repeat.
 *
 * Every [start] begins a new generation. [stop] ends it and waits for the worker, and a worker
 * re-checks its generation after discovery and before connecting, so a stopped run can never leave
 * a connection behind or keep delivering clips.
 */
class SyncLoop(
    private val pairing: () -> PairingInfo?,
    private val locate: (PairingInfo) -> Pair<String, Int>?,
    private val device: String,
    private val callbacks: Callbacks,
    private val backoff: Backoff = Backoff(),
    private val pingIntervalMs: Long = 25_000,
    /** How to reach the Mac over Wi-Fi (tests swap in other links). */
    private val wifiLink: (host: String, port: Int) -> Link = { host, port -> TcpLink(host, port) },
    /** Bluetooth fallback: finds and opens a BLE link to this Mac (blocking scan), or null if not found/allowed.
     *  Null factory = no Bluetooth (no permission / no radio). */
    private val bluetoothLink: ((PairingInfo) -> Link?)? = null,
    /** While on Bluetooth: is the Mac reachable over Wi-Fi again? Default: quick TCP connect. */
    private val wifiProbe: ((PairingInfo) -> Boolean)? = null,
    private val wifiProbeIntervalMs: Long = 30_000,
    /** Wait after each Bluetooth scan that found nothing (battery): 1, 2, 5, then every 10 min; reset by [wake] or a connection. */
    private val bluetoothRetryDelaysMs: LongArray = longArrayOf(60_000, 120_000, 300_000, 600_000),
) {
    enum class Path { WIFI, BLUETOOTH }

    /** True while connected over Bluetooth. */
    val isOnBluetooth: Boolean get() = connection?.isBluetooth == true

    /** How we're connected right now (null when not connected). */
    @Volatile var path: Path? = null
        private set

    enum class Status { STOPPED, NOT_PAIRED, SEARCHING, CONNECTED, RETRYING, PAIRING_INVALID }

    interface Callbacks {
        fun onStatus(status: Status)
        fun onClip(text: String)
        /** Same clip with its HTML (formatted text), when present. Defaults to the plain callback. */
        fun onClip(text: String, html: String?) = onClip(text)
        /** The Mac offered a file (or null: the offer was cleared). */
        fun onOffer(offer: Message.Offer?) {}
        /** The Mac offered several files together. Defaults to offering the first. */
        fun onOffers(offers: List<Message.Offer>) = onOffer(offers.firstOrNull())
    }

    private class PendingFetch(val id: String, val dir: File) {
        val done = CountDownLatch(1)
        /** Counted down when the Mac starts answering (file-start or file-error). */
        val started = CountDownLatch(1)
        @Volatile var file: File? = null
        @Volatile var reason: String? = null
        @Volatile var receiver: FileReceiver? = null
    }

    private val fetches = ConcurrentHashMap<String, PendingFetch>()
    private val sendLock = Object()
    private val uploads = java.util.concurrent.atomic.AtomicInteger()

    /** A paste/save or an upload is in progress (switching links now would break it). */
    private val busy: Boolean get() = fetches.isNotEmpty() || uploads.get() > 0

    /** Woken by [wake] (a network appeared) so the Bluetooth→Wi-Fi check runs right away. */
    private val probeSignal = Object()
    @Volatile private var probeNow = false
    @Volatile private var bluetoothMisses = 0
    @Volatile private var bluetoothNotBefore = 0L

    private val lock = Object()
    private var generation = 0 // guarded by lock
    private var worker: Thread? = null // guarded by lock
    private var wakeRequested = false // guarded by lock
    @Volatile private var connection: Connection? = null
    private val loopGuard = LoopGuard()

    val isRunning: Boolean get() = synchronized(lock) { worker != null }

    /** Name of the Mac we're connected to, if any. */
    val macName: String? get() = connection?.macName

    fun start() = synchronized(lock) {
        if (worker != null) return
        val gen = ++generation
        worker = Thread({ run(gen) }, "clipbridge-sync").apply { isDaemon = true; start() }
    }

    fun stop() {
        val (oldWorker, oldConnection) = synchronized(lock) {
            generation++
            val w = worker
            worker = null
            lock.notifyAll()
            w to connection
        }
        oldConnection?.close()
        oldWorker?.interrupt()
        if (oldWorker != null && oldWorker !== Thread.currentThread()) oldWorker.join(JOIN_TIMEOUT_MS)
        callbacks.onStatus(Status.STOPPED)
    }

    /** Drop the current connection and connect again right away (e.g. after re-pairing). */
    fun reconnect() {
        backoff.reset()
        wake()
        connection?.close()
    }

    /** Skip the current back-off wait (e.g. Wi-Fi came back). */
    fun wake() {
        synchronized(lock) {
            bluetoothNotBefore = 0  // the network changed: worth looking over Bluetooth again
            wakeRequested = true
            lock.notifyAll()
        }
        synchronized(probeSignal) { probeNow = true; probeSignal.notifyAll() }
    }

    /**
     * Send text to the Mac. False if not connected, too large, or it is an echo of what the Mac sent.
     * [userInitiated] (an explicit "Send to Mac", e.g. from history) skips the echo check: the tap is the intent.
     */
    fun send(text: String, html: String? = null, userInitiated: Boolean = false): Boolean {
        if (text.isEmpty()) return false
        if (!userInitiated && !loopGuard.shouldSend(text)) return false
        val conn = connection ?: return false
        return conn.send(text, html).also { if (it) loopGuard.markSent(text) }
    }

    /**
     * Fetches Mac offer [id] into [dir], blocking until the file has arrived and been verified.
     * Concurrent calls for the same id share one transfer. Never call on the main thread.
     */
    fun fetch(id: String, dir: File, timeoutMs: Long = 60_000, firstDataMs: Long = timeoutMs, size: Long = -1): File {
        // Known to be too big for Bluetooth: say so at once instead of asking the Mac.
        if (size > FileLimit.BLUETOOTH_MAX_BYTES && isOnBluetooth) throw FileFetchException("too-large-bluetooth")
        var isNew = false
        val pending = synchronized(fetches) {
            fetches[id] ?: PendingFetch(id, dir).also { fetches[id] = it; isNew = true }
        }
        if (isNew) {
            val conn = connection
            if (conn == null || !conn.sendControl(Message.Fetch(id))) fail(pending, "not-connected")
        }
        val begin = System.currentTimeMillis()
        // A Mac that never starts answering (asleep, half-open connection) fails fast, so a paste doesn't freeze.
        if (!pending.started.await(firstDataMs, TimeUnit.MILLISECONDS) && pending.done.count > 0) fail(pending, "timeout")
        val left = (timeoutMs - (System.currentTimeMillis() - begin)).coerceAtLeast(0)
        if (!pending.done.await(left, TimeUnit.MILLISECONDS)) fail(pending, "timeout")
        pending.file?.let { return it }
        throw FileFetchException(pending.reason ?: "io")
    }

    /** Sends one file to the Mac (one at a time). Blocks while streaming; never call on the main thread. */
    /** [purpose] "clipboard" asks the Mac to put the file (an image) on its clipboard instead of Downloads. */
    fun sendFile(name: String, mime: String, size: Long, purpose: String = "file", input: () -> InputStream): Boolean = synchronized(sendLock) {
        if (size < 0 || size > FileLimit.MAX_BYTES) return false
        val conn = connection ?: return false
        if (conn.isBluetooth && size > FileLimit.BLUETOOTH_MAX_BYTES) return false  // too slow over Bluetooth
        uploads.incrementAndGet()
        try { upload(conn, name, mime, size, purpose, input) } finally { uploads.decrementAndGet() }
    }

    private fun upload(conn: Connection, name: String, mime: String, size: Long, purpose: String, input: () -> InputStream): Boolean {
        val sender = FileSender(UUID.randomUUID().toString(), input, name, mime, size, purpose)
        val start = try { sender.start() } catch (_: Exception) { return false }
        if (!conn.sendControl(start)) return false
        try {
            for (chunk in sender.chunks()) if (!conn.sendChunk(chunk)) return false
        } catch (_: Exception) {
            // Reading the shared file failed midway: tell the Mac to throw away the partial file.
            conn.sendControl(Message.FileError(sender.id, "cancelled"))
            return false
        }
        return conn.sendChunk(sender.end())  // via the chunk queue so it can't overtake the last chunks
    }

    private fun fail(pending: PendingFetch, reason: String) {
        synchronized(fetches) { if (fetches[pending.id] === pending) fetches.remove(pending.id) }
        pending.receiver?.abort()
        if (pending.file == null && pending.reason == null) pending.reason = reason
        pending.done.countDown()
    }

    private fun onFileMessage(message: Message) {
        when (message) {
            is Message.Offer -> {
                fetches.values.filter { it.id != message.id }.forEach { fail(it, "cancelled") }
                callbacks.onOffer(message)
            }
            is Message.Offers -> {
                val ids = message.items.map { it.id }.toSet()
                fetches.values.filter { it.id !in ids }.forEach { fail(it, "cancelled") }
                callbacks.onOffers(message.offers)
            }
            is Message.OfferClear -> callbacks.onOffer(null)
            is Message.FileStart -> fetches[message.id]?.let { p ->
                p.started.countDown()
                try { p.receiver = FileReceiver(message, p.dir) } catch (_: Exception) { fail(p, "too-large") }
            }
            is Message.FileChunk, is Message.FileEnd -> {
                val id = if (message is Message.FileChunk) message.id else (message as Message.FileEnd).id
                val p = fetches[id] ?: return
                val receiver = p.receiver ?: return
                try {
                    receiver.accept(message)?.let { file ->
                        p.file = file
                        synchronized(fetches) { if (fetches[id] === p) fetches.remove(id) }
                        p.done.countDown()
                    }
                } catch (_: Exception) {
                    fail(p, "bad-data")
                }
            }
            is Message.FileError -> fetches[message.id]?.let { it.started.countDown(); fail(it, message.reason) }
            else -> {}
        }
    }

    private fun alive(gen: Int) = synchronized(lock) { generation == gen }

    private fun status(gen: Int, status: Status) {
        if (alive(gen)) callbacks.onStatus(status)
    }

    private fun probeWifi(info: PairingInfo): Boolean = wifiProbe?.invoke(info) ?: run {
        val (host, port) = runCatching { locate(info) }.getOrNull() ?: (info.host to info.port)
        runCatching { java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, port), 2_000) } }.isSuccess
    }

    private fun run(gen: Int) {
        var authFailures = 0
        var wifiFailures = 0
        try {
            while (alive(gen)) {
                val info = pairing()
                if (info == null) {
                    status(gen, Status.NOT_PAIRED)
                    pause(gen, backoff.maxDelayMs)
                    continue
                }
                status(gen, Status.SEARCHING)
                // Wi-Fi first; Bluetooth only after two Wi-Fi attempts that never connected.
                val useBluetooth = bluetoothLink != null && wifiFailures >= WIFI_FAILURES_BEFORE_BLUETOOTH &&
                    System.currentTimeMillis() >= bluetoothNotBefore
                val link: Link = if (useBluetooth) {
                    val found = try {
                        bluetoothLink!!.invoke(info)
                    } catch (e: InterruptedException) {
                        throw e
                    } catch (_: Exception) {
                        null  // radio turned off mid-scan, etc.: never crash the app
                    }
                    found ?: run {
                        // Mac not found over Bluetooth either: back to Wi-Fi, and don't scan again for a while (battery).
                        bluetoothNotBefore = System.currentTimeMillis() +
                            bluetoothRetryDelaysMs[bluetoothMisses.coerceAtMost(bluetoothRetryDelaysMs.size - 1)]
                        bluetoothMisses++
                        wifiFailures = 0
                        status(gen, Status.RETRYING)
                        pause(gen, backoff.nextDelayMs())
                        null
                    } ?: continue
                } else {
                    val (host, port) = locate(info) ?: (info.host to info.port)
                    wifiLink(host, port)
                }
                if (!alive(gen)) { link.close(); return }

                val closed = CountDownLatch(1)
                var reason = Connection.CloseReason.LOST
                var acked = false
                val conn = Connection(link, info.key, device, object : Connection.Listener {
                    override fun onConnected() {
                        acked = true
                        authFailures = 0
                        bluetoothMisses = 0
                        bluetoothNotBefore = 0
                        if (!link.isBluetooth) wifiFailures = 0
                        backoff.reset()
                        path = if (link.isBluetooth) Path.BLUETOOTH else Path.WIFI
                        status(gen, Status.CONNECTED)
                    }
                    override fun onClip(text: String) = onClip(text, null)
                    override fun onClip(text: String, html: String?) {
                        if (!alive(gen)) return
                        loopGuard.markReceived(text)
                        callbacks.onClip(text, html)
                    }
                    override fun onMessage(message: Message) {
                        if (alive(gen)) onFileMessage(message)
                    }
                    override fun onClosed(reason0: Connection.CloseReason) {
                        reason = reason0
                        closed.countDown()
                    }
                }, pingIntervalMs)

                synchronized(lock) {
                    if (generation != gen) return // stopped while we were discovering
                    connection = conn
                }
                conn.start()
                val prober = if (link.isBluetooth) Thread({
                    // On Bluetooth: keep checking Wi-Fi and move back as soon as it works.
                    try {
                        while (closed.count > 0 && alive(gen)) {
                            synchronized(probeSignal) {
                                if (!probeNow) probeSignal.wait(wifiProbeIntervalMs)
                                probeNow = false
                            }
                            // Never switch mid-transfer: a Bluetooth paste or upload would fail.
                            if (closed.count > 0 && !busy && probeWifi(info)) { wifiFailures = 0; conn.close(); break }
                        }
                    } catch (_: InterruptedException) {}
                }, "clipbridge-wifi-probe").apply { isDaemon = true; start() } else null
                try {
                    closed.await()
                } finally {
                    prober?.interrupt()
                    conn.close()
                    path = null
                    synchronized(lock) { if (connection === conn) connection = null }
                    fetches.values.toList().forEach { fail(it, "io") }  // a dropped connection ends any fetch
                }
                if (!alive(gen)) return

                if (!link.isBluetooth && !acked && reason == Connection.CloseReason.LOST) wifiFailures++
                if (link.isBluetooth && wifiFailures > 0) wifiFailures = WIFI_FAILURES_BEFORE_BLUETOOTH - 1
                if (reason == Connection.CloseReason.AUTH_FAILED) authFailures++
                if (authFailures >= AUTH_FAILURES_BEFORE_INVALID) {
                    status(gen, Status.PAIRING_INVALID)
                    pause(gen, backoff.maxDelayMs)
                } else {
                    status(gen, Status.RETRYING)
                    pause(gen, backoff.nextDelayMs())
                }
            }
        } catch (_: InterruptedException) {
            // stop() interrupted discovery or a wait
        }
    }

    private fun pause(gen: Int, ms: Long) = synchronized(lock) {
        if (!wakeRequested && generation == gen) lock.wait(ms)
        wakeRequested = false
    }

    companion object {
        const val JOIN_TIMEOUT_MS = 2_000L
        const val AUTH_FAILURES_BEFORE_INVALID = 3
        const val WIFI_FAILURES_BEFORE_BLUETOOTH = 2

        /** (total, first-data) timeouts for fetching a Mac file while an app waits on the paste. */
        fun fetchTimeouts(size: Long, bluetooth: Boolean): Pair<Long, Long> =
            if (bluetooth) (10_000L + size / 30) to 6_000L else 20_000L to 4_000L

        /** (total, first-data) timeouts for Save to Downloads: nobody is waiting, so allow slow Bluetooth (20 KB/s). */
        fun saveTimeouts(size: Long, bluetooth: Boolean): Pair<Long, Long> =
            if (bluetooth) (30_000L + size / 20) to 30_000L else 60_000L to 60_000L
    }
}
