package app.clipbridge.core

/** Formatted-text rule shared with the Mac: send HTML only if text + HTML fit in the text cap. */
object RichClip {
    fun fitting(text: String, html: String?): String? {
        if (html.isNullOrEmpty()) return null
        val total = text.toByteArray(Charsets.UTF_8).size + html.toByteArray(Charsets.UTF_8).size
        return if (total <= TextLimit.MAX_BYTES) html else null
    }
}
