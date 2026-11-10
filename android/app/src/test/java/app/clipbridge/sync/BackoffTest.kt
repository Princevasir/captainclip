package app.clipbridge.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class BackoffTest {
    @Test fun followsScheduleAndCapsAt30s() {
        val backoff = Backoff()
        assertEquals(listOf(1000L, 2000L, 5000L, 10000L, 30000L, 30000L, 30000L), List(7) { backoff.nextDelayMs() })
    }

    @Test fun resetStartsOver() {
        val backoff = Backoff()
        repeat(3) { backoff.nextDelayMs() }
        backoff.reset()
        assertEquals(1000L, backoff.nextDelayMs())
    }
}
