package app.clipbridge.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SendQueueTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 0L
    private val discarded = mutableListOf<File>()
    private fun queue(maxFiles: Int = 20, maxBytes: Long = 200L * 1024 * 1024) =
        SendQueue(clock = { now }, maxFiles = maxFiles, maxBytes = maxBytes, discard = { discarded.add(it) })

    private fun out(name: String, size: Int = 1) =
        OutgoingFile(tmp.newFile(name).also { it.writeBytes(ByteArray(size)) }, name, "application/octet-stream")

    @Test fun newerClipReplacesOlderAndDeletesItsImage() {
        val q = queue()
        val a = out("a.png")
        q.putClip(QueuedClip.Image(a, "k"))
        q.putClip(QueuedClip.Text("b", null))
        assertEquals(QueuedClip.Text("b", null), q.drain().clip)
        assertEquals(listOf(a.file), discarded)
    }

    @Test fun filesKeepTheirOrder() {
        val q = queue()
        val f1 = out("1"); val f2 = out("2")
        assertEquals(2, q.addFiles(listOf(f1, f2)))
        assertEquals(listOf(f1, f2), q.drain().files)
        assertTrue(q.isEmpty)
    }

    @Test fun filesBeyondLimitsAreRejectedAndDeleted() {
        val q = queue()
        val many = (1..21).map { out("f$it") }
        assertEquals(20, q.addFiles(many))
        assertEquals(listOf(many[20].file), discarded)

        discarded.clear()
        val q2 = queue(maxBytes = 100)
        val big1 = out("b1", 60); val big2 = out("b2", 60)
        assertEquals(1, q2.addFiles(listOf(big1, big2)))
        assertEquals(listOf(big2.file), discarded)
    }

    @Test fun expiredItemsAreDroppedAndDeleted() {
        val q = queue()
        val img = out("i.png"); val f = out("f")
        q.putClip(QueuedClip.Image(img, "k"))
        q.addFiles(listOf(f))
        now = 3_600_001
        val p = q.drain()
        assertNull(p.clip)
        assertTrue(p.files.isEmpty())
        assertEquals(setOf(img.file, f.file), discarded.toSet())
    }

    @Test fun countCountsClipAndFiles() {
        val q = queue()
        q.putClip(QueuedClip.Text("t", null))
        q.addFiles(listOf(out("1"), out("2")))
        assertEquals(3, q.count)
    }

    @Test fun clearDiscardsEverythingAndDeletesFiles() {
        val q = queue()
        val img = out("i.png"); val f = out("f")
        q.putClip(QueuedClip.Image(img, "k"))
        q.addFiles(listOf(f))
        q.clear()
        assertTrue(q.isEmpty)
        assertEquals(setOf(img.file, f.file), discarded.toSet())
    }
}
