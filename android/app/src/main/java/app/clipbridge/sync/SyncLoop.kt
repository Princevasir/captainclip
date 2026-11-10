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
) {
    enum class Status { STOPPED, NOT_PAIRED, SEARCHING, CONNECTED, RETRYING, PAIRING_INVALID }

    interface Callbacks {
        fun onStatus(status: Status)
        fun onClip(text: String)
        /** The Mac offered a file (or null: the offer was cleared). */
        fun onOffer(offer: Message.Offer?) {}
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
    fun wake() = synchronized(lock) {
        wakeRequested = true
        lock.notifyAll()
    }

    /** Send text to the Mac. False if not connected, too large, or it is an echo of what the Mac sent. */
    fun send(text: String): Boolean {
        if (!loopGuard.shouldSend(text)) return false
        val conn = connection ?: return false
        return conn.send(text).also { if (it) loopGuard.markSent(text) }
    }

    /**
     * Fetches Mac offer [id] into [dir], blocking until the file has arrived and been verified.
     * Concurrent calls for the same id share one transfer. Never call on the main thread.
     */
    fun fetch(id: String, dir: File, timeoutMs: Long = 60_000, firstDataMs: Long = timeoutMs): File {
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
    fun sendFile(name: String, mime: String, size: Long, input: () -> InputStream): Boolean = synchronized(sendLock) {
        if (size < 0 || size > FileLimit.MAX_BYTES) return false
        val conn = connection ?: return false
        val sender = FileSender(UUID.randomUUID().toString(), input, name, mime, size)
        val start = try { sender.start() } catch (_: Exception) { return false }
        if (!conn.sendControl(start)) return false
        try {
            for (chunk in sender.chunks()) if (!conn.sendChunk(chunk)) return false
        } catch (_: Exception) {
            // Reading the shared file failed midway: tell the Mac to throw away the partial file.
            conn.sendControl(Message.FileError(sender.id, "cancelled"))
            return false
        }
        conn.sendChunk(sender.end())  // via the chunk queue so it can't overtake the last chunks
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

    private fun run(gen: Int) {
        var authFailures = 0
        try {
            while (alive(gen)) {
                val info = pairing()
                if (info == null) {
                    status(gen, Status.NOT_PAIRED)
                    pause(gen, backoff.maxDelayMs)
                    continue
                }
                status(gen, Status.SEARCHING)
                val (host, port) = locate(info) ?: (info.host to info.port)

                val closed = CountDownLatch(1)
                var reason = Connection.CloseReason.LOST
                val conn = Connection(host, port, info.key, device, object : Connection.Listener {
                    override fun onConnected() {
                        authFailures = 0
                        backoff.reset()
                        status(gen, Status.CONNECTED)
                    }
                    override fun onClip(text: String) {
                        if (!alive(gen)) return
                        loopGuard.markReceived(text)
                        callbacks.onClip(text)
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
                try {
                    closed.await()
                } finally {
                    conn.close()
                    synchronized(lock) { if (connection === conn) connection = null }
                    fetches.values.toList().forEach { fail(it, "io") }  // a dropped connection ends any fetch
                }
                if (!alive(gen)) return

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

    private companion object {
        const val JOIN_TIMEOUT_MS = 2_000L
        const val AUTH_FAILURES_BEFORE_INVALID = 3
    }
}
