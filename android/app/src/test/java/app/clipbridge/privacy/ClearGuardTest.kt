package app.clipbridge.privacy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClearGuardTest {
    private var now = 1_000L
    private val guard = ClearGuard({ now })

    @Test fun clearsAfter60s() {
        val t = guard.wrote("482913")
        now += 60_000
        assertTrue(guard.shouldClear(t, "482913"))
    }

    @Test fun notBefore60s() {
        val t = guard.wrote("482913")
        now += 59_999
        assertFalse(guard.shouldClear(t, "482913"))
    }

    @Test fun newerCopyIsNotCleared() {
        val t = guard.wrote("482913")
        now += 60_000
        assertFalse(guard.shouldClear(t, "something else"))
        assertFalse(guard.shouldClear(t, null))
    }

    @Test fun newerWriteWins() {
        val first = guard.wrote("482913")
        now += 30_000
        val second = guard.wrote("771204")
        now += 40_000
        assertFalse(guard.shouldClear(first, "771204"))
        assertFalse(guard.shouldClear(second, "771204"))  // only 40 s since the second write
        now += 20_000
        assertTrue(guard.shouldClear(second, "771204"))
    }
}
