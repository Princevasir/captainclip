package app.clipbridge.core

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Byte stream → BLE-sized packets. [sendPacket] blocks until the radio accepted it; false = link gone. */
class PacketOutputStream(private val mtu: Int, private val sendPacket: (ByteArray) -> Boolean) : OutputStream() {
    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        var pos = off
        val end = off + len
        while (pos < end) {
            val n = minOf(mtu, end - pos)
            if (!sendPacket(b.copyOfRange(pos, pos + n))) throw IOException("Bluetooth link closed")
            pos += n
        }
    }
}

/** BLE packets → byte stream. Reads block until data, [readTimeoutMs] of silence (SocketTimeoutException), or close. */
class PacketInputStream : InputStream() {
    @Volatile var readTimeoutMs: Int = 60_000
    private val packets = LinkedBlockingQueue<ByteArray>()
    private var current: ByteArray = ByteArray(0)
    private var pos = 0
    @Volatile private var ended = false

    fun offer(packet: ByteArray) { if (!ended) packets.put(packet) }

    fun closeFromRemote() { ended = true; packets.put(END) }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (pos >= current.size) {
            val next = packets.poll(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: throw SocketTimeoutException("Bluetooth link silent")
            if (next === END) { packets.put(END); return -1 }
            current = next; pos = 0
        }
        val n = minOf(len, current.size - pos)
        System.arraycopy(current, pos, b, off, n)
        pos += n
        return n
    }

    override fun available(): Int = current.size - pos

    private companion object { val END = ByteArray(0) }
}

/** Same as the Mac: first 4 bytes of SHA-256(macId), advertised in the Mac's Bluetooth name. */
object PairingTag {
    fun hexOf(macId: String): String =
        MessageDigest.getInstance("SHA-256").digest(macId.toByteArray()).take(4).joinToString("") { "%02x".format(it) }

    fun localName(macId: String) = "CB-" + hexOf(macId)
}
