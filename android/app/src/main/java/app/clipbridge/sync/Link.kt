package app.clipbridge.sync

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** A reliable, ordered byte stream to the Mac: TCP on Wi-Fi or the Bluetooth LE link. */
interface Link {
    val isBluetooth: Boolean
    /** Connects (blocking). [readTimeoutMs] = longest silence before reads fail. */
    fun open(readTimeoutMs: Int): Pair<InputStream, OutputStream>
    fun close()
}

class TcpLink(private val host: String, private val port: Int) : Link {
    override val isBluetooth = false
    private val socket = Socket()

    override fun open(readTimeoutMs: Int): Pair<InputStream, OutputStream> {
        socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        socket.tcpNoDelay = true
        socket.soTimeout = readTimeoutMs
        return socket.getInputStream() to socket.getOutputStream()
    }

    override fun close() { runCatching { socket.close() } }

    private companion object { const val CONNECT_TIMEOUT_MS = 5_000 }
}
