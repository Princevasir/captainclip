package app.clipbridge.core

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

/** Frame = 4-byte big-endian length N (1..MAX_PAYLOAD) then N bytes. */
object Frame {
    const val MAX_PAYLOAD = 1_048_576
    private const val CRYPTO_OVERHEAD = 12 + 16

    fun encode(payload: ByteArray): ByteArray {
        require(payload.isNotEmpty() && payload.size <= MAX_PAYLOAD) { "bad payload size ${payload.size}" }
        return ByteBuffer.allocate(4 + payload.size).putInt(payload.size).put(payload).array()
    }

    /** True if a clip carrying [text] fits in one encrypted frame. */
    fun fits(text: String): Boolean {
        val json = Message.Clip("00000000-0000-0000-0000-000000000000", text, Long.MAX_VALUE).toJson()
        return json.toByteArray().size + CRYPTO_OVERHEAD <= MAX_PAYLOAD
    }
}

class FrameReader(input: InputStream) {
    private val data = DataInputStream(input)

    /** Blocks for the next payload. Throws IOException on bad length or end of stream. */
    fun next(): ByteArray {
        val length = data.readInt()
        if (length <= 0 || length > Frame.MAX_PAYLOAD) throw IOException("bad frame length $length")
        return ByteArray(length).also { data.readFully(it) }
    }
}
