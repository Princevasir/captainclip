package app.clipbridge.sync

import app.clipbridge.core.ClipCrypto
import app.clipbridge.core.Direction
import app.clipbridge.core.Frame
import app.clipbridge.core.FrameReader
import app.clipbridge.core.Message
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A stand-in Mac that speaks protocol v2 like the real one: sends a challenge, decrypts
 * phone→Mac frames, and (if [autoAck]) answers hello and ping.
 */
private class FakeMac(key: ByteArray, private val autoAck: Boolean = true) {
    val crypto = ClipCrypto(key)
    val server = ServerSocket(0)
    val received = LinkedBlockingQueue<Message>()
    val challenge = ByteArray(16).also { SecureRandom().nextBytes(it) }
    @Volatile lateinit var socket: Socket
    var closeAfterHello = false

    fun acceptInBackground() = Thread {
        socket = server.accept()
        socket.getOutputStream().write(Frame.encode(challenge))
        val reader = FrameReader(socket.getInputStream())
        try {
            while (true) {
                val aad = ClipCrypto.sessionAad(Direction.PHONE_TO_MAC, challenge)
                val message = Message.parse(String(crypto.open(reader.next(), aad)))
                received.put(message)
                if (message is Message.Hello && closeAfterHello) { socket.close(); return@Thread }
                if (autoAck && (message is Message.Hello || message is Message.Ping)) {
                    send(if (message is Message.Hello) Message.Hello("FakeMac") else Message.Ping)
                }
            }
        } catch (_: Exception) {}
    }.apply { isDaemon = true; start() }

    fun send(message: Message, direction: Direction = Direction.MAC_TO_PHONE, with: ClipCrypto = crypto) {
        val box = with.seal(message.toJson().toByteArray(), aad = ClipCrypto.sessionAad(direction, challenge))
        synchronized(this) { socket.getOutputStream().write(Frame.encode(box)) }
    }

    fun close() { runCatching { socket.close() }; server.close() }
}

private class Events : Connection.Listener {
    val connected = LinkedBlockingQueue<Unit>()
    val clips = LinkedBlockingQueue<String>()
    val closed = LinkedBlockingQueue<Connection.CloseReason>()
    override fun onConnected() { connected.put(Unit) }
    override fun onClip(text: String) { clips.put(text) }
    override fun onClosed(reason: Connection.CloseReason) { closed.put(reason) }
}

class ConnectionTest {
    private val key = ClipCrypto.randomKey()
    private var mac = FakeMac(key)
    private val events = Events()

    private fun connect(
        fake: FakeMac = mac.also { it.acceptInBackground() },
        pingIntervalMs: Long = 100,
        readTimeoutMs: Int = 60_000,
    ) = Connection("127.0.0.1", fake.server.localPort, key, "test-phone", events, pingIntervalMs, readTimeoutMs)
        .also { connection = it; it.start() }

    private var connection: Connection? = null

    @After fun tearDown() { connection?.close(); mac.close() }

    private fun <T> LinkedBlockingQueue<T>.await(): T? = poll(3, TimeUnit.SECONDS)

    @Test fun sendsHelloFirstThenConnectsAfterMacAnswers() {
        connect()
        assertEquals(Message.Hello("test-phone"), mac.received.await())
        assertTrue(events.connected.await() != null)
    }

    @Test fun notConnectedUntilMacProvesKey() {
        mac = FakeMac(key, autoAck = false)
        connect(mac.also { it.acceptInBackground() })
        mac.received.await()
        assertNull("connected before Mac answered", events.connected.poll(500, TimeUnit.MILLISECONDS))
        mac.send(Message.Hello("FakeMac"))
        assertTrue(events.connected.await() != null)
    }

    @Test fun unknownMessageFromMacIsIgnored() {
        connect()
        events.connected.await()
        mac.send(Message.Unknown("future"))
        mac.send(Message.Clip("1", "after unknown", 1))
        assertEquals("after unknown", events.clips.await())
        assertNull(events.closed.poll(100, TimeUnit.MILLISECONDS))
    }

