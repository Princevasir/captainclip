package app.clipbridge.capture

import android.os.SystemClock

/** What may be sent from the phone to the Mac. */
object ClipPolicy {
    /** Extra password managers set on Android 13+ (ClipDescription.EXTRA_IS_SENSITIVE). */
    const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    /** [hasUri]: the clip carries a content URI (a file, an image, or ClipBridge's own placeholder) — never sent as text. */
    fun shouldSend(text: String?, sensitive: Boolean, hasUri: Boolean = false): Boolean =
        !text.isNullOrEmpty() && !sensitive && !hasUri
}

/** Remembers when ClipBridge itself last wrote the clipboard, so copy detection can ignore it. */
class OwnWriteWindow(private val windowMs: Long = 2_000, private val clock: () -> Long) {
    @Volatile private var lastWrite: Long? = null

    fun markNow() { lastWrite = clock() }

    fun isRecent(): Boolean = lastWrite?.let { clock() - it <= windowMs } ?: false
}

/** App-wide instance: the sync service marks its writes, the accessibility service checks them. */
val OwnWrites = OwnWriteWindow { SystemClock.elapsedRealtime() }

/** Which shared URIs "Share → Send to Mac" may read: other apps' content only, never raw paths or our own files. */
object SharePolicy {
    fun accepts(scheme: String?, authority: String?): Boolean =
        scheme == "content" && authority != null && authority != app.clipbridge.files.Files.AUTHORITY
}

/** Which clipboard images are sent to the Mac (copied screenshots, "Copy image"). */
object ClipImagePolicy {
    fun accepts(scheme: String?, authority: String?, mime: String?, sensitive: Boolean, size: Long): Boolean =
        SharePolicy.accepts(scheme, authority) &&
            mime != null && mime.startsWith("image/") &&
            !sensitive &&
            size in 1..app.clipbridge.core.FileLimit.MAX_BYTES
}
