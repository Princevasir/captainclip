package app.clipbridge.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveMessageTest {
    @Test fun sensitiveRoundTrips() {
        val clip = Message.Clip("1", "482913", 1, sensitive = true)
        assertTrue(JSONObject(clip.toJson()).getBoolean("sensitive"))
        assertEquals(clip, Message.parse(clip.toJson()))
    }

    @Test fun absentMeansNotSensitive() {
        val clip = Message.parse("""{"t":"clip","id":"1","text":"482913","ts":1}""") as Message.Clip
        assertFalse(clip.sensitive)
    }

    @Test fun falseIsNotWritten() {
        assertFalse(JSONObject(Message.Clip("1", "hi", 1).toJson()).has("sensitive"))
    }

    @Test fun parsesFixedWireJson() {
        assertEquals(Message.Clip("1", "482913", 1, sensitive = true),
            Message.parse("""{"t":"clip","id":"1","text":"482913","ts":1,"sensitive":true}"""))
    }
}
