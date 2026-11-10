package app.clipbridge.files

import org.junit.Assert.assertEquals
import org.junit.Test

class SaveProgressTest {
    @Test fun retryAfterAPartialFailureSkipsFilesAlreadySaved() {
        val progress = SaveProgress()
        assertEquals(listOf("a", "b", "c"), progress.remaining(listOf("a", "b", "c")))
        progress.saved("a"); progress.saved("b")  // "c" failed
        assertEquals(listOf("c"), progress.remaining(listOf("a", "b", "c")))
    }

    @Test fun aNewCopyOnTheMacStartsOver() {
        val progress = SaveProgress()
        progress.remaining(listOf("a", "b")); progress.saved("a")
        assertEquals(listOf("x", "y"), progress.remaining(listOf("x", "y")))
    }
}
