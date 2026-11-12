package app.clipbridge.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MacCompanionTest {
    @Test fun findsOnlyThisPairingsMac() {
        val pattern = MacCompanion.namePattern("0123456789abcdef")  // tag 9f9f5111
        assertTrue(pattern.matcher("9f9f5111").matches())
        assertFalse("another ClipBridge Mac", pattern.matcher("19df4b89").matches())
        assertFalse(pattern.matcher("x9f9f5111").matches())
    }
}
