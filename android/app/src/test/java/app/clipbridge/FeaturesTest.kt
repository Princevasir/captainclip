package app.clipbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeaturesTest {
    private val standard = Features.list(pro = false)

    @Test fun leadsWithEffortlessMacToPhoneCopy() {
        val first = standard.first()
        assertEquals("Copy on your Mac, paste here", first.title)
        assertTrue(first.body.contains("nothing to tap"))
    }

    @Test fun coversEveryStoreFeatureInStoreOrder() {
        assertEquals(
            listOf("Copy on your Mac, paste here", "Send to Mac, next to Copy", "One tap, wherever you are",
                "Screenshots and pictures", "Files, both ways", "Clipboard history", "Wi-Fi or Bluetooth",
                "Private by design"),
            standard.map { it.title },
        )
    }

    @Test fun namesEveryWayToSend() {
        val text = standard.joinToString(" ") { it.body }
        listOf("select text", "Share", "notification panel", "widget", "long-press").forEach {
            assertTrue("mentions $it", text.contains(it, ignoreCase = true))
        }
    }

    @Test fun historyClaimMatchesThePrivacyDefault() {
        val history = standard.single { it.title == "Clipboard history" }.body
        assertTrue(history.contains("sent from this phone"))
        assertFalse(history.contains("from your Mac", ignoreCase = true))
    }

    @Test fun proAddsAutomaticCopyAndStandardDoesNot() {
        assertFalse(standard.any { it.title.startsWith("Automatic copy") })
        assertEquals("Automatic copy", Features.list(pro = true)[1].title)
        assertEquals(standard.size + 1, Features.list(pro = true).size)
    }

    @Test fun everyFeatureHasItsOwnIcon() {
        val icons = Features.list(pro = true).map { it.icon }
        assertEquals(icons.size, icons.toSet().size)
    }

    @Test fun showsOnceAfterTheFirstPairing() {
        assertTrue(Features.showAfterPairing(seen = false))
        assertFalse(Features.showAfterPairing(seen = true))
    }
}
