package app.clipbridge.sync

import app.clipbridge.core.ClipCrypto
import app.clipbridge.core.PairingInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private class DeadLink : Link {
    override val isBluetooth = false
    override fun open(readTimeoutMs: Int): Pair<InputStream, OutputStream> = throw IOException("no route to Mac")
    override fun close() {}
}

class PathSelectionTest {
    private val key = ClipCrypto.randomKey()
    private val mac = MultiFakeMac(key)
    private val statuses = CopyOnWriteArrayList<SyncLoop.Status>()
    private val wifiUp = AtomicBoolean(false)
    private val bleDials = AtomicInteger()
    private var loop: SyncLoop? = null

    @After fun tearDown() { loop?.stop(); mac.close() }

    private fun start(bluetooth: Boolean): SyncLoop = SyncLoop(
        pairing = { PairingInfo("0123456789abcdef", key, "127.0.0.1", mac.port) },
        locate = { null }, device = "phone",
        callbacks = object : SyncLoop.Callbacks {
            override fun onStatus(status: SyncLoop.Status) { statuses.add(status) }
            override fun onClip(text: String) {}
        },
        backoff = Backoff(longArrayOf(50)),
        pingIntervalMs = 500,
        wifiLink = { host, port -> if (wifiUp.get()) TcpLink(host, port) else DeadLink() },
        bluetoothLink = if (bluetooth) { _ -> bleDials.incrementAndGet(); PacketBridgeLink(mac.port) } else null,
        wifiProbe = { wifiUp.get() },
        wifiProbeIntervalMs = 300,
    ).also { loop = it; it.start() }

    private fun eventually(ms: Long = 5_000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (condition()) return true; Thread.sleep(20) }
        return condition()
    }

    @Test fun wifiWorkingNeverTouchesBluetooth() {
        wifiUp.set(true)
        val l = start(bluetooth = true)
        assertTrue(eventually { statuses.lastOrNull() == SyncLoop.Status.CONNECTED })
        assertEquals(SyncLoop.Path.WIFI, l.path)
        Thread.sleep(400)
        assertEquals(0, bleDials.get())
    }

    @Test fun twoWifiFailuresFallBackToBluetooth() {
        val l = start(bluetooth = true)
        assertTrue(eventually { statuses.lastOrNull() == SyncLoop.Status.CONNECTED })
        assertEquals(SyncLoop.Path.BLUETOOTH, l.path)
    }

    @Test fun withoutBluetoothItKeepsRetryingWifi() {
        val l = start(bluetooth = false)
        Thread.sleep(800)
        assertTrue(statuses.none { it == SyncLoop.Status.CONNECTED })
        assertEquals(null, l.path)
        wifiUp.set(true)
        assertTrue(eventually { statuses.lastOrNull() == SyncLoop.Status.CONNECTED })
    }

    @Test fun movesBackToWifiWhenItReturns() {
        val l = start(bluetooth = true)
        assertTrue(eventually { l.path == SyncLoop.Path.BLUETOOTH })
        wifiUp.set(true)
        assertTrue("should switch back to Wi-Fi", eventually { l.path == SyncLoop.Path.WIFI && statuses.lastOrNull() == SyncLoop.Status.CONNECTED })
    }
}
