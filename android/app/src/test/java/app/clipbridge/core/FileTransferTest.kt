package app.clipbridge.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import kotlin.random.Random

class FileTransferTest {
    @get:Rule val tmp = TemporaryFolder()

    private val bytes = Random(42).nextBytes(600_000)
    private fun sender(data: ByteArray = bytes, name: String = "report.pdf") =
        FileSender("f1", { ByteArrayInputStream(data) }, name, "application/pdf", data.size.toLong())
    private fun parts() = tmp.root.listFiles()!!.filter { it.name.endsWith(".clipbridge-part") }
    private fun expectFailure(block: () -> Unit) {
        try { block(); fail("expected failure") } catch (e: AssertionError) { throw e } catch (_: Exception) {}
    }

    @Test fun limitsMatchSpec() {
        assertEquals(26_214_400L, FileLimit.MAX_BYTES)
        assertEquals(196_608, FileLimit.CHUNK_BYTES)
    }

    @Test fun shaMatchesMac() {
        val s = FileSender("x", { ByteArrayInputStream(ByteArray(256) { it.toByte() }) }, "a", "b", 256)
        assertEquals("40aff2e9d2d8922e47afd4648e6967497158785fbd1da870e7110266bf944880", s.start().sha256)
    }

    @Test fun roundtripsInFourChunks() {
        val s = sender()
        val start = s.start()
        val chunks = s.chunks().toList()
        assertEquals(4, chunks.size)
        val r = FileReceiver(start, tmp.root)
        chunks.forEach { assertNull(r.accept(it)) }
        val file = r.accept(s.end())!!
        assertEquals("report.pdf", file.name)
        assertArrayEquals(bytes, file.readBytes())
        assertTrue(parts().isEmpty())
    }

    @Test fun existingNameGetsSuffix() {
        repeat(2) {
            val s = sender(); val r = FileReceiver(s.start(), tmp.root)
            s.chunks().forEach { r.accept(it) }; r.accept(s.end())
        }
        assertTrue(tmp.root.resolve("report (1).pdf").exists())
    }

    @Test fun outOfOrderChunkRejectedAndTempRemoved() {
        val s = sender(); val r = FileReceiver(s.start(), tmp.root)
        expectFailure { r.accept(s.chunks().toList()[1]) }
        assertTrue(parts().isEmpty())
    }

    @Test fun checksumMismatchRejectedAndTempRemoved() {
        val s = sender(); val r = FileReceiver(s.start(), tmp.root)
        s.chunks().forEachIndexed { i, c ->
            r.accept(if (i == 2) Message.FileChunk(c.id, c.seq, c.data.copyOf().also { it[0] = (it[0].toInt() xor 0xff).toByte() }) else c)
        }
        expectFailure { r.accept(s.end()) }
        assertTrue(parts().isEmpty())
    }

    @Test fun moreBytesThanDeclaredRejected() {
        val r = FileReceiver(Message.FileStart("s", "s.bin", "x", 10, "0".repeat(64)), tmp.root)
        expectFailure { r.accept(Message.FileChunk("s", 0, ByteArray(11))) }
    }

    @Test fun abortRemovesTemp() {
        val s = sender(); val r = FileReceiver(s.start(), tmp.root)
        r.accept(s.chunks().first())
        assertEquals(1, parts().size)
        r.abort()
        assertTrue(parts().isEmpty())
    }

    @Test fun hostileNameStaysInsideFolder() {
        val one = byteArrayOf(0)
        val s = FileSender("e", { ByteArrayInputStream(one) }, "../../evil.sh", "x", 1)
        val r = FileReceiver(s.start(), tmp.root)
        s.chunks().forEach { r.accept(it) }
        val f = r.accept(s.end())!!
        assertEquals(tmp.root.canonicalFile, f.canonicalFile.parentFile)
        assertFalse(f.name.startsWith("."))
    }

    @Test fun tooLargeRejected() {
        expectFailure { FileSender("b", { ByteArrayInputStream(ByteArray(0)) }, "b", "x", FileLimit.MAX_BYTES + 1) }
        expectFailure { FileReceiver(Message.FileStart("b", "b", "x", FileLimit.MAX_BYTES + 1, ""), tmp.root) }
    }
}
