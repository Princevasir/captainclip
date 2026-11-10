package app.clipbridge.files

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareSetTest {
    @Test fun sameTypeIsSharedAsThatType() {
        assertEquals("application/pdf", ShareSet.mimeFor(listOf("application/pdf", "application/pdf", "application/pdf")))
    }

    @Test fun mixedImagesShareAsAnyImage() {
        assertEquals("image/*", ShareSet.mimeFor(listOf("image/png", "image/jpeg")))
    }

    @Test fun mixedKindsShareAsAnyFile() {
        assertEquals("*/*", ShareSet.mimeFor(listOf("application/pdf", "image/png")))
        assertEquals("*/*", ShareSet.mimeFor(emptyList()))
    }

    @Test fun malformedTypeFallsBackToAnyFile() {
        assertEquals("*/*", ShareSet.mimeFor(listOf("weird", "weird2")))
    }
}
