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

    @Test fun bigFilesAndImagesWaitForWifi() {
        val q = queue()
        val small = out("s", 3); val big = out("b", 10); val img = out("i.png", 10)
        q.putClip(QueuedClip.Image(img, "k"))
        q.addFiles(listOf(big, small))
        val overBluetooth = q.drain(maxFileBytes = 5)
        assertNull(overBluetooth.clip)
        assertEquals(listOf(small), overBluetooth.files)
        assertEquals(2, q.count)
        val overWifi = q.drain()
        assertEquals(QueuedClip.Image(img, "k"), overWifi.clip)
        assertEquals(listOf(big), overWifi.files)
        assertTrue(discarded.isEmpty())
    }

    @Test fun failedItemsGoBackInOrderWithTheirOriginalTime() {
        val q = queue()
        val f1 = out("1"); val f2 = out("2"); val f3 = out("3")
        q.putClip(QueuedClip.Text("t", null))
        q.addFiles(listOf(f1, f2))
        now = 1_000
        val p = q.drain()
        q.addFiles(listOf(f3))  // queued after the drain
        q.requeueClip(p)
        q.requeueFile(p, f2)
        q.requeueFile(p, f1)
        val again = q.drain()
        assertEquals(QueuedClip.Text("t", null), again.clip)
        assertEquals(listOf(f1, f2, f3), again.files)

        q.requeueClip(again); q.requeueFile(again, f1)
        now = 3_600_001  // an hour after f1 and the clip were first queued
        assertTrue(q.isEmpty)
        assertEquals(listOf(f1.file), discarded)
    }

    @Test fun aDirectSendSupersedesTheQueuedClip() {
        val q = queue()
        val img = out("i.png")
        q.putClip(QueuedClip.Image(img, "k"))
        q.supersedeClip()
        assertTrue(q.isEmpty)
        assertEquals(listOf(img.file), discarded)

        q.putClip(QueuedClip.Text("old", null))
        val p = q.drain()
        assertTrue(q.isLatestClip(p))
        q.supersedeClip()  // newer text went straight to the Mac while "old" was in flight
        assertTrue(!q.isLatestClip(p))
        q.requeueClip(p)
        assertTrue(q.isEmpty)
    }

    @Test fun aFailedClipNeverReplacesANewerOne() {
        val q = queue()
        val img = out("i.png")
        q.putClip(QueuedClip.Image(img, "k"))
        val p = q.drain()
        q.putClip(QueuedClip.Text("newer", null))
        q.requeueClip(p)
        assertEquals(QueuedClip.Text("newer", null), q.drain().clip)
        assertEquals(listOf(img.file), discarded)
    }

    @Test fun requeueAfterClearIsDiscarded() {
        val q = queue()
        val img = out("i.png"); val f = out("f")
        q.putClip(QueuedClip.Image(img, "k"))
        q.addFiles(listOf(f))
        val p = q.drain()
        q.clear()  // re-paired while the drain was in flight
        assertEquals(false, q.requeueClip(p))
        assertEquals(false, q.requeueFile(p, f))
        assertTrue(q.isEmpty)
        assertEquals(setOf(img.file, f.file), discarded.toSet())
    }

    @Test fun requeueReportsWhetherTheItemWasKept() {
        val q = queue()
        val f = out("f")
        q.putClip(QueuedClip.Text("t", null))
        q.addFiles(listOf(f))
        val p = q.drain()
        assertEquals(true, q.requeueClip(p))
        assertEquals(true, q.requeueFile(p, f))
        assertEquals(2, q.count)
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
