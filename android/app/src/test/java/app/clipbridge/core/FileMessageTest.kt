package app.clipbridge.core

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class FileMessageTest {
    private val all = listOf(
        Message.Offer("o1", "report.pdf", "application/pdf", 1234, 99) to "offer",
        Message.OfferClear to "offer-clear",
        Message.Fetch("o1") to "fetch",
        Message.FileStart("f1", "a.pdf", "application/pdf", 3, "a".repeat(64)) to "file-start",
        Message.FileChunk("f1", 0, byteArrayOf(0, 1, 2)) to "file-chunk",
        Message.FileEnd("f1") to "file-end",
        Message.FileError("f1", "expired") to "file-error",
    )

    @Test fun everyFileMessageHasItsTypeAndRoundtrips() {
        for ((message, type) in all) {
            assertEquals(type, JSONObject(message.toJson()).getString("t"))
            assertEquals(message, Message.parse(message.toJson()))
        }
    }

    @Test fun parsesMacFileChunk() {
        val m = Message.parse("""{"data":"AAEC","id":"x","seq":0,"t":"file-chunk"}""") as Message.FileChunk
        assertArrayEquals(byteArrayOf(0, 1, 2), m.data)
        assertEquals("AAEC", JSONObject(m.toJson()).getString("data"))
    }

    @Test fun parsesMacOffer() {
        val mac = """{"id":"o1","mime":"application/pdf","name":"report.pdf","size":1234,"t":"offer","ts":99}"""
        assertEquals(Message.Offer("o1", "report.pdf", "application/pdf", 1234, 99), Message.parse(mac))
    }

    @Test fun unknownTypesAreTolerated() {
        assertEquals(Message.Unknown("future-thing"), Message.parse("""{"t":"future-thing"}"""))
    }

    @Test fun badChunkDataRejected() {
        try { Message.parse("""{"t":"file-chunk","id":"f","seq":0,"data":"***"}"""); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun offerMissingSizeRejected() {
        try { Message.parse("""{"t":"offer","id":"o","name":"n","mime":"m","ts":1}"""); fail() } catch (_: IllegalArgumentException) {}
    }
}
