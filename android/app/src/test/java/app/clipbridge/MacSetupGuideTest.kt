package app.clipbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MacSetupGuideTest {
    @Test fun linksToTheLatestMacRelease() {
        assertEquals("https://github.com/Princevasir/clipbridge/releases/latest", MacSetupGuide.DOWNLOAD_URL)
    }

    @Test fun walksFromDownloadToPairing() {
        val steps = MacSetupGuide.steps
        assertEquals(5, steps.size)
        assertTrue(steps.first().contains("ClipBridge-mac.zip"))
        assertTrue(steps.any { it.contains("Applications") })
        assertTrue(steps.any { it.contains("Open Anyway") })  // first launch outside the App Store
        assertTrue(steps.last().contains("Pair phone"))
    }

    @Test fun shareTextCarriesTheLink() {
        assertTrue(MacSetupGuide.shareText.contains(MacSetupGuide.DOWNLOAD_URL))
    }

    @Test fun everyStepHasAPicture() {
        assertEquals(MacSetupGuide.steps.size, MacSetupGuide.pictures.size)
        assertEquals(MacSetupGuide.pictures.size, MacSetupGuide.pictures.toSet().size)  // all different
    }
}
