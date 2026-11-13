package app.clipbridge.privacy

/**
 * Decides whether a one-time code ClipBridge put on the clipboard should now be cleared: only once [delayMs]
 * have passed, only if no newer code was written since, and only if the clipboard still holds that text.
 */
class ClearGuard(private val clock: () -> Long, private val delayMs: Long = 60_000) {
    private var token = 0L
    private var wroteAt = 0L
    private var text: String? = null

    /** Remember a write; the returned token identifies it for [shouldClear]. */
    @Synchronized fun wrote(text: String): Long {
        this.text = text
        wroteAt = clock()
        return ++token
    }

    /** [currentText] is the clipboard's text right now (null if it can't be read: never clear then). */
    @Synchronized fun shouldClear(token: Long, currentText: String?): Boolean =
        token == this.token && currentText != null && currentText == text && clock() - wroteAt >= delayMs
}
