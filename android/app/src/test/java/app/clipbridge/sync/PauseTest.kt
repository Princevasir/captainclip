package app.clipbridge.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PauseTest {
    @Test fun fifteenMinutes() = assertEquals(900_000L, Pause.until(PauseChoice.FIFTEEN_MIN, 0))
    @Test fun oneHour() = assertEquals(3_600_000L, Pause.until(PauseChoice.ONE_HOUR, 0))
    @Test fun untilResumedHasNoDeadline() = assertNull(Pause.until(PauseChoice.UNTIL_RESUMED, 0))
    @Test fun pastDeadlineIsNotPaused() {
        assertFalse(Pause.isTimedPause(1_000, now = 2_000))
        assertFalse(Pause.isTimedPause(0, now = 2_000))
    }
    @Test fun futureDeadlineIsPaused() = assertTrue(Pause.isTimedPause(5_000, now = 2_000))
}
