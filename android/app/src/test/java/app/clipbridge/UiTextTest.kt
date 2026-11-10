package app.clipbridge

import org.junit.Assert.assertEquals
import org.junit.Test

class UiTextTest {
    @Test fun fileSizes() {
        assertEquals("609 B", UiText.size(609))
        assertEquals("1.2 KB", UiText.size(1_234))
        assertEquals("184 KB", UiText.size(188_190))
        assertEquals("2.4 MB", UiText.size(2_500_000))
        assertEquals("25 MB", UiText.size(26_214_400))
    }

    @Test fun timeLeftOnAnOffer() {
        assertEquals("Available for 10 more minutes", UiText.timeLeft(0))
        assertEquals("Available for 9 more minutes", UiText.timeLeft(30_000))
        assertEquals("Available for 1 more minute", UiText.timeLeft(9 * 60_000 + 10_000))
        assertEquals("Expiring now", UiText.timeLeft(10 * 60_000))
    }
}
