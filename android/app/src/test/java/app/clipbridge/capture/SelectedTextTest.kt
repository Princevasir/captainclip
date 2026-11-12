package app.clipbridge.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectedTextTest {
    @Test fun selectedTextIsSentAsIs() {
        assertEquals(SelectedText.Outcome.SEND, SelectedText.check("  Flat 4B, Rose Court  "))
    }

    @Test fun nothingSelectedSendsNothing() {
        assertEquals(SelectedText.Outcome.EMPTY, SelectedText.check(null))
        assertEquals(SelectedText.Outcome.EMPTY, SelectedText.check("   \n "))
    }

    @Test fun tooMuchTextIsRefused() {
        assertEquals(SelectedText.Outcome.TOO_LARGE, SelectedText.check("x".repeat(262_145)))
    }

    @Test fun messagesTellWhatHappened() {
        assertEquals("Sent to Mac", SelectedText.message(SelectedText.Outcome.SEND, sent = true))
        assertEquals("Not connected to your Mac", SelectedText.message(SelectedText.Outcome.SEND, sent = false))
        assertEquals("Select some text first", SelectedText.message(SelectedText.Outcome.EMPTY, sent = false))
        assertEquals("Too much text to send (max 256 KB)", SelectedText.message(SelectedText.Outcome.TOO_LARGE, sent = false))
    }
}
