package app.clipbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickToggleGuideTest {
    @Test fun android13AndLaterAddInOneTap() {
        val steps = QuickToggleGuide.steps(sdk = 33)
        assertEquals(3, steps.size)
        assertTrue(steps[0].contains("Add it now"))
        assertTrue("Samsung puts it at the end", steps[1].contains("Samsung"))
        assertTrue(steps.last().contains("Send to Mac"))
        assertTrue(QuickToggleGuide.oneTap(33))
    }

    @Test fun olderAndroidIsAddedByHand() {
        val steps = QuickToggleGuide.steps(sdk = 31)
        assertFalse(steps[0].contains("Add it now"))
        assertTrue(steps[0].contains("Edit"))
        assertFalse(QuickToggleGuide.oneTap(32))
    }
}
