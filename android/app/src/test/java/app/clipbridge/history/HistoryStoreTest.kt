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
private class XorCipher : HistoryCipher {
    override fun encrypt(plain: ByteArray) = ByteArray(plain.size) { (plain[it].toInt() xor 0x5A).toByte() }
    override fun decrypt(box: ByteArray) = encrypt(box)
}

class HistoryStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 1_000L
    private fun store(file: File = File(tmp.root, "history.bin")) = HistoryStore(file, XorCipher(), clock = { now })

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
}
