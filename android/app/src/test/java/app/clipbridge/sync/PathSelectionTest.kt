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

    private fun start(
        bluetooth: Boolean,
        dialer: ((PairingInfo) -> Link?)? = null,
        bluetoothRetryDelaysMs: LongArray = longArrayOf(0),
    ): SyncLoop = SyncLoop(
        pairing = { PairingInfo("0123456789abcdef", key, "127.0.0.1", mac.port) },
        locate = { null }, device = "phone",
        callbacks = object : SyncLoop.Callbacks {
            override fun onStatus(status: SyncLoop.Status) { statuses.add(status) }
            override fun onClip(text: String) {}
        },
        backoff = Backoff(longArrayOf(50)),
        pingIntervalMs = 500,
        wifiLink = { host, port -> if (wifiUp.get()) TcpLink(host, port) else DeadLink() },
        bluetoothLink = if (!bluetooth) null else dialer?.let { d -> { info: PairingInfo -> bleDials.incrementAndGet(); d(info) } }
            ?: { _ -> bleDials.incrementAndGet(); PacketBridgeLink(mac.port) },
        wifiProbe = { wifiUp.get() },
        wifiProbeIntervalMs = 300,
        bluetoothRetryDelaysMs = bluetoothRetryDelaysMs,
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

class PathSelectionReviewTest {
    private val key = ClipCrypto.randomKey()
    private val mac = MultiFakeMac(key)
    private val statuses = CopyOnWriteArrayList<SyncLoop.Status>()
    private val wifiUp = AtomicBoolean(false)
    private val bleDials = AtomicInteger()
    private var loop: SyncLoop? = null

    @After fun tearDown() { loop?.stop(); mac.close() }

    private fun start(dialer: (PairingInfo) -> Link?, bluetoothRetryDelaysMs: LongArray = longArrayOf(0)): SyncLoop = SyncLoop(
        pairing = { PairingInfo("0123456789abcdef", key, "127.0.0.1", mac.port) },
        locate = { null }, device = "phone",
        callbacks = object : SyncLoop.Callbacks {
            override fun onStatus(status: SyncLoop.Status) { statuses.add(status) }
            override fun onClip(text: String) {}
        },
        backoff = Backoff(longArrayOf(50)),
        pingIntervalMs = 500,
        wifiLink = { host, port -> if (wifiUp.get()) TcpLink(host, port) else DeadLink() },
        bluetoothLink = { info -> bleDials.incrementAndGet(); dialer(info) },
        wifiProbe = { wifiUp.get() },
        wifiProbeIntervalMs = 300,
        bluetoothRetryDelaysMs = bluetoothRetryDelaysMs,
    ).also { loop = it; it.start() }

    private fun eventually(ms: Long = 5_000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (condition()) return true; Thread.sleep(20) }
        return condition()
    }

    @Test fun wifiReturningWaitsForATransferOnBluetoothToFinish() {
        val l = start({ PacketBridgeLink(mac.port) })
        assertTrue(eventually { l.path == SyncLoop.Path.BLUETOOTH })
        mac.ignoreFetch = true  // a slow Bluetooth paste still in progress
        val dir = java.nio.file.Files.createTempDirectory("probe").toFile()
        val fetch = Thread { runCatching { l.fetch("slow", dir, timeoutMs = 1_500, firstDataMs = 1_500) } }.apply { start() }
        Thread.sleep(100)
        wifiUp.set(true)
        Thread.sleep(900)  // three probe intervals with Wi-Fi up
        assertEquals("must not drop Bluetooth mid-transfer", SyncLoop.Path.BLUETOOTH, l.path)
        fetch.join()
        assertTrue("moves to Wi-Fi once the transfer is over", eventually { l.path == SyncLoop.Path.WIFI })
    }

    @Test fun wifiComingBackIsTriedRightAway() {
        val l = SyncLoop(
            pairing = { PairingInfo("0123456789abcdef", key, "127.0.0.1", mac.port) },
            locate = { null }, device = "phone",
            callbacks = object : SyncLoop.Callbacks {
                override fun onStatus(status: SyncLoop.Status) { statuses.add(status) }
                override fun onClip(text: String) {}
            },
            backoff = Backoff(longArrayOf(50)), pingIntervalMs = 500,
            wifiLink = { host, port -> if (wifiUp.get()) TcpLink(host, port) else DeadLink() },
            bluetoothLink = { PacketBridgeLink(mac.port) },
            wifiProbe = { wifiUp.get() },
            wifiProbeIntervalMs = 60_000,  // the regular check is far away
        ).also { loop = it; it.start() }
        assertTrue(eventually { l.path == SyncLoop.Path.BLUETOOTH })
        wifiUp.set(true)
        l.wake()  // Android: a network became available
        assertTrue("switches to Wi-Fi without waiting for the next regular check", eventually(3_000) { l.path == SyncLoop.Path.WIFI })
    }

    @Test fun bluetoothDialerThrowingDoesNotKillTheLoop() {
        start({ throw IllegalStateException("BT Adapter is not turned ON") })
        assertTrue(eventually { bleDials.get() >= 1 })
        wifiUp.set(true)
        assertTrue("loop survives and reconnects over Wi-Fi", eventually { statuses.lastOrNull() == SyncLoop.Status.CONNECTED })
    }

    @Test fun bluetoothMissesBackOffSeparately() {
        val l = start({ null }, bluetoothRetryDelaysMs = longArrayOf(60_000))
        assertTrue(eventually { bleDials.get() == 1 })
        Thread.sleep(800)  // many fast Wi-Fi retries meanwhile
        assertEquals("no Bluetooth rescans while the Mac is away", 1, bleDials.get())
        l.wake()  // network changed: worth one more look
        assertTrue(eventually { bleDials.get() == 2 })
    }
}

