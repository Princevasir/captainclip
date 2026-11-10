package app.clipbridge.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

object FileLimit {
    /** 25 MB per file. */
    const val MAX_BYTES = 26_214_400L
    /** Raw bytes per chunk (≈256 KiB once base64-encoded). Same as the Mac. */
    const val CHUNK_BYTES = 196_608
}

class FileTransferException(message: String) : IOException(message)

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

/**
 * Streams a file in chunks. [input] is opened twice: once to hash (for file-start), once to send.
 * Nothing is held in memory beyond one chunk.
 */
class FileSender(
    val id: String,
    private val input: () -> InputStream,
    val name: String,
    val mime: String,
    val size: Long,
) {
    init {
        if (size < 0 || size > FileLimit.MAX_BYTES) throw FileTransferException("too-large")
    }

    fun start(): Message.FileStart {
        val digest = MessageDigest.getInstance("SHA-256")
        input().use { stream ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return Message.FileStart(id, name, mime, size, digest.digest().hex())
    }

    fun chunks(): Sequence<Message.FileChunk> = sequence {
        input().use { stream ->
            var seq = 0L
            while (true) {
                val chunk = readUpTo(stream, FileLimit.CHUNK_BYTES)
                if (chunk.isEmpty()) break
                yield(Message.FileChunk(id, seq++, chunk))
            }
        }
    }

    fun end() = Message.FileEnd(id)

    /** Fills up to [n] bytes (fewer only at end of stream). InputStream.readNBytes needs API 33. */
    private fun readUpTo(stream: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var filled = 0
        while (filled < n) {
            val r = stream.read(buf, filled, n - filled)
            if (r < 0) break
            filled += r
        }
        return if (filled == n) buf else buf.copyOf(filled)
    }
}

/**
 * Writes chunks to `<name>.clipbridge-part` in [dir], verifies size + SHA-256 at file-end and renames to a
 * unique name. Any problem deletes the temp file and throws.
 */
class FileReceiver(start: Message.FileStart, private val dir: File) {
    val id = start.id
    val name = safeName(start.name)
    private val size = start.size
    private val sha256 = start.sha256.lowercase()
    private val temp = File(dir, "$name.clipbridge-part")
    private val digest = MessageDigest.getInstance("SHA-256")
    private var out: FileOutputStream?
    private var written = 0L
    private var nextSeq = 0L

    init {
        if (size < 0 || size > FileLimit.MAX_BYTES) throw FileTransferException("too-large")
        out = FileOutputStream(temp)
    }

    /** Returns the final file once file-end verifies; null for chunks. */
    fun accept(message: Message): File? = try {
        when (message) {
            is Message.FileChunk -> {
                if (message.id != id) throw FileTransferException("wrong transfer")
                if (message.seq != nextSeq) throw FileTransferException("out of order")
                if (written + message.data.size > size) throw FileTransferException("too much data")
                out!!.write(message.data)
                digest.update(message.data)
                written += message.data.size
                nextSeq++
                null
            }
            is Message.FileEnd -> {
                if (message.id != id) throw FileTransferException("wrong transfer")
                if (written != size) throw FileTransferException("size mismatch")
                if (digest.digest().hex() != sha256) throw FileTransferException("checksum mismatch")
                out!!.close()
                out = null
                val target = uniqueFile()
                if (!temp.renameTo(target)) throw FileTransferException("rename failed")
                target
            }
            else -> throw FileTransferException("unexpected message")
        }
    } catch (e: Exception) {
        abort()
        throw e
    }

    fun abort() {
        runCatching { out?.close() }
        out = null
        temp.delete()
    }

    private fun uniqueFile(): File {
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var candidate = File(dir, name)
        var n = 1
        while (candidate.exists()) { candidate = File(dir, "$base ($n)$ext"); n++ }
        return candidate
    }

    companion object {
        /** Keeps only the last path component, no leading dots, no control characters. */
        fun safeName(raw: String): String {
            val last = raw.replace('\\', '/').split('/').lastOrNull { it.isNotEmpty() } ?: ""
            val cleaned = last.filter { !it.isISOControl() && it != ':' }.trimStart('.')
            return cleaned.ifEmpty { "file" }.take(200)
        }
    }
}
