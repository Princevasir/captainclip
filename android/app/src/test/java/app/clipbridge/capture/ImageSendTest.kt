package app.clipbridge.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageSendTest {
    @Test fun automaticCopiesStaySilentUnlessSent() {
        assertNull(ImageSend.message(ImageSend.Outcome.QUEUED, manual = false))
        assertNull(ImageSend.message(ImageSend.Outcome.FAILED, manual = false))
        assertNull(ImageSend.message(ImageSend.Outcome.NOT_AN_IMAGE, manual = false))
    }

    @Test fun manualSendsSayWhatHappened() {
        assertEquals("Image sent to Mac", ImageSend.message(ImageSend.Outcome.SEND, manual = true))
        assertEquals("Waiting — sends when your Mac is back", ImageSend.message(ImageSend.Outcome.QUEUED, manual = true))
        assertEquals("Couldn't send", ImageSend.message(ImageSend.Outcome.FAILED, manual = true))
        assertEquals("That's a file — use Share → Send to Mac", ImageSend.message(ImageSend.Outcome.NOT_AN_IMAGE, manual = true))
    }
}
