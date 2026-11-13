package app.clipbridge.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharePolicyTest {
    @Test fun normalSharedContentIsAccepted() {
        assertTrue(SharePolicy.accepts("content", "com.google.android.apps.docs.storage"))
        assertTrue(SharePolicy.accepts("content", "media"))
    }

    @Test fun rawFilePathsAreRefused() {
        // file:///data/data/app.clipbridge/... would let any app make CaptainClip send its private files
        assertFalse(SharePolicy.accepts("file", null))
    }

    @Test fun clipBridgesOwnFilesAreRefused() {
        assertFalse(SharePolicy.accepts("content", "app.clipbridge.files"))
    }
}
