package app.clipbridge.sync

import app.clipbridge.core.ClipCrypto
import app.clipbridge.core.Message
import app.clipbridge.core.PairingTag
import app.clipbridge.core.PacketInputStream
import app.clipbridge.core.PacketOutputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class PacketStreamTest {
    @Test fun writesAreCutIntoMtuSizedPacketsInOrder() {
        val packets = CopyOnWriteArrayList<ByteArray>()
        val out = PacketOutputStream(182) { packets.add(it); true }
        val data = ByteArray(1000) { it.toByte() }
        out.write(data, 0, 400); out.write(data, 400, 600); out.flush()
        assertTrue(packets.all { it.size <= 182 })
        assertArrayEquals(data, packets.fold(ByteArray(0)) { acc, p -> acc + p })
    }

    @Test fun packetsReassembleIntoTheStream() {
        val input = PacketInputStream()
        input.offer(byteArrayOf(1, 2, 3)); input.offer(byteArrayOf(4, 5))
        val buf = ByteArray(5)
        var n = 0
        while (n < 5) n += input.read(buf, n, 5 - n)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), buf)
    }

    @Test fun silentLinkTimesOut() {
        val input = PacketInputStream().apply { readTimeoutMs = 200 }
        try { input.read(); fail() } catch (_: SocketTimeoutException) {}
    }

    @Test fun closedLinkEndsTheStream() {
        val input = PacketInputStream()
        input.offer(byteArrayOf(7))
        input.closeFromRemote()
        assertEquals(7, input.read())
        assertEquals(-1, input.read())
    }

    @Test fun failedPacketSendIsAnIOException() {
        val out = PacketOutputStream(20) { false }
        try { out.write(ByteArray(50)); fail() } catch (_: java.io.IOException) {}
    }

    @Test fun pairingTagMatchesMac() {
        assertEquals("9f9f5111", PairingTag.hexOf("0123456789abcdef"))
        assertEquals("CB-9f9f5111", PairingTag.localName("0123456789abcdef"))
    }
}

/** A Link that crosses a 182-byte packet boundary in both directions before reaching a real (fake) Mac socket. */
internal class PacketBridgeLink(private val port: Int, private val mtu: Int = 182) : Link {
    override val isBluetooth = true
    private val socket = Socket()
    override fun open(readTimeoutMs: Int): Pair<InputStream, OutputStream> {
        socket.connect(java.net.InetSocketAddress("127.0.0.1", port))
        val input = PacketInputStream().apply { this.readTimeoutMs = readTimeoutMs }
        Thread {
            val s = socket.getInputStream(); val buf = ByteArray(mtu)
            try { while (true) { val n = s.read(buf); if (n < 0) break; input.offer(buf.copyOf(n)) } } catch (_: Exception) {}
            input.closeFromRemote()
        }.apply { isDaemon = true; start() }
        val out = PacketOutputStream(mtu) { packet -> runCatching { socket.getOutputStream().write(packet) }.isSuccess }
        return input to out
    }
    override fun close() { runCatching { socket.close() } }
}

class PacketLinkTest {
    @get:Rule val tmp = TemporaryFolder()
    private val key = ClipCrypto.randomKey()
    private val uploads by lazy { tmp.newFolder("mac") }
    private val mac by lazy { MultiFakeMac(key, uploadDir = uploads) }
    @After fun tearDown() { mac.close() }

    @Test fun fullSessionOverBluetoothSizedPackets() {
        val connected = LinkedBlockingQueue<Unit>()
        val htmls = LinkedBlockingQueue<String>()
        val conn = Connection(PacketBridgeLink(mac.port), key, "ble-phone", object : Connection.Listener {
            override fun onConnected() { connected.put(Unit) }
            override fun onClip(text: String) {}
            override fun onClip(text: String, html: String?) { html?.let { htmls.put(it) } }
            override fun onClosed(reason: Connection.CloseReason) {}
        })
        conn.start()
        assertTrue("handshake over packets", connected.poll(5, TimeUnit.SECONDS) != null)
        assertTrue(conn.isBluetooth)
        mac.sendClip("hi", "<b>hi</b>")
        assertEquals("<b>hi</b>", htmls.poll(5, TimeUnit.SECONDS))
        conn.close()
    }

    @Test fun twoMegabyteUploadOverBluetoothSizedPackets() {
        val bytes = Random(9).nextBytes(2_000_000)
        val statuses = CopyOnWriteArrayList<SyncLoop.Status>()
        val loop = SyncLoop(
            pairing = { app.clipbridge.core.PairingInfo("0123456789abcdef", key, "127.0.0.1", mac.port) },
            locate = { null }, device = "ble-phone",
            callbacks = object : SyncLoop.Callbacks {
                override fun onStatus(status: SyncLoop.Status) { statuses.add(status) }
                override fun onClip(text: String) {}
            },
            wifiLink = { _, _ -> PacketBridgeLink(mac.port) },
        )
        loop.start()
        val end = System.currentTimeMillis() + 5_000
        while (statuses.lastOrNull() != SyncLoop.Status.CONNECTED && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue(loop.sendFile("notes.pdf", "application/pdf", bytes.size.toLong()) { ByteArrayInputStream(bytes) })
        val done = System.currentTimeMillis() + 20_000
        while (mac.uploads.isEmpty() && System.currentTimeMillis() < done) Thread.sleep(50)
        assertArrayEquals(bytes, mac.uploads.single().readBytes())
        loop.stop()
    }
}
