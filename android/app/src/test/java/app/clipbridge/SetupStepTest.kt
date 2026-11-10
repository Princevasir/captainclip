package app.clipbridge

import org.junit.Assert.assertEquals
import org.junit.Test

class SetupStepTest {
    @Test fun pairFirst() {
        assertEquals(SetupStep.PAIR, SetupStep.next(paired = false, notificationsAllowed = false, autoCopyOn = false))
    }

    @Test fun thenNotifications() {
        assertEquals(SetupStep.NOTIFICATIONS, SetupStep.next(paired = true, notificationsAllowed = false, autoCopyOn = false))
    }

    @Test fun thenAutomaticCopy() {
        assertEquals(SetupStep.AUTO_COPY, SetupStep.next(paired = true, notificationsAllowed = true, autoCopyOn = false))
    }

    @Test fun doneWhenAllSet() {
        assertEquals(SetupStep.DONE, SetupStep.next(paired = true, notificationsAllowed = true, autoCopyOn = true))
    }

    @Test fun everyStepHasHelpText() {
        SetupStep.entries.forEach { assert(it.help.isNotBlank()) }
    }
}
