package app.clipbridge.sync

import app.clipbridge.core.ClipCrypto
import app.clipbridge.core.Direction
import app.clipbridge.core.Frame
import app.clipbridge.core.FrameReader
import app.clipbridge.core.Message
import app.clipbridge.core.RichClip
import app.clipbridge.core.TextLimit
import java.io.IOException
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One encrypted TCP connection to the Mac (protocol v2):
 * read the Mac's 16-byte challenge → send hello → wait for the Mac's hello (proof it has the key)
 * → connected. Pings every [pingIntervalMs]; the Mac answers, so silence for [readTimeoutMs]
 * means it's gone. [Listener.onClosed] fires exactly once, with why.
 * All socket writes happen on this connection's own threads, so [send] is safe on Android's main thread.
 */
class Connection(
    private val link: Link,
    key: ByteArray,
    private val device: String,
    private val listener: Listener,
    private val pingIntervalMs: Long = 25_000,
    private val readTimeoutMs: Int = 60_000,
) {
    /** Wi-Fi convenience constructor. */
    constructor(host: String, port: Int, key: ByteArray, device: String, listener: Listener,
                pingIntervalMs: Long = 25_000, readTimeoutMs: Int = 60_000) :
        this(TcpLink(host, port), key, device, listener, pingIntervalMs, readTimeoutMs)

    /** True when this connection runs over Bluetooth. */
    val isBluetooth: Boolean get() = link.isBluetooth

    enum class CloseReason {
        /** Network trouble, Mac went away/silent, or we closed it. Retry later. */
        LOST,
        /** The Mac rejected our key or sent something we couldn't decrypt. Pairing is probably stale. */
        AUTH_FAILED,
    }

    interface Listener {
        fun onConnected()
        fun onClip(text: String)
        /** Same clip with its HTML (formatted text), when the Mac sent one. Defaults to the plain callback. */
        fun onClip(text: String, html: String?) = onClip(text)
        fun onClosed(reason: CloseReason)
        /** File messages from the Mac (offer, offer-clear, file-start/chunk/end/error). Background thread. */
        fun onMessage(message: Message) {}
    }

    private val crypto = ClipCrypto(key)
    private val closed = AtomicBoolean(false)
    /** Text, pings and file control messages: always written before any queued file chunk. */
    private val outbox = LinkedBlockingQueue<Message>(OUTBOX_CAPACITY)
    /** File chunks: small bound so a waiting text never sits behind more than a few chunks. */
    private val chunks = LinkedBlockingQueue<Message>(CHUNK_CAPACITY)
    @Volatile private var output: OutputStream? = null
    @Volatile private var challenge: ByteArray? = null
    @Volatile private var acked = false

    /** The Mac's name, from its hello (e.g. "Alex's MacBook Pro"). Null until connected. */
    @Volatile var macName: String? = null
        private set

    fun start() {
        Thread({ run() }, "clipbridge-conn").apply { isDaemon = true; start() }
    }

    /** Queues [text] for the Mac. False if not connected, too large, or the outbox is full. Never blocks. */
    fun send(text: String, html: String? = null): Boolean {
        if (!TextLimit.fits(text)) return false
        return enqueue(Message.Clip(UUID.randomUUID().toString(), text, System.currentTimeMillis(), RichClip.fitting(text, html)))
    }

    /** Queues a file control message (fetch, file-start, file-end, file-error). Never blocks. */
    fun sendControl(message: Message): Boolean = enqueue(message)

    /**
     * Queues one file chunk — or the file-end that must follow the chunks in order — waiting for room
     * if needed. Call from a worker thread, never the main thread. False if the connection closes.
     */
    fun sendChunk(chunk: Message): Boolean {
        while (acked && !closed.get()) {
            if (chunks.offer(chunk, 200, TimeUnit.MILLISECONDS)) return true
        }
        return false
    }

    fun close() = close(CloseReason.LOST)

    private fun close(reason: CloseReason) {
        if (!closed.compareAndSet(false, true)) return
        link.close()
        listener.onClosed(reason)
    }

    private fun enqueue(message: Message): Boolean = acked && !closed.get() && outbox.offer(message)

    private fun run() {
        var helloSent = false
        try {
            val (input, out) = link.open(readTimeoutMs)
            val reader = FrameReader(input)
            challenge = reader.next().takeIf { it.size == CHALLENGE_BYTES } ?: return close(CloseReason.LOST)
            output = out
            if (!write(Message.Hello(device))) return
            helloSent = true
            val inbound = ClipCrypto.sessionAad(Direction.MAC_TO_PHONE, challenge!!)
            while (!closed.get()) {
                val message = Message.parse(String(crypto.open(reader.next(), inbound)))
                when {
                    message is Message.Hello && !acked -> {
                        macName = message.device
                        acked = true
                        listener.onConnected()
                        startWriter()
                        startPinger()
                    }
                    message is Message.Clip && acked && TextLimit.fits(message.text) ->
                        listener.onClip(message.text, RichClip.fitting(message.text, message.html))
                    acked && (message is Message.Offer || message is Message.Offers || message is Message.OfferClear || message is Message.FileStart ||
                        message is Message.FileChunk || message is Message.FileEnd || message is Message.FileError) ->
                        listener.onMessage(message)
                }
            }
        } catch (_: GeneralSecurityException) {
            return close(CloseReason.AUTH_FAILED)
        } catch (_: IllegalArgumentException) {
            return close(CloseReason.AUTH_FAILED)
        } catch (_: java.net.SocketTimeoutException) {
            return close(CloseReason.LOST)  // silence (slow or dropped link) says nothing about the key
        } catch (_: IOException) {
            // The real Mac hangs up right after a hello it can't decrypt (wrong/stale key).
            return close(if (helloSent && !acked) CloseReason.AUTH_FAILED else CloseReason.LOST)
        }
        close(CloseReason.LOST)
    }

    private fun write(message: Message): Boolean {
        val out = output ?: return false
        val ch = challenge ?: return false
        if (closed.get()) return false
        return try {
            val aad = ClipCrypto.sessionAad(Direction.PHONE_TO_MAC, ch)
            val frame = Frame.encode(crypto.seal(message.toJson().toByteArray(), aad = aad))
            synchronized(out) { out.write(frame); out.flush() }
            true
        } catch (_: Exception) {
            close(CloseReason.LOST)
            false
        }
    }

    private fun startWriter() {
        Thread({
            while (!closed.get()) {
                // Control/text first; a chunk only when nothing else is waiting.
                val message: Message = outbox.poll() ?: chunks.poll() ?: outbox.poll(20, TimeUnit.MILLISECONDS) ?: continue
                if (!write(message)) break
            }
        }, "clipbridge-writer").apply { isDaemon = true; start() }
    }

    private fun startPinger() {
        Thread({
            try {
                while (!closed.get()) {
                    Thread.sleep(pingIntervalMs)
                    enqueue(Message.Ping)
                }
            } catch (_: InterruptedException) {}
        }, "clipbridge-ping").apply { isDaemon = true; start() }
    }

    private companion object {
        const val CHALLENGE_BYTES = 16
        const val OUTBOX_CAPACITY = 16
        const val CHUNK_CAPACITY = 4
    }
}