class BluetoothLimitsTest {
    @Test fun bluetoothFileCapIsFiveMegabytes() {
        assertEquals(5_242_880L, app.clipbridge.core.FileLimit.BLUETOOTH_MAX_BYTES)
    }

    @Test fun savingToDownloadsAllowsSlowBluetooth() {
        // Nobody is waiting on a paste: allow 20 KB/s, the slow end of real-world Bluetooth.
        assertEquals(60_000L to 60_000L, SyncLoop.saveTimeouts(size = 5_000_000, bluetooth = false))
        assertEquals((30_000L + 5_000_000L / 20) to 30_000L, SyncLoop.saveTimeouts(size = 5_000_000, bluetooth = true))
    }

    @Test fun fetchTimeoutsScaleWithSizeOnBluetooth() {
        assertEquals(20_000L to 4_000L, SyncLoop.fetchTimeouts(size = 2_000_000, bluetooth = false))
        // 10 s + size ÷ 30 KB/s; first data within 6 s
        assertEquals((10_000L + 3_000_000L / 30) to 6_000L, SyncLoop.fetchTimeouts(size = 3_000_000, bluetooth = true))
    }
}

class BluetoothUploadCapTest {
    private val key = ClipCrypto.randomKey()
    private val mac = MultiFakeMac(key)
    @After fun tearDown() { mac.close() }

    @Test fun bigUploadIsRefusedOnBluetoothButFineOnWifi() {
        val statuses = CopyOnWriteArrayList<SyncLoop.Status>()
        fun loopVia(bluetooth: Boolean) = SyncLoop(
            pairing = { PairingInfo("0123456789abcdef", key, "127.0.0.1", mac.port) },
            locate = { null }, device = "phone",
            callbacks = object : SyncLoop.Callbacks {
                override fun onStatus(status: SyncLoop.Status) { statuses.add(status) }
                override fun onClip(text: String) {}
            },
            wifiLink = { host, port -> if (bluetooth) PacketBridgeLink(port) else TcpLink(host, port) },
        )
        val ble = loopVia(bluetooth = true).also { it.start() }
        val end = System.currentTimeMillis() + 5_000
        while (statuses.lastOrNull() != SyncLoop.Status.CONNECTED && System.currentTimeMillis() < end) Thread.sleep(20)
        assertEquals(false, ble.sendFile("big.zip", "application/zip", 6_000_000) { java.io.ByteArrayInputStream(ByteArray(0)) })
        assertTrue(ble.isOnBluetooth)
        ble.stop()
    }
}
