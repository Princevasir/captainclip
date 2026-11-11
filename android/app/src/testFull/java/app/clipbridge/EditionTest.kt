package app.clipbridge

import org.junit.Assert.assertTrue
import org.junit.Test

class EditionTest {
    @Test fun fullHasAutomaticCopy() = assertTrue(Edition.AUTO_COPY)

    @Test fun fullKeepsBluetoothOnOldAndroid() = assertTrue(Edition.LOCATION_FOR_BLUETOOTH)
}
