package app.clipbridge.sync

import app.clipbridge.core.ClipCrypto
import app.clipbridge.core.Direction
import app.clipbridge.core.FileReceiver
import app.clipbridge.core.FileSender
import java.io.ByteArrayInputStream
import java.io.File
import app.clipbridge.core.Frame
import app.clipbridge.core.FrameReader
import app.clipbridge.core.Message
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Protocol-v2 fake Mac that accepts any number of phones, like the real one (newest wins). */
class MultiFakeMac(private val key: ByteArray, var rejectKey: Boolean = false, private val uploadDir: File? = null) {
    /** Files this fake Mac will serve on fetch, by offer id. */
    val served = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    /** Offer ids answered with file-error and this reason. */
    val refused = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** If set, close the phone's socket after sending this many chunks of a served file. */
    @Volatile var dropAfterChunks: Int? = null
    /** If true, fetches are never answered (Mac asleep / half-open connection). */
    @Volatile var ignoreFetch = false
    val uploads = CopyOnWriteArrayList<File>()
    private val receivers = java.util.concurrent.ConcurrentHashMap<String, FileReceiver>()
    val server = ServerSocket(0)
    val port get() = server.localPort
    val accepts = AtomicInteger()
    val received = CopyOnWriteArrayList<Message>()
    private val sessions = CopyOnWriteArrayList<Pair<Socket, ByteArray>>()
    private val crypto = ClipCrypto(key)

    val liveConnections: Int get() = sessions.count { !it.first.isClosed }

    init {
        Thread {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                accepts.incrementAndGet()
                Thread { serve(socket) }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun serve(socket: Socket) {
        val challenge = ByteArray(16).also { SecureRandom().nextBytes(it) }
        try {
            socket.getOutputStream().write(Frame.encode(challenge))
            val reader = FrameReader(socket.getInputStream())
            val inbound = ClipCrypto.sessionAad(Direction.PHONE_TO_MAC, challenge)
            while (true) {
                val message = Message.parse(String(crypto.open(reader.next(), inbound)))
                received.add(message)
                if (message is Message.Hello) {
                    if (rejectKey) { socket.close(); return }
                    sessions.forEach { runCatching { it.first.close() } }  // newest phone replaces older
                    sessions.add(socket to challenge)
                    write(socket, challenge, Message.Hello("FakeMac"))
                } else if (message is Message.Ping) {
                    write(socket, challenge, Message.Ping)
                } else if (message is Message.Fetch) {
                    serveFetch(socket, challenge, message.id)
                } else if (message is Message.FileStart && uploadDir != null) {
                    receivers[message.id] = FileReceiver(message, uploadDir)
                } else if (message is Message.FileError) {
                    receivers.remove(message.id)?.abort()
                } else if (message is Message.FileChunk || message is Message.FileEnd) {
                    val id = if (message is Message.FileChunk) message.id else (message as Message.FileEnd).id
                    receivers[id]?.accept(message)?.let { uploads.add(it); receivers.remove(id) }
                }
            }
        } catch (_: Exception) {
            runCatching { socket.close() }
        }
    }

    private fun write(socket: Socket, challenge: ByteArray, message: Message) {
        val box = crypto.seal(message.toJson().toByteArray(), aad = ClipCrypto.sessionAad(Direction.MAC_TO_PHONE, challenge))
        synchronized(socket) { socket.getOutputStream().write(Frame.encode(box)) }
    }

    private fun serveFetch(socket: Socket, challenge: ByteArray, id: String) {
        if (ignoreFetch) return
        refused[id]?.let { write(socket, challenge, Message.FileError(id, it)); return }
        val bytes = served[id] ?: run { write(socket, challenge, Message.FileError(id, "expired")); return }
        val sender = FileSender(id, { ByteArrayInputStream(bytes) }, "file.pdf", "application/pdf", bytes.size.toLong())
        write(socket, challenge, sender.start())
        var sent = 0
        for (chunk in sender.chunks()) {
            if (dropAfterChunks == sent) { socket.close(); return }
            write(socket, challenge, chunk); sent++
        }
        write(socket, challenge, sender.end())
    }

    /** Send any message to the current phone. */
    fun send(message: Message) {
        val (socket, challenge) = sessions.last { !it.first.isClosed }
        write(socket, challenge, message)
    }

    /** Send a clip to the current (newest live) phone. */
    fun sendClip(text: String) {
        val (socket, challenge) = sessions.last { !it.first.isClosed }
        write(socket, challenge, Message.Clip("id", text, 1))
    }

    fun close() { server.close(); sessions.forEach { runCatching { it.first.close() } } }
}
