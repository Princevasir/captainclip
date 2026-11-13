package app.clipbridge.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionLogTest {
    private var now = 0L
    private fun log(keep: Int = 50) = ConnectionLog(clock = { now }, keep = keep)

    @Test fun threeDropsInAnHourPrompts() {
        val log = log()
        log.add("lost"); log.add("lost"); log.add("lost")
        assertTrue(log.keepsDropping())
    }

    @Test fun oldDropsDontCount() {
        val log = log()
        now = 0L; log.add("lost"); log.add("lost")
        now = 3_600_001L; log.add("lost")
        assertFalse(log.keepsDropping())
    }

    @Test fun pausesAreNotDrops() {
        val log = log()
        log.add("paused"); log.add("paused"); log.add("paused")
        assertFalse(log.keepsDropping())
    }

    @Test fun dismissHidesUntilNextDrop() {
        val log = log()
        log.add("lost"); log.add("lost"); log.add("lost")
        log.dismissPrompt()
        assertFalse(log.keepsDropping())
        log.add("lost")
        assertTrue(log.keepsDropping())
    }

    @Test fun keepsOnlyTheNewest() {
        val log = log(keep = 5)
        for (i in 1..7) { now = i.toLong(); log.add("connected-wifi") }
        val recent = log.recent(10)
        assertEquals(5, recent.size)
        assertEquals(ConnEvent(7L, "connected-wifi"), recent.last())
        assertEquals(3L, recent.first().at)
    }

    @Test fun recentReturnsNewestNInOrder() {
        val log = log()
        now = 1L; log.add("connected-wifi")
        now = 2L; log.add("lost")
        now = 3L; log.add("resumed")
        assertEquals(listOf(ConnEvent(2L, "lost"), ConnEvent(3L, "resumed")), log.recent(2))
    }
}
