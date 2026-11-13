package app.clipbridge.capture

import android.os.SystemClock

/** What may be sent from the phone to the Mac. */
object ClipPolicy {
    /** Extra password managers set on Android 13+ (ClipDescription.EXTRA_IS_SENSITIVE). */
    const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    /** [hasUri]: the clip carries a content URI (a file, an image, or CaptainClip's own placeholder) — never sent as text. */
    fun shouldSend(text: String?, sensitive: Boolean, hasUri: Boolean = false): Boolean =
        !text.isNullOrEmpty() && !sensitive && !hasUri
}

/** Remembers when CaptainClip itself last wrote the clipboard, so copy detection can ignore it. */
class OwnWriteWindow(private val windowMs: Long = 2_000, private val clock: () -> Long) {
    @Volatile private var lastWrite: Long? = null

    fun markNow() { lastWrite = clock() }

    fun isRecent(): Boolean = lastWrite?.let { clock() - it <= windowMs } ?: false
}

/** App-wide instance: the sync service marks its writes, the accessibility service checks them. */
val OwnWrites = OwnWriteWindow { SystemClock.elapsedRealtime() }

/**
 * HTML for a copied clip item: the app's own HTML version if it put one, otherwise HTML converted from
 * styled text (bold/italic/links copied out of an editable field arrive as styled text, not HTML).
 */
object ClipHtml {
    fun of(item: android.content.ClipData.Item?): String? {
        item ?: return null
        item.htmlText?.takeIf { it.isNotBlank() }?.let { return it }
        val styled = item.text as? android.text.Spanned ?: return null
        val formatting = styled.getSpans(0, styled.length, android.text.style.CharacterStyle::class.java)
        if (formatting.isEmpty()) return null
        return android.text.Html.toHtml(styled, android.text.Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE)
    }
}

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
