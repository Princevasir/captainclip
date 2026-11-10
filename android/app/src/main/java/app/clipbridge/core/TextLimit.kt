package app.clipbridge.core

/**
 * App-level cap on clipboard text: 256 KB of UTF-8 (same as the Mac). Keeps every clip well under
 * Android's ~1 MB Binder limit, so setPrimaryClip and Intents can never crash the app.
 */
object TextLimit {
    const val MAX_BYTES = 262_144

    fun fits(text: String): Boolean {
        val n = text.toByteArray(Charsets.UTF_8).size
        return n in 1..MAX_BYTES
    }
}
