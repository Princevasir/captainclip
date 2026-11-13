package app.clipbridge.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionCheckTest {
    private val good = CheckFacts(
        connected = false, path = null, onWifi = true, macVisible = true, macReachable = true,
        bluetoothOn = true, bluetoothAllowed = true, pairingInvalid = false, backgroundOk = true, paused = false,
    )

    private fun details(f: CheckFacts) = ConnectionCheck.lines(f).map { it.detail }

    @Test fun connectedIsAllGood() {
        val f = good.copy(connected = true, path = SyncLoop.Path.WIFI)
        assertEquals("Connected to your Mac over Wi-Fi", ConnectionCheck.headline(f))
        assertTrue(ConnectionCheck.lines(f).all { it.severity == Severity.OK })
    }

    @Test fun visibleButBlocked() {
        val f = good.copy(macReachable = false)
        assertEquals("Your Wi-Fi is blocking devices from talking to each other", ConnectionCheck.headline(f))
        assertTrue(details(f).any { "guest, hotel or office Wi-Fi" in it && "Bluetooth" in it })
    }

    @Test fun notOnWifi() {
        val f = good.copy(onWifi = false, macVisible = false, macReachable = false)
        assertEquals("This phone isn't on Wi-Fi", ConnectionCheck.headline(f))
        assertTrue(details(f).any { "Bluetooth" in it })
    }

    @Test fun macNotVisible() {
        val f = good.copy(macVisible = false, macReachable = false)
        assertEquals("Can't find your Mac on this Wi-Fi", ConnectionCheck.headline(f))
        assertTrue(details(f).any { "Is CaptainClip open on your Mac, and is it on the same Wi-Fi?" in it })
    }

    @Test fun rememberedAddressAnswers() {
        val f = good.copy(macVisible = false, macReachable = true)
        assertEquals("Your Mac answered at its last address — CaptainClip will reconnect", ConnectionCheck.headline(f))
        val mac = ConnectionCheck.lines(f).first { it.title == "Mac on this Wi-Fi" }
        assertEquals(Severity.WARN, mac.severity)
        assertEquals("Your Wi-Fi hides devices from each other, but your Mac's last address works.", mac.detail)
    }

    @Test fun connectedOverWifiMacLineIsOk() {
        val f = good.copy(connected = true, path = SyncLoop.Path.WIFI, macVisible = false, macReachable = true)
        val mac = ConnectionCheck.lines(f).first { it.title == "Mac on this Wi-Fi" }
        assertEquals(Severity.OK, mac.severity)
        assertEquals("All good — connected to your Mac over Wi-Fi.", mac.detail)
    }

    @Test fun pairingInvalidWins() {
        val f = good.copy(pairingInvalid = true, macVisible = false, onWifi = false)
        assertEquals("Your Mac was paired again — scan its new QR code", ConnectionCheck.headline(f))
    }

    @Test fun pausedSaysPaused() {
        val f = good.copy(paused = true, pairingInvalid = true)
        assertEquals("Syncing is paused", ConnectionCheck.headline(f))
    }

    @Test fun bluetoothOffIsAWarning() {
        val line = ConnectionCheck.lines(good.copy(bluetoothOn = false)).first { it.title == "Bluetooth" }
        assertEquals(Severity.WARN, line.severity)
    }

    @Test fun fallbackHeadline() {
        assertEquals("Still looking for your Mac", ConnectionCheck.headline(good))
    }

    @Test fun reportKeepsLast20Events() {
        val events = (1..25).map { ConnEvent(it.toLong(), if (it % 2 == 0) "lost" else "connected-wifi") }
        val text = ConnectionReport.text("0.6.1", "Android 14", ConnectionCheck.lines(good), events, 100L) { "t$it" }
        val timeLines = text.lines().filter { it.startsWith("t") && it.contains("  ") }
        assertEquals((6..25).map { "t$it" }, timeLines.map { it.substringBefore("  ") })
        assertFalse("t5  " in text)
    }

    @Test fun reportNeverContainsAddresses() {
        val f = good.copy(macReachable = false)
        val events = listOf(ConnEvent(1, "connected-wifi"), ConnEvent(2, "lost"))
        val text = ConnectionReport.text("CaptainClip 0.6.1", "Android 14", ConnectionCheck.lines(f), events, 10L) { "12:0$it" }
        assertFalse(Regex("""\b\d{1,3}(\.\d{1,3}){3}\b""").containsMatchIn(text))
        assertTrue("CaptainClip 0.6.1" in text)
        assertTrue("Android 14" in text)
        assertTrue(text.startsWith("CaptainClip connection report"))
    }
}
