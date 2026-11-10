package app.clipbridge.sync

import app.clipbridge.core.LoopGuard
import app.clipbridge.core.PairingInfo
import java.util.concurrent.CountDownLatch

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
    }

    private val lock = Object()
    private var generation = 0 // guarded by lock
    private var worker: Thread? = null // guarded by lock
    private var wakeRequested = false // guarded by lock
    @Volatile private var connection: Connection? = null
    private val loopGuard = LoopGuard()

    val isRunning: Boolean get() = synchronized(lock) { worker != null }

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
