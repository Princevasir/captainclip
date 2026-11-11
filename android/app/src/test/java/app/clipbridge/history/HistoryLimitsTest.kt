package app.clipbridge.history

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryLimitsTest {
    @Test fun fromSettings() {
        val l = HistoryLimits.from(items = 25, days = 1)
        assertEquals(25, l.maxItems)
        assertEquals(HistoryLimits.DAY_MS, l.maxAgeMs)
    }

    @Test fun unknownValuesFallBackToDefaults() {
        val l = HistoryLimits.from(items = 7, days = 3)
        assertEquals(50, l.maxItems)
        assertEquals(7 * HistoryLimits.DAY_MS, l.maxAgeMs)
    }
}
