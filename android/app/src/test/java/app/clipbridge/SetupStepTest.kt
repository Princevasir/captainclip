package app.clipbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupStepTest {
    @Test fun pairFirst() {
        assertEquals(SetupStep.PAIR, SetupStep.next(paired = false, autoCopyOn = false))
    }

    @Test fun thenAutomaticCopy() {
        assertEquals(SetupStep.AUTO_COPY, SetupStep.next(paired = true, autoCopyOn = false))
    }

    @Test fun doneWithoutNeedingNotifications() {
        // The user keeps notifications off; sync works without them, so they are not a setup step.
        assertEquals(SetupStep.DONE, SetupStep.next(paired = true, autoCopyOn = true))
    }

    @Test fun everyStepHasTitleAndHelp() {
        SetupStep.entries.forEach { assertTrue(it.title.isNotBlank() && it.help.isNotBlank()) }
    }
}
