package app.clipbridge.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RichMessageTest {
    @Test fun clipCarriesHtml() {
        val rich = Message.Clip("c1", "Hello", 1, html = "<b>Hello</b>")
        assertEquals("<b>Hello</b>", JSONObject(rich.toJson()).getString("html"))
        assertEquals(rich, Message.parse(rich.toJson()))
    }

    @Test fun plainClipHasNoHtmlKey() {
        assertFalse(JSONObject(Message.Clip("c2", "plain", 1).toJson()).has("html"))
    }

    @Test fun parsesMacRichClip() {
        val mac = """{"html":"<i>x</i>","id":"c3","t":"clip","text":"x","ts":5}"""
        assertEquals(Message.Clip("c3", "x", 5, "<i>x</i>"), Message.parse(mac))
    }

    @Test fun oldClipWithoutHtmlStillParses() {
        assertNull((Message.parse("""{"id":"c","t":"clip","text":"old","ts":5}""") as Message.Clip).html)
    }

    @Test fun fileStartPurpose() {
        val image = Message.FileStart("f", "s.png", "image/png", 3, "x", purpose = "clipboard")
        assertEquals("clipboard", JSONObject(image.toJson()).getString("purpose"))
        assertEquals(image, Message.parse(image.toJson()))
        assertFalse(JSONObject(Message.FileStart("f", "a", "m", 1, "x").toJson()).has("purpose"))
        assertEquals("file", (Message.parse("""{"id":"f","mime":"m","name":"a","sha256":"x","size":1,"t":"file-start"}""") as Message.FileStart).purpose)
    }
}
