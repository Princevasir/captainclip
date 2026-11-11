package app.clipbridge

import org.junit.Assert.assertFalse
import org.junit.Test

class EditionTest {
    @Test fun standardHasNoAutomaticCopy() = assertFalse(Edition.AUTO_COPY)

    @Test fun standardHasNoLocationPermission() = assertFalse(Edition.LOCATION_FOR_BLUETOOTH)
}
