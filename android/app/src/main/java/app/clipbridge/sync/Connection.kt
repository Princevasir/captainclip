package app.clipbridge.sync

import app.clipbridge.core.ClipCrypto
import app.clipbridge.core.Frame
import app.clipbridge.core.FrameReader
import app.clipbridge.core.Message
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One encrypted TCP connection to the Mac. Sends hello first, pings every [pingIntervalMs],
 * and closes on any decrypt/parse/IO error. [Listener.onClosed] fires exactly once.
 * Listener callbacks run on a background thread.
 */
class Connection(
    private val host: String,
    private val port: Int,
    key: ByteArray,
    private val device: String,
    private val listener: Listener,
    private val pingIntervalMs: Long = 25_000,
) {
    interface Listener {
        fun onConnected()
        fun onClip(text: String)
        fun onClosed()
    }

    private val crypto = ClipCrypto(key)
    private val socket = Socket()
    private val closed = AtomicBoolean(false)
    @Volatile private var output: OutputStream? = null

    fun start() {
        Thread({ run() }, "clipbridge-conn").apply { isDaemon = true; start() }
    }

    /** False if not connected or [text] is too large for one frame. */
    fun send(text: String): Boolean {
        if (!Frame.fits(text)) return false
        return write(Message.Clip(UUID.randomUUID().toString(), text, System.currentTimeMillis()))
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.close() }
        listener.onClosed()
    }

    private fun run() {
        try {
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.tcpNoDelay = true
            output = socket.getOutputStream()
            if (!write(Message.Hello(device))) return close()
            listener.onConnected()
            startPinger()
            val reader = FrameReader(socket.getInputStream())
            while (!closed.get()) {
                val message = Message.parse(String(crypto.open(reader.next())))
                if (message is Message.Clip) listener.onClip(message.text)
            }
        } catch (_: Exception) {
            // IO error, wrong key, bad frame or bad JSON: drop the connection.
        }
        close()
    }

    private fun write(message: Message): Boolean {
        val out = output ?: return false
        if (closed.get()) return false
        return try {
            val frame = Frame.encode(crypto.seal(message.toJson().toByteArray()))
            synchronized(out) { out.write(frame); out.flush() }
            true
        } catch (_: Exception) {
            close()
            false
        }
    }

    private fun startPinger() {
        Thread({
            try {
                while (!closed.get()) {
                    Thread.sleep(pingIntervalMs)
                    write(Message.Ping)
                }
            } catch (_: InterruptedException) {}
        }, "clipbridge-ping").apply { isDaemon = true; start() }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
    }
}
