package app.clipbridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RichClipTest {
    @Test fun smallHtmlIsKept() { assertEquals("<b>a</b>", RichClip.fitting("a", "<b>a</b>")) }
    @Test fun htmlPastTheTextCapIsDropped() { assertNull(RichClip.fitting("a", "x".repeat(300_000))) }
    @Test fun blankHtmlIsDropped() { assertNull(RichClip.fitting("a", "")); assertNull(RichClip.fitting("a", null)) }
}
