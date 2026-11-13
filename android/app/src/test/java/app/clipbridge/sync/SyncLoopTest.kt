package app.clipbridge.sync

import app.clipbridge.core.ClipCrypto
import app.clipbridge.core.PairingInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class SyncLoopTest {
    private val key = ClipCrypto.randomKey()
    private val mac = MultiFakeMac(key)
    private val pairing = PairingInfo("0123456789abcdef", key, "127.0.0.1", mac.port)
    private val statuses = CopyOnWriteArrayList<SyncLoop.Status>()
    private val clips = CopyOnWriteArrayList<String>()
    private val htmls = CopyOnWriteArrayList<String?>()
    @Volatile private var locateDelayMs = 0L

    private val loop = SyncLoop(
        pairing = { pairing },
        locate = { Thread.sleep(locateDelayMs); null },  // no NSD in tests → falls back to QR address
        device = "test-phone",
        callbacks = object : SyncLoop.Callbacks {
            override fun onStatus(status: SyncLoop.Status) { statuses.add(status) }
            override fun onClip(text: String) { clips.add(text) }
            override fun onClip(text: String, html: String?) { onClip(text); htmls.add(html) }
        },
        backoff = Backoff(longArrayOf(50, 50, 50)),
        pingIntervalMs = 200,
    )

    @After fun tearDown() { loop.stop(); mac.close() }

    private fun eventually(timeoutMs: Long = 3_000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (condition()) return true; Thread.sleep(20) }
        return condition()
    }

    @Test fun connectsAndDeliversClipsFromMac() {
        loop.start()
        assertTrue(eventually { statuses.lastOrNull() == SyncLoop.Status.CONNECTED })
        mac.sendClip("from mac")
        assertTrue(eventually { clips == listOf("from mac") })
    }

    @Test fun formattedClipsPassThroughBothWays() {
        loop.start()
        assertTrue(eventually { statuses.lastOrNull() == SyncLoop.Status.CONNECTED })
        mac.sendClip("bold", "<b>bold</b>")
        assertTrue(eventually { htmls.contains("<b>bold</b>") })
        assertTrue(loop.send("from phone", "<u>from phone</u>"))
        assertTrue(eventually { mac.received.any { it is app.clipbridge.core.Message.Clip && it.html == "<u>from phone</u>" } })
    }

    @Test fun sendGoesToMacButEchoOfReceivedTextDoesNot() {
        loop.start()
        assertTrue(eventually { statuses.lastOrNull() == SyncLoop.Status.CONNECTED })
        mac.sendClip("from mac")
        assertTrue(eventually { clips.isNotEmpty() })
        assertFalse("echo of received text", loop.send("from mac"))
        assertTrue(loop.send("from phone"))
        assertTrue(eventually { mac.received.any { it is app.clipbridge.core.Message.Clip && it.text == "from phone" } })
    }

    @Test fun userInitiatedSendResendsTextJustReceivedFromMac() {
        loop.start()
        assertTrue(eventually { statuses.lastOrNull() == SyncLoop.Status.CONNECTED })
        mac.sendClip("again please")
        assertTrue(eventually { clips.isNotEmpty() })
        assertFalse("automatic echo is still blocked", loop.send("again please"))
        assertTrue("an explicit Send to Mac goes through", loop.send("again please", userInitiated = true))
        assertTrue(eventually { mac.received.any { it is app.clipbridge.core.Message.Clip && it.text == "again please" } })
        assertFalse("still no empty sends", loop.send("", userInitiated = true))
    }

    @Test fun stopDuringDiscoveryNeverConnects() {
        locateDelayMs = 300
        loop.start()
        Thread.sleep(100)
        loop.stop()
        Thread.sleep(600)
        assertEquals("connections made after stop", 0, mac.accepts.get())
        assertEquals(SyncLoop.Status.STOPPED, statuses.last())
    }

    @Test fun stopThenImmediateStartEndsWithExactlyOneStableConnection() {
        loop.start()
        assertTrue(eventually { statuses.lastOrNull() == SyncLoop.Status.CONNECTED })
        loop.stop()
        loop.start()
        assertTrue(eventually { statuses.lastOrNull() == SyncLoop.Status.CONNECTED && mac.liveConnections == 1 })
        val acceptsNow = mac.accepts.get()
        Thread.sleep(1_000)
        assertEquals("connections keep flapping", acceptsNow, mac.accepts.get())
        assertEquals(1, mac.liveConnections)
    }

    @Test fun reconnectReplacesConnectionWithoutFlapping() {
        loop.start()
        assertTrue(eventually { statuses.lastOrNull() == SyncLoop.Status.CONNECTED })
        loop.reconnect()
        assertTrue(eventually { mac.accepts.get() == 2 && mac.liveConnections == 1 })
        Thread.sleep(1_000)
        assertEquals(2, mac.accepts.get())
    }

    @Test fun noClipsAfterStop() {
        loop.start()
        assertTrue(eventually { statuses.lastOrNull() == SyncLoop.Status.CONNECTED })
        loop.stop()
        runCatching { mac.sendClip("late") }
        Thread.sleep(300)
        assertTrue(clips.isEmpty())
        assertFalse(loop.send("after stop"))
    }

    @Test fun repeatedKeyRejectionReportsPairingInvalid() {
        mac.rejectKey = true
        loop.start()
        assertTrue(eventually { statuses.lastOrNull() == SyncLoop.Status.PAIRING_INVALID })
    }

    @Test fun notPairedReportsNotPaired() {
        val unpaired = SyncLoop({ null }, { null }, "p", object : SyncLoop.Callbacks {
            override fun onStatus(status: SyncLoop.Status) { statuses.add(status) }
            override fun onClip(text: String) {}
        })
        unpaired.start()
        assertTrue(eventually { statuses.lastOrNull() == SyncLoop.Status.NOT_PAIRED })
        unpaired.stop()
    }

    private fun rememberedLoop(dialed: MutableList<Pair<String, Int>>, wifiUp: Boolean, worked: MutableList<Pair<String, Int>>) = SyncLoop(
        pairing = { pairing },
        locate = { null },
        device = "test-phone",
        callbacks = object : SyncLoop.Callbacks {
            override fun onStatus(status: SyncLoop.Status) { statuses.add(status) }
            override fun onClip(text: String) {}
            override fun onWifiConnected(host: String, port: Int) { worked.add(host to port) }
        },
        backoff = Backoff(longArrayOf(50)),
        pingIntervalMs = 200,
        // Pretend the remembered address is the Mac: dial the fake Mac whatever address was asked for.
        wifiLink = { host, port -> dialed.add(host to port); TcpLink("127.0.0.1", if (wifiUp) mac.port else 1) },
        lastAddress = { "10.0.0.9" to 5000 },
    )

    @Test fun usesRememberedAddressWhenDiscoveryFindsNothing() {
        val dialed = CopyOnWriteArrayList<Pair<String, Int>>()
        val l = rememberedLoop(dialed, true, CopyOnWriteArrayList())
        try {
            l.start()
            assertTrue(eventually { dialed.isNotEmpty() })
            assertEquals("10.0.0.9" to 5000, dialed.first())
        } finally { l.stop() }
    }

    @Test fun reportsTheWifiAddressThatWorked() {
        val worked = CopyOnWriteArrayList<Pair<String, Int>>()
        val l = rememberedLoop(CopyOnWriteArrayList(), true, worked)
        try {
            l.start()
            assertTrue(eventually { statuses.lastOrNull() == SyncLoop.Status.CONNECTED })
            assertEquals(listOf("10.0.0.9" to 5000), worked)
        } finally { l.stop() }
    }
}
