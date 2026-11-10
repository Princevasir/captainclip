package app.clipbridge.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootPolicyTest {
    @Test fun startsAfterBootWhenPairedAndEnabled() { assertTrue(BootPolicy.shouldStart(paired = true, enabled = true)) }
    @Test fun staysOffWhenUserStoppedSync() { assertFalse(BootPolicy.shouldStart(paired = true, enabled = false)) }
    @Test fun staysOffWhenNotPaired() { assertFalse(BootPolicy.shouldStart(paired = false, enabled = true)) }
}
