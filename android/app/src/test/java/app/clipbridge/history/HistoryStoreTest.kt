package app.clipbridge.history

import app.clipbridge.history.Direction.FROM_MAC
import app.clipbridge.history.Direction.TO_MAC
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Stands in for the Keystore cipher: reversible, and the output doesn't contain the plain text. */
private open class XorCipher : HistoryCipher {
    var encrypts = 0
    override fun encrypt(plain: ByteArray): ByteArray { encrypts++; return xor(plain) }
    override fun decrypt(box: ByteArray) = xor(box)
    private fun xor(b: ByteArray) = ByteArray(b.size) { (b[it].toInt() xor 0x5A).toByte() }
}

/** Keystore busy: decrypt fails for a reason other than a bad file. */
private class BusyCipher : XorCipher() {
    override fun decrypt(box: ByteArray): ByteArray = throw IllegalStateException("keystore busy")
}

/** Runs queued writes only when told to (a stand-in for the background writer thread). */
private class ManualExecutor : java.util.concurrent.Executor {
    val queue = ArrayDeque<Runnable>()
    override fun execute(command: Runnable) { queue.add(command) }
    fun runAll() { while (queue.isNotEmpty()) queue.removeFirst().run() }
}

class HistoryStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 1_000L
    private fun store(file: File = File(tmp.root, "history.bin"), cipher: HistoryCipher = XorCipher(), fromMac: Boolean = true) =
        HistoryStore(file, cipher, clock = { now }, writer = { it.run() }, includeFromMac = fromMac)

    @Test fun macTextIsNotKeptByDefault() {
        val s = HistoryStore(File(tmp.root, "history.bin"), XorCipher(), clock = { now }, writer = { it.run() })
        s.recordText("copied on the Mac, never used here", null, FROM_MAC)
        assertTrue(s.entries().isEmpty())
    }

    @Test fun macTextKeptWhenNotPutOnClipboard() {
        val s = store(fromMac = false)
        s.recordText("waiting for you", null, FROM_MAC, held = true)
        assertEquals(listOf("waiting for you"), s.entries().map { it.text })
        assertEquals(listOf("waiting for you"), store(fromMac = false).entries().map { it.text })
    }

    @Test fun heldMacTextSurvivesIncludeFromMacPurge() {
        val s = store()
        s.recordText("plain mac clip", null, FROM_MAC)
        s.recordText("held mac clip", null, FROM_MAC, held = true)
        s.setIncludeFromMac(false)
        assertEquals(listOf("held mac clip"), s.entries().map { it.text })
        assertEquals("and after a restart", listOf("held mac clip"), store(fromMac = false).entries().map { it.text })
    }

    @Test fun codeFromMacNeverRecordedEvenWhenHeld() {
        val s = store(fromMac = false)
        s.recordText("123456", null, FROM_MAC, sensitive = true, held = true)
        assertTrue(s.entries().isEmpty())
    }

    @Test fun withMacClipsOffPhoneSendsAndOpenedMacFilesAreStillKept() {
        val s = store(fromMac = false)
        s.recordText("sent from phone", null, TO_MAC)
        s.recordText("mac password-ish", null, FROM_MAC)
        s.recordFile("offer-1", "Invoice.pdf", "application/pdf", 10, FROM_MAC)
        assertEquals(listOf("Invoice.pdf", null), s.entries().map { it.fileName })
        assertEquals("sent from phone", s.entries().last().text)
        assertEquals(2, store(fromMac = false).entries().size)
    }

    @Test fun turningMacClipsOffDeletesStoredMacText() {
        val s = store()
        s.recordText("mine", null, TO_MAC)
        s.recordText("from mac", null, FROM_MAC)
        s.recordFile("offer-1", "a.pdf", "application/pdf", 1, FROM_MAC)
        s.setIncludeFromMac(false)
        assertEquals(listOf("a.pdf", "mine"), s.entries().map { it.fileName ?: it.text })
        assertEquals("gone from disk too", 2, store(fromMac = false).entries().size)
        assertFalse(s.includeFromMac)
    }

    @Test fun startingWithMacClipsOffDropsMacTextSavedEarlier() {
        store().apply { recordText("old mac clip", null, FROM_MAC); recordText("mine", null, TO_MAC) }
        assertEquals(listOf("mine"), store(fromMac = false).entries().map { it.text })
        assertEquals("rewritten on disk", listOf("mine"), store().entries().map { it.text })
    }

    @Test fun turningMacClipsBackOnKeepsThemAgain() {
        val s = store(fromMac = false)
        s.setIncludeFromMac(true)
        s.recordText("from mac", null, FROM_MAC)
        assertEquals("from mac", s.entries().single().text)
    }

    @Test fun codecRoundTripsAllFields() {
        val entries = listOf(
            HistoryEntry("1", 5, TO_MAC, text = "hi \"there\"\nline", html = "<b>hi</b>"),
            HistoryEntry("2", 4, FROM_MAC, fileName = "a.pdf", mime = "application/pdf", size = 42),
        )
        assertEquals(entries, HistoryCodec.decode(HistoryCodec.encode(entries)))
    }

    @Test fun persistsAcrossInstances() {
        store().recordText("hello", "<i>hello</i>", FROM_MAC)
        val again = store().entries()
        assertEquals("hello", again.single().text)
        assertEquals("<i>hello</i>", again.single().html)
    }

    @Test fun fileOnDiskIsNotPlainText() {
        store().recordText("secret-address-42", null, TO_MAC)
        val bytes = File(tmp.root, "history.bin").readBytes()
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("secret-address-42"))
    }

    @Test fun corruptFileGivesEmptyHistoryAndRecordsAgain() {
        File(tmp.root, "history.bin").writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        val s = store()
        assertTrue(s.entries().isEmpty())
        s.recordText("fresh", null, FROM_MAC)
        assertEquals("fresh", store().entries().single().text)
    }

    @Test fun disabledRecordsNothingAndDeletesFile() {
        val s = store()
        s.recordText("before", null, FROM_MAC)
        s.setEnabled(false)
        assertFalse(File(tmp.root, "history.bin").exists())
        s.recordText("while off", null, FROM_MAC)
        assertTrue(s.entries().isEmpty())
    }

    @Test fun limitsApplyOnRead() {
        val s = store()
        s.recordText("aging", null, FROM_MAC)
        now += 8 * HistoryLimits.DAY_MS
        assertTrue(s.entries().isEmpty())
    }

    @Test fun repeatedFetchOfSameOfferRecordsOnce() {
        val s = store()
        repeat(3) { s.recordFile("offer-9", "Invoice.pdf", "application/pdf", 420_000, FROM_MAC) }
        assertEquals(1, s.entries().size)
    }

    @Test fun writesHappenOnTheWriterNotTheCaller() {
        val cipher = XorCipher()
        val writer = ManualExecutor()
        val s = HistoryStore(File(tmp.root, "history.bin"), cipher, clock = { now }, writer = writer)
        s.recordText("one", null, TO_MAC)
        s.recordText("two", null, TO_MAC)
        assertEquals("caller never encrypts or writes", 0, cipher.encrypts)
        assertEquals(listOf("two", "one"), s.entries().map { it.text })  // memory is up to date at once
        writer.runAll()
        assertEquals("a burst of changes is written once", 1, cipher.encrypts)
        assertEquals(listOf("two", "one"), store().entries().map { it.text })
    }

    @Test fun clearWinsOverAWriteStillQueued() {
        val writer = ManualExecutor()
        val s = HistoryStore(File(tmp.root, "history.bin"), XorCipher(), clock = { now }, writer = writer)
        s.recordText("soon gone", null, FROM_MAC)
        s.clear()
        writer.runAll()
        assertFalse(File(tmp.root, "history.bin").exists())
    }

    @Test fun transientLoadErrorKeepsTheFile() {
        store().recordText("keep me", null, TO_MAC)
        val busy = store(cipher = BusyCipher())
        assertTrue(busy.entries().isEmpty())
        busy.recordText("while busy", null, FROM_MAC)
        assertEquals("old history survives for the next start", listOf("keep me"), store().entries().map { it.text })
    }
}
