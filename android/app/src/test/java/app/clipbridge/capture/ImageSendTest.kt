package app.clipbridge.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageSendTest {
    @Test fun notConnectedIsCheckedBeforeCopying() {
        assertEquals(ImageSend.Outcome.NOT_CONNECTED, ImageSend.decide(isImage = true, connected = false))
        assertEquals(ImageSend.Outcome.NOT_AN_IMAGE, ImageSend.decide(isImage = false, connected = true))
        assertEquals(ImageSend.Outcome.SEND, ImageSend.decide(isImage = true, connected = true))
    }

    @Test fun automaticCopiesStaySilentWhenTheyCantBeSent() {
        assertNull(ImageSend.message(ImageSend.Outcome.NOT_CONNECTED, manual = false))
        assertNull(ImageSend.message(ImageSend.Outcome.NOT_AN_IMAGE, manual = false))
    }

    @Test fun manualSendsSayWhatHappened() {
        assertEquals("Image sent to Mac", ImageSend.message(ImageSend.Outcome.SEND, manual = true))
        assertEquals("Not connected to your Mac", ImageSend.message(ImageSend.Outcome.NOT_CONNECTED, manual = true))
        assertEquals("That's a file — use Share → Send to Mac", ImageSend.message(ImageSend.Outcome.NOT_AN_IMAGE, manual = true))
    }
}
