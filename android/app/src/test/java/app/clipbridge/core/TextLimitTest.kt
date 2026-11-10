package app.clipbridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextLimitTest {
    @Test fun capIs256KbOfUtf8() {
        assertEquals(262_144, TextLimit.MAX_BYTES)
        assertTrue(TextLimit.fits("a".repeat(262_144)))
        assertFalse(TextLimit.fits("a".repeat(262_145)))
        assertTrue(TextLimit.fits("é".repeat(131_072)))
        assertFalse(TextLimit.fits("é".repeat(131_073)))
        assertFalse(TextLimit.fits(""))
    }
}