    @Test fun deliversClipFromMac() {
        connect()
        events.connected.await()
        mac.send(Message.Clip("1", "from mac", 1))
        assertEquals("from mac", events.clips.await())
    }

    @Test fun sendsClipToMac() {
        val conn = connect()
        events.connected.await()
        assertTrue(conn.send("from phone"))
        val msg = generateSequence { mac.received.await() }.first { it is Message.Clip }
        assertEquals("from phone", (msg as Message.Clip).text)
    }

    @Test fun sendNeverBlocksCallerThread() {
        // On Android the caller is the main thread, where socket writes are forbidden.
        val stalled = FakeMac(key)
        val conn = Connection("127.0.0.1", stalled.server.localPort, key, "test-phone", events, 60_000)
        connection = conn
        Thread {
            stalled.socket = stalled.server.accept()
            stalled.socket.getOutputStream().write(Frame.encode(stalled.challenge))
            val reader = FrameReader(stalled.socket.getInputStream())
            reader.next()  // read hello, answer it, then stop reading forever
            stalled.send(Message.Hello("FakeMac"))
        }.apply { isDaemon = true; start() }
        conn.start()
        events.connected.await()
        val chunk = "x".repeat(200_000)
        val started = System.nanoTime()
        val accepted = (1..100).count { conn.send(chunk) }  // ~20 MB, far beyond any socket buffer
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        stalled.close()
        assertTrue("send blocked caller for $elapsedMs ms", elapsedMs < 1_000)
        assertTrue("sends are accepted while connected", accepted > 0)
    }

    @Test fun refusesOversizedText() {
        val conn = connect()
        events.connected.await()
        assertFalse(conn.send("a".repeat(300_000)))
    }

    @Test fun dropsOversizedClipFromMacButStaysConnected() {
        connect()
        events.connected.await()
        mac.send(Message.Clip("1", "b".repeat(300_000), 1))
        mac.send(Message.Clip("2", "small after big", 2))
        assertEquals("small after big", events.clips.await())
        assertNull(events.closed.poll(100, TimeUnit.MILLISECONDS))
    }

    @Test fun sendsPingsAndMacAnswersKeepItAlive() {
        connect(readTimeoutMs = 400)
        events.connected.await()
        assertEquals(Message.Ping, generateSequence { mac.received.await() }.first { it is Message.Ping })
        assertNull("closed although Mac answers pings", events.closed.poll(1_000, TimeUnit.MILLISECONDS))
    }

    @Test fun closesWhenMacGoesSilent() {
        mac = FakeMac(key, autoAck = false)
        connect(mac.also { it.acceptInBackground() }, pingIntervalMs = 60_000, readTimeoutMs = 300)
        mac.received.await()
        mac.send(Message.Hello("FakeMac"))
        events.connected.await()
        assertEquals(Connection.CloseReason.LOST, events.closed.await())
    }

    @Test fun macClosingRightAfterHelloMeansKeyRejected() {
        mac.closeAfterHello = true
        connect()
        assertEquals(Connection.CloseReason.AUTH_FAILED, events.closed.await())
        assertNull(events.connected.poll(100, TimeUnit.MILLISECONDS))
    }

    @Test fun frameWithWrongKeyIsAuthFailure() {
        connect()
        events.connected.await()
        mac.send(Message.Clip("1", "evil", 1), with = ClipCrypto(ClipCrypto.randomKey()))
        assertEquals(Connection.CloseReason.AUTH_FAILED, events.closed.await())
        assertNull(events.clips.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test fun reflectedFrameIsRejected() {
        connect()
        events.connected.await()
        mac.send(Message.Clip("1", "reflected", 1), direction = Direction.PHONE_TO_MAC)
        assertTrue(events.closed.await() != null)
        assertNull(events.clips.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test fun macGoingAwayIsLost() {
        connect()
        events.connected.await()
        mac.socket.close()
        assertEquals(Connection.CloseReason.LOST, events.closed.await())
    }

    @Test fun unreachableMacIsLost() {
        mac.close()
        connect(mac)
        assertEquals(Connection.CloseReason.LOST, events.closed.await())
    }
}
