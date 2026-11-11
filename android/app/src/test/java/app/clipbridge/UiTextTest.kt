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

    @Test fun tipForTheFullApp() {
        assertEquals("Copies on the phone go to your Mac by themselves. The “Send to Mac” quick toggle and " +
            "Share → Send to Mac work too. Same Wi-Fi, or Bluetooth when nearby · end-to-end encrypted.",
            UiText.tip(autoCopy = true, bluetooth = true))
    }

    @Test fun tipForLiteSaysToTapSendToMac() {
        assertEquals("After copying on the phone, tap the “Send to Mac” quick toggle or use Share → Send to Mac. " +
            "Same Wi-Fi, or Bluetooth when nearby · end-to-end encrypted.",
            UiText.tip(autoCopy = false, bluetooth = true))
    }

    @Test fun tipOnlyMentionsBluetoothWhereItWorks() {
        assertEquals(false, UiText.tip(autoCopy = false, bluetooth = false).contains("Bluetooth"))
        assertEquals(true, UiText.tip(autoCopy = false, bluetooth = false).endsWith("Same Wi-Fi · end-to-end encrypted."))
    }

    @Test fun quickToggleShowsTheConnection() {
        assertEquals("Ready", UiText.tileSubtitle(connected = true, bluetooth = false))
        assertEquals("Via Bluetooth", UiText.tileSubtitle(connected = true, bluetooth = true))
        assertEquals("Mac not connected", UiText.tileSubtitle(connected = false, bluetooth = false))
    }
}
