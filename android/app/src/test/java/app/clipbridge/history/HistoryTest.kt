package app.clipbridge.history

import app.clipbridge.history.Direction.FROM_MAC
import app.clipbridge.history.Direction.TO_MAC
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTest {
    private fun History.texts() = entries.map { it.text }

    @Test fun newestFirst() {
        val h = History()
        h.addText("a", null, FROM_MAC, now = 1); h.addText("b", null, TO_MAC, now = 2)
        assertEquals(listOf("b", "a"), h.texts())
    }

    @Test fun equalTextMovesToTopWithNewTimeAndDirection() {
        val h = History()
        h.addText("a", null, FROM_MAC, now = 1); h.addText("b", null, FROM_MAC, now = 2)
        h.addText("b", null, TO_MAC, now = 3)
        assertEquals(listOf("b", "a"), h.texts())
        assertEquals(3L, h.entries[0].at)
        assertEquals(TO_MAC, h.entries[0].direction)
    }

    @Test fun onlyTheNewestEntryIsCompared() {
        val h = History()
        h.addText("a", null, FROM_MAC, now = 1); h.addText("b", null, FROM_MAC, now = 2); h.addText("a", null, FROM_MAC, now = 3)
        assertEquals(listOf("a", "b", "a"), h.texts())
    }

    @Test fun sensitiveAndBlankAreNeverStored() {
        val h = History()
        assertFalse(h.addText("hunter2", null, TO_MAC, now = 1, sensitive = true))
        assertFalse(h.addText("   ", null, TO_MAC, now = 2))
        assertTrue(h.entries.isEmpty())
    }

    @Test fun htmlOver64KbIsDroppedButTextKept() {
        val h = History()
        h.addText("big", "<b>" + "x".repeat(HistoryLimits.MAX_HTML_BYTES) + "</b>", FROM_MAC, now = 1)
        h.addText("small", "<b>small</b>", FROM_MAC, now = 2)
        assertNull(h.entries.first { it.text == "big" }.html)
        assertEquals("<b>small</b>", h.entries.first { it.text == "small" }.html)
    }

    @Test fun fileIdIsRecordedOnce() {
        val h = History()
        assertTrue(h.addFile("offer-1", "Invoice.pdf", "application/pdf", 420_000, FROM_MAC, now = 1))
        assertFalse(h.addFile("offer-1", "Invoice.pdf", "application/pdf", 420_000, FROM_MAC, now = 2))
        assertEquals(1, h.entries.size)
        assertTrue(h.entries[0].isFile)
    }

    @Test fun pruneByCount() {
        val h = History()
        (1..60).forEach { h.addText("t$it", null, FROM_MAC, now = it.toLong()) }
        h.prune(HistoryLimits(maxItems = 50), now = 60)
        assertEquals(50, h.entries.size)
        assertEquals("t60", h.entries.first().text)
        assertEquals("t11", h.entries.last().text)
    }

    @Test fun pruneByAge() {
        val h = History()
        val day = HistoryLimits.DAY_MS
        h.addText("old", null, FROM_MAC, now = 0); h.addText("new", null, FROM_MAC, now = 6 * day)
        h.prune(HistoryLimits(), now = 7 * day + 1)
        assertEquals(listOf("new"), h.texts())
    }

    @Test fun searchIsCaseInsensitiveOverTextAndFileNames() {
        val h = History()
        h.addText("Flat 4B, Rose Court", null, TO_MAC, now = 1)
        h.addFile("f", "rose-garden.pdf", "application/pdf", 1, FROM_MAC, now = 2)
        h.addText("unrelated", null, TO_MAC, now = 3)
        assertEquals(2, h.search("ROSE").size)
        assertEquals(3, h.search("").size)
    }

    @Test fun deleteAndClear() {
        val h = History()
        h.addText("a", null, FROM_MAC, now = 1); h.addText("b", null, FROM_MAC, now = 2)
        h.delete(h.entries[0].id)
        assertEquals(listOf("a"), h.texts())
        h.clear()
        assertTrue(h.entries.isEmpty())
    }
}
