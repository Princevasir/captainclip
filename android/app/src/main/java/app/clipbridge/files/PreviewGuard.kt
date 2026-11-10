package app.clipbridge.files

/**
 * Pull-only rule for the clipboard placeholder: file content goes only to a genuine paste target.
 * Clipboard-history previews (keyboard, system clipboard UI), thumbnail requests and opens right after
 * the offer arrives are refused, so nothing downloads from the Mac without a paste.
 */
object PreviewGuard {
    enum class Method { OPEN_FILE, OPEN_TYPED_ASSET }

    const val MIN_MS_AFTER_OFFER = 1_000L

    fun allow(method: Method, caller: String?, blockedCallers: Set<String>, msSinceOffer: Long, sizeHint: Boolean): Boolean =
        method == Method.OPEN_FILE &&
            !sizeHint &&
            (caller == null || caller !in blockedCallers) &&
            msSinceOffer >= MIN_MS_AFTER_OFFER
}
