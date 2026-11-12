package app.clipbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickToggleGuideTest {
    @Test fun android13AndLaterOnlyNeedTipsBecauseEachButtonHasItsOwnAdd() {
        assertTrue(QuickToggleGuide.oneTap(33))
        assertEquals("Tips", QuickToggleGuide.title(33))
        val steps = QuickToggleGuide.steps(sdk = 33)
        assertEquals(2, steps.size)
        assertTrue("explains moving them up", steps[0].contains("Torch") && steps[0].contains("Samsung"))
        assertTrue(steps[1].contains("Send to Mac") && steps[1].contains("ClipBridge"))
        assertFalse("no Quick Settings jargon", steps.any { it.contains("Quick Settings") })
    }

    @Test fun olderAndroidIsAddedByHand() {
        assertFalse(QuickToggleGuide.oneTap(32))
        assertEquals("How to add them", QuickToggleGuide.title(31))
        val steps = QuickToggleGuide.steps(sdk = 31)
        assertEquals(3, steps.size)
        assertTrue(steps[0].contains("Edit") && steps[0].contains("Send to Mac") && steps[0].contains("ClipBridge"))
    }
}
