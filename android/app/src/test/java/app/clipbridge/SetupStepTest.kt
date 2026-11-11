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

    @Test fun offersBluetoothFallbackWhenNotAllowedYet() {
        assertEquals(SetupStep.BLUETOOTH, SetupStep.next(paired = true, autoCopyOn = true, bluetoothAllowed = false))
    }

    @Test fun notNowHidesTheBluetoothStep() {
        assertEquals(SetupStep.DONE, SetupStep.next(paired = true, autoCopyOn = true, bluetoothAllowed = false, bluetoothDismissed = true))
    }

    @Test fun bluetoothStepComesAfterTheEssentials() {
        assertEquals(SetupStep.AUTO_COPY, SetupStep.next(paired = true, autoCopyOn = false, bluetoothAllowed = false))
    }

    @Test fun everyStepHasTitleAndHelp() {
        SetupStep.entries.forEach { assertTrue(it.title.isNotBlank() && it.help.isNotBlank()) }
    }

    @Test fun allowBluetoothAsksAndroidTheFirstTime() {
        assertEquals(SetupStep.PermissionAction.ASK, SetupStep.bluetoothAction(askedBefore = false, canShowPrompt = false))
        assertEquals(SetupStep.PermissionAction.ASK, SetupStep.bluetoothAction(askedBefore = true, canShowPrompt = true))
    }

    @Test fun afterDontAskAgainItOpensAppInfo() {
        // Android silently ignores the request once the user chose "Don't allow" twice / "Don't ask again".
        assertEquals(SetupStep.PermissionAction.OPEN_APP_INFO, SetupStep.bluetoothAction(askedBefore = true, canShowPrompt = false))
    }

    @Test fun liteNeverAsksForAutomaticCopy() {
        // ClipBridge Lite has no accessibility service, so there's nothing to turn on.
        assertEquals(SetupStep.DONE, SetupStep.next(paired = true, autoCopyOn = false, autoCopyAvailable = false))
        assertEquals(SetupStep.BLUETOOTH,
            SetupStep.next(paired = true, autoCopyOn = false, autoCopyAvailable = false, bluetoothAllowed = false))
    }

    @Test fun bluetoothNeedsAndroid12InLite() {
        // Lite doesn't hold the location permission that Android 10-11 require for Bluetooth scanning.
        assertTrue(SetupStep.bluetoothFallbackAvailable(sdk = 31, hasLocationPermission = false))
        assertTrue(SetupStep.bluetoothFallbackAvailable(sdk = 30, hasLocationPermission = true))
        assertEquals(false, SetupStep.bluetoothFallbackAvailable(sdk = 30, hasLocationPermission = false))
    }

    @Test fun noBluetoothStepWhereBluetoothCantWork() {
        assertEquals(SetupStep.DONE, SetupStep.next(paired = true, autoCopyOn = true, bluetoothAllowed = false, bluetoothAvailable = false))
    }
}
