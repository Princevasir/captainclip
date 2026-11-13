package app.clipbridge.privacy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OneTimeCodeTest {
    @Test fun codes() = listOf("4829", "482913", "48291375", " 482913 ", "482 913", "4829-13", "48-2913").forEach { assertTrue(it, OneTimeCode.isCode(it)) }

    @Test fun notCodes() = listOf("123", "123456789", "482  913", "48 29 13", "4829-13-1", "Code 482913", "In 2026 we", "+91 98765 43210", "1,299", "4829a3", "").forEach { assertFalse(it, OneTimeCode.isCode(it)) }
}
