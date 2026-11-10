package app.clipbridge.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipImagePolicyTest {
    private fun ok(scheme: String? = "content", authority: String? = "com.samsung.android.app.smartcapture.provider",
                   mime: String? = "image/png", sensitive: Boolean = false, size: Long = 2_000_000) =
        ClipImagePolicy.accepts(scheme, authority, mime, sensitive, size)

    @Test fun screenshotCopiedFromAnotherAppIsSent() { assertTrue(ok()) }
    @Test fun jpegAndWebpToo() { assertTrue(ok(mime = "image/jpeg")); assertTrue(ok(mime = "image/webp")) }
    @Test fun clipBridgesOwnPlaceholderIsNeverSentBack() { assertFalse(ok(authority = "app.clipbridge.files")) }
    @Test fun nonImagesAreNotSentThisWay() { assertFalse(ok(mime = "application/pdf")); assertFalse(ok(mime = null)) }
    @Test fun sensitiveClipsAreNotSent() { assertFalse(ok(sensitive = true)) }
    @Test fun imagesOver25MbAreNotSent() { assertFalse(ok(size = 26_214_401)) }
    @Test fun rawFilePathsAreRefused() { assertFalse(ok(scheme = "file", authority = null)) }
}
