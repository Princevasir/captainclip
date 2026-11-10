package app.clipbridge

import org.junit.Assert.assertFalse
import org.junit.Test

class EditionTest {
    @Test fun liteHasNoAutomaticCopy() = assertFalse(Edition.AUTO_COPY)
}
