package app.clipbridge

import org.junit.Assert.assertTrue
import org.junit.Test

class EditionTest {
    @Test fun proHasAutomaticCopy() = assertTrue(Edition.AUTO_COPY)

    @Test fun proKeepsBluetoothOnOldAndroid() = assertTrue(Edition.LOCATION_FOR_BLUETOOTH)
}
