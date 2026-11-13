package app.clipbridge.sync

import app.clipbridge.sync.SendPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class SendPolicyTest {
    @Test fun connectedAndNotPausedSends() {
        assertEquals(Decision.SEND, SendPolicy.decide(connected = true, paused = false, userInitiated = true))
        assertEquals(Decision.SEND, SendPolicy.decide(connected = true, paused = false, userInitiated = false))
    }

    @Test fun macAwayQueuesEveryCopy() {
        assertEquals(Decision.QUEUE, SendPolicy.decide(connected = false, paused = false, userInitiated = true))
        assertEquals("automatic copies still wait while the Mac is merely away",
            Decision.QUEUE, SendPolicy.decide(connected = false, paused = false, userInitiated = false))
    }

    @Test fun pausedQueuesOnlyWhatTheUserSent() {
        assertEquals(Decision.QUEUE, SendPolicy.decide(connected = true, paused = true, userInitiated = true))
        assertEquals(Decision.QUEUE, SendPolicy.decide(connected = false, paused = true, userInitiated = true))
    }

    @Test fun pausedDropsAutomaticCopies() {
        assertEquals(Decision.DROP, SendPolicy.decide(connected = true, paused = true, userInitiated = false))
        assertEquals(Decision.DROP, SendPolicy.decide(connected = false, paused = true, userInitiated = false))
    }
}
