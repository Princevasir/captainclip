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

    @Test fun tipForStandardAppSaysToTapSendToMac() {
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

    @Test fun agoReadsNaturally() {
        assertEquals("Just now", UiText.ago(30_000))
        assertEquals("5 min ago", UiText.ago(5 * 60_000L + 10_000))
        assertEquals("2 h ago", UiText.ago(2 * 3_600_000L + 60_000))
        assertEquals("Yesterday", UiText.ago(30 * 3_600_000L))
        assertEquals("3 days ago", UiText.ago(3 * 86_400_000L + 1))
    }

    @Test fun historyPreviewIsCompact() {
        val long = "line one\n\nline two " + "x".repeat(200)
        val text = UiText.historyPreview(app.clipbridge.history.HistoryEntry("1", 0, app.clipbridge.history.Direction.FROM_MAC, text = long))
        assertEquals(141, text.length)
        assertEquals(true, text.startsWith("line one line two"))
        assertEquals(true, text.endsWith("…"))
        val file = app.clipbridge.history.HistoryEntry("2", 0, app.clipbridge.history.Direction.TO_MAC, fileName = "Invoice.pdf", mime = "application/pdf", size = 430_080)
        assertEquals("📄 Invoice.pdf · 420 KB", UiText.historyPreview(file))
    }

    @Test fun historyGroupsByCalendarDay() {
        val zone = java.time.ZoneId.of("Asia/Kolkata")
        val now = java.time.ZonedDateTime.of(2026, 11, 12, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        fun at(day: Int, hour: Int) = java.time.ZonedDateTime.of(2026, 11, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("Today", UiText.dayGroup(at(12, 0), now, zone))
        assertEquals("Yesterday", UiText.dayGroup(at(11, 23), now, zone))
        assertEquals("Earlier", UiText.dayGroup(at(10, 23), now, zone))
    }

    @Test fun shortAgoForCompactRows() {
        assertEquals("now", UiText.agoShort(20_000))
        assertEquals("5 min", UiText.agoShort(5 * 60_000L))
        assertEquals("2 h", UiText.agoShort(2 * 3_600_000L))
        assertEquals("Yesterday", UiText.agoShort(30 * 3_600_000L))
        assertEquals("3 d", UiText.agoShort(3 * 86_400_000L))
    }

    @Test fun ownCopiedMessageOnlyBeforeAndroid13() {
        // Android 13+ shows its own "Copied" confirmation for every clipboard write; a second one is noise.
        assertEquals(true, UiText.showOwnCopiedMessage(sdk = 32))
        assertEquals(false, UiText.showOwnCopiedMessage(sdk = 33))
    }

    @Test fun widgetAndToggleSayHowYouAreConnected() {
        assertEquals("MacBook Air" to "Connected · Wi-Fi", UiText.connectionLine(paired = true, syncOn = true, connected = true, macName = "MacBook Air", bluetooth = false))
        assertEquals("MacBook Air" to "Connected · Bluetooth", UiText.connectionLine(paired = true, syncOn = true, connected = true, macName = "MacBook Air", bluetooth = true))
        assertEquals("Your Mac" to "Looking for your Mac…", UiText.connectionLine(paired = true, syncOn = true, connected = false, macName = null, bluetooth = false))
        assertEquals("ClipBridge" to "Paused", UiText.connectionLine(paired = true, syncOn = false, connected = false, macName = "MacBook Air", bluetooth = false))
        assertEquals("ClipBridge" to "Not paired", UiText.connectionLine(paired = false, syncOn = true, connected = false, macName = null, bluetooth = false))
    }
}
