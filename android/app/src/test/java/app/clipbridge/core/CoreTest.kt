package app.clipbridge.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

private fun bytes(n: Int) = ByteArray(n) { it.toByte() }
private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

/** Same vector as docs/test-vector.txt, produced by the Mac's CryptoKit. */
private const val KNOWN_VECTOR =
    "000102030405060708090a0b3c20a239ffc7b272e326b5f68f06b385001a35d68d5681074ae47dc3"

class CryptoTest {
    private val crypto = ClipCrypto(bytes(32))

    @Test fun sealOpenRoundtrip() {
        val text = "héllo 👋".toByteArray()
        assertArrayEquals(text, crypto.open(crypto.seal(text)))
    }

    @Test fun matchesMacKnownVector() {
        assertEquals(KNOWN_VECTOR, crypto.seal("""{"t":"ping"}""".toByteArray(), bytes(12)).hex())
    }

    @Test fun opensBoxSealedOnMac() {
        val box = KNOWN_VECTOR.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals("""{"t":"ping"}""", String(crypto.open(box)))
    }

    @Test fun wrongKeyFails() {
        val box = crypto.seal("x".toByteArray())
        assertThrows { ClipCrypto(ClipCrypto.randomKey()).open(box) }
    }

    @Test fun tamperedBoxFails() {
        val box = crypto.seal("x".toByteArray())
        box[box.size - 1] = (box[box.size - 1].toInt() xor 1).toByte()
        assertThrows { crypto.open(box) }
    }

    @Test fun shortKeyRejected() { assertThrows { ClipCrypto(ByteArray(16)) } }

    @Test fun randomKeyIs32Bytes() { assertEquals(32, ClipCrypto.randomKey().size) }
}

class FrameTest {
    @Test fun headerIsBigEndianLength() {
        assertArrayEquals(byteArrayOf(0, 0, 0, 5), Frame.encode("first".toByteArray()).copyOfRange(0, 4))
    }

    @Test fun readsConsecutiveFrames() {
        val stream = Frame.encode("first".toByteArray()) + Frame.encode("second!".toByteArray())
        val reader = FrameReader(ByteArrayInputStream(stream))
        assertEquals("first", String(reader.next()))
        assertEquals("second!", String(reader.next()))
    }

    @Test fun rejectsOversizeHeader() {
        assertThrowsIO { FrameReader(ByteArrayInputStream(byteArrayOf(0x00, 0x10, 0x00, 0x01))).next() }
    }

    @Test fun rejectsZeroLength() {
        assertThrowsIO { FrameReader(ByteArrayInputStream(byteArrayOf(0, 0, 0, 0))).next() }
    }

    @Test fun endOfStreamIsIOException() {
        assertThrowsIO { FrameReader(ByteArrayInputStream(byteArrayOf(0, 0, 0, 9, 1, 2))).next() }
    }

    @Test fun encodeRejectsOversizePayload() {
        assertThrows { Frame.encode(ByteArray(Frame.MAX_PAYLOAD + 1)) }
    }

    @Test fun fitsReportsWhetherTextCanBeSent() {
        assertTrue(Frame.fits("hello"))
        assertFalse(Frame.fits("a".repeat(1_100_000)))
    }
}

class MessageTest {
    @Test fun clipRoundtrips() {
        val clip = Message.Clip("abc", "hi \"there\"", 1_700_000_000_000)
        assertTrue(clip.toJson().contains(""""t":"clip""""))
        assertEquals(clip, Message.parse(clip.toJson()))
    }

    @Test fun parsesMacJson() {
        // Exact output of the Mac's Message.clip(...).encoded() (sorted keys).
        val mac = """{"id":"abc","t":"clip","text":"hi","ts":1700000000000}"""
        assertEquals(Message.Clip("abc", "hi", 1_700_000_000_000), Message.parse(mac))
    }

    @Test fun parsesPingAndHello() {
        assertEquals(Message.Ping, Message.parse("""{"t":"ping"}"""))
        assertEquals(Message.Hello("Pixel"), Message.parse("""{"t":"hello","device":"Pixel"}"""))
    }

    @Test fun rejectsUnknownType() { assertThrows { Message.parse("""{"t":"nope"}""") } }

    @Test fun rejectsGarbage() { assertThrows { Message.parse("not json") } }
}

class PairingInfoTest {
    private val macUri = "clipbridge://pair?v=1&id=0123456789abcdef&key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8&host=192.168.1.20&port=52001"

    @Test fun parsesMacUri() {
        val info = PairingInfo.parse(macUri)!!
        assertEquals("0123456789abcdef", info.macId)
        assertArrayEquals(bytes(32), info.key)
        assertEquals("192.168.1.20", info.host)
        assertEquals(52001, info.port)
        assertEquals("ClipBridge-012345", info.serviceName)
    }

    @Test fun parsesBase64UrlKey() {
        val uri = "clipbridge://pair?v=1&id=0123456789abcdef&key=__________________________________________8&host=h&port=1"
        assertArrayEquals(ByteArray(32) { -1 }, PairingInfo.parse(uri)!!.key)
    }

    @Test fun rejectsOtherVersion() { assertNull(PairingInfo.parse(macUri.replace("v=1", "v=2"))) }

    @Test fun rejectsShortKey() {
        assertNull(PairingInfo.parse(macUri.replace("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", "AAECAwQFBgcICQoLDA0ODw")))
    }

    @Test fun rejectsForeignUri() { assertNull(PairingInfo.parse("https://example.com")) }
}

class LoopGuardTest {
    @Test fun blocksEchoOfReceivedAndSent() {
        val guard = LoopGuard()
        assertTrue(guard.shouldSend("a"))
        guard.markReceived("a")
        assertFalse(guard.shouldSend("a"))
        assertTrue(guard.shouldSend("b"))
        guard.markSent("b")
        assertFalse(guard.shouldSend("b"))
        assertFalse(guard.shouldSend(""))
    }
}

private fun assertThrows(block: () -> Unit) {
    try { block(); fail("expected an exception") } catch (e: AssertionError) { throw e } catch (_: Exception) {}
}

private fun assertThrowsIO(block: () -> Unit) {
    try { block(); fail("expected IOException") } catch (_: IOException) {}
}
