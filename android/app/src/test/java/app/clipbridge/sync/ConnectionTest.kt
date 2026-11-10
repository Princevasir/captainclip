package app.clipbridge.sync

import app.clipbridge.core.ClipCrypto
import app.clipbridge.core.Frame
import app.clipbridge.core.FrameReader
import app.clipbridge.core.Message
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** A stand-in Mac built from the shared core: accepts one phone, decrypts what it sends. */
private class FakeMac(key: ByteArray) {
    val crypto = ClipCrypto(key)
    val server = ServerSocket(0)
    val received = LinkedBlockingQueue<Message>()
    lateinit var socket: Socket

    fun acceptInBackground() = Thread {
        socket = server.accept()
        val reader = FrameReader(socket.getInputStream())
        try { while (true) received.put(Message.parse(String(crypto.open(reader.next())))) } catch (_: Exception) {}
    }.apply { isDaemon = true; start() }

    fun send(message: Message) {
        socket.getOutputStream().write(Frame.encode(crypto.seal(message.toJson().toByteArray())))
    }

    fun sendRaw(bytes: ByteArray) = socket.getOutputStream().write(bytes)

    fun close() { runCatching { socket.close() }; server.close() }
}

private class Events : Connection.Listener {
    val connected = LinkedBlockingQueue<Unit>()
    val clips = LinkedBlockingQueue<String>()
    val closed = LinkedBlockingQueue<Unit>()
    override fun onConnected() { connected.put(Unit) }
    override fun onClip(text: String) { clips.put(text) }
    override fun onClosed() { closed.put(Unit) }
}

class ConnectionTest {
    private val key = ClipCrypto.randomKey()
    private val mac = FakeMac(key).also { it.acceptInBackground() }
    private val events = Events()
    private val connection = Connection("127.0.0.1", mac.server.localPort, key, "test-phone", events, pingIntervalMs = 100)

    @After fun tearDown() { connection.close(); mac.close() }

    private fun <T> LinkedBlockingQueue<T>.await(): T? = poll(3, TimeUnit.SECONDS)

    @Test fun sendsHelloFirstThenReportsConnected() {
        connection.start()
        assertEquals(Message.Hello("test-phone"), mac.received.await())
        assertTrue(events.connected.await() != null)
    }

    @Test fun deliversClipFromMac() {
        connection.start()
        mac.received.await()
        mac.send(Message.Clip("1", "from mac", 1))
        assertEquals("from mac", events.clips.await())
    }

    @Test fun sendsClipToMac() {
        connection.start()
        events.connected.await()
        mac.received.await()
        assertTrue(connection.send("from phone"))
        val msg = generateSequence { mac.received.await() }.first { it !is Message.Ping }
        assertEquals("from phone", (msg as Message.Clip).text)
    }

    @Test fun sendNeverBlocksCallerThread() {
        // On Android the caller is the main thread, where socket writes are forbidden.
        // Writes must happen on Connection's own thread, so send() returns at once even
        // when the Mac stops reading and the socket buffer is full.
        val stalledMac = ServerSocket(0)
        val accepted = LinkedBlockingQueue<Socket>()
        Thread { accepted.put(stalledMac.accept()) }.apply { isDaemon = true; start() }
        val conn = Connection("127.0.0.1", stalledMac.localPort, key, "test-phone", events, pingIntervalMs = 60_000)
        conn.start()
        val macSide = accepted.await()  // keep a reference so the stalled socket stays open
        events.connected.await()
        val chunk = "x".repeat(200_000)
        val started = System.nanoTime()
        val accepted2 = (1..100).count { conn.send(chunk) }  // ~20 MB, far beyond any socket buffer
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        conn.close(); macSide?.close(); stalledMac.close()
        assertTrue("send blocked caller for $elapsedMs ms", elapsedMs < 1_000)
        assertTrue("sends are accepted while connected", accepted2 > 0)
    }

    @Test fun refusesOversizedText() {
        connection.start()
        events.connected.await()
        assertFalse(connection.send("a".repeat(300_000)))
    }

    @Test fun dropsOversizedClipFromMacButStaysConnected() {
        connection.start()
        mac.received.await()
        mac.send(Message.Clip("1", "b".repeat(300_000), 1))
        mac.send(Message.Clip("2", "small after big", 2))
        assertEquals("small after big", events.clips.await())
        assertEquals(null, events.closed.poll(100, TimeUnit.MILLISECONDS))
    }

    @Test fun sendsPings() {
        connection.start()
        mac.received.await()
        assertEquals(Message.Ping, mac.received.await())
    }

    @Test fun closesOnFrameWithWrongKey() {
        connection.start()
        mac.received.await()
        val intruder = ClipCrypto(ClipCrypto.randomKey())
        mac.sendRaw(Frame.encode(intruder.seal(Message.Clip("1", "evil", 1).toJson().toByteArray())))
        assertTrue(events.closed.await() != null)
        assertEquals(null, events.clips.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test fun closesWhenMacGoesAway() {
        connection.start()
        mac.received.await()
        mac.socket.close()
        assertTrue(events.closed.await() != null)
    }

    @Test fun reportsClosedWhenMacUnreachable() {
        mac.close()
        connection.start()
        assertTrue(events.closed.await() != null)
    }
}
