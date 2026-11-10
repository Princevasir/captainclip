package app.clipbridge.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipPolicyTest {
    @Test fun normalTextIsSent() { assertTrue(ClipPolicy.shouldSend("hello", sensitive = false)) }

    @Test fun passwordManagerCopiesAreNotSent() {
        // Android 13+ password managers flag clips with ClipDescription.EXTRA_IS_SENSITIVE.
        assertFalse(ClipPolicy.shouldSend("hunter2", sensitive = true))
    }

    @Test fun emptyOrMissingTextIsNotSent() {
        assertFalse(ClipPolicy.shouldSend("", sensitive = false))
        assertFalse(ClipPolicy.shouldSend(null, sensitive = false))
    }
}

class OwnWriteWindowTest {
    private var now = 10_000L
    private val window = OwnWriteWindow(windowMs = 2_000) { now }

    @Test fun copyTriggersRightAfterOurOwnWriteAreIgnored() {
        window.markNow()
        now += 1_500
        assertTrue(window.isRecent())
    }

    @Test fun laterCopiesAreNotIgnored() {
        window.markNow()
        now += 2_001
        assertFalse(window.isRecent())
    }

    @Test fun nothingIgnoredBeforeAnyWrite() { assertFalse(window.isRecent()) }
}
