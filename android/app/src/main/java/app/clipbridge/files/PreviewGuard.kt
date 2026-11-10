package app.clipbridge.files

/**
 * Pull-only rule for the clipboard placeholder: file content goes only to a genuine paste target.
 *
 * Measured on a Galaxy S25 Ultra (One UI 9): Samsung Keyboard asks for "image" and "any type" the instant a
 * file lands on the clipboard (a preview); Gmail and My Files paste with openTypedAssetFile("any type").
 * So: refuse known previewers (keyboard, system clipboard UI), thumbnail-sized requests, type filters that
 * don't match the file (an image preview of a PDF), and anything in the first second after the offer.
 */
object PreviewGuard {
    enum class Method { OPEN_FILE, OPEN_TYPED_ASSET }

    const val MIN_MS_AFTER_OFFER = 1_000L

    fun allow(
        method: Method,
        caller: String?,
        blockedCallers: Set<String>,
        msSinceOffer: Long,
        sizeHint: Boolean,
        mimeFilter: String? = null,
        mime: String? = null,
    ): Boolean {
        if (sizeHint) return false
        if (caller != null && caller in blockedCallers) return false
        if (msSinceOffer < MIN_MS_AFTER_OFFER) return false
        if (method == Method.OPEN_TYPED_ASSET && !matches(mimeFilter ?: return false, mime ?: return false)) return false
        return true
    }

    // Filter forms: any type ("*" + "/*"), a whole family ("application/" + "*"), or an exact type.
    fun matches(filter: String, mime: String): Boolean {
        val f = filter.lowercase(); val m = mime.lowercase()
        return f == "*/*" || f == m || (f.endsWith("/*") && m.startsWith(f.removeSuffix("*")))
    }
}
