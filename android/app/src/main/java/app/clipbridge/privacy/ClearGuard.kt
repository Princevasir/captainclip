package app.clipbridge.privacy

/**
 * Decides whether a one-time code ClipBridge put on the clipboard should now be cleared: only once [delayMs]
 * have passed, only if no newer code was written and nothing newer was copied or sent since ([markChanged]),
 * and, when the clipboard text could be read, only if it still holds that text. A background service usually
 * cannot read the clipboard, so a null [shouldClear] text means "can't tell" and relies on [markChanged].
 */
class ClearGuard(private val clock: () -> Long, private val delayMs: Long = 60_000) {
    private var token = 0L
    private var wroteAt = 0L
    private var text: String? = null
    private var changed = false

    /** Remember a write; the returned token identifies it for [shouldClear]. */
    @Synchronized fun wrote(text: String): Long {
        this.text = text
        wroteAt = clock()
        changed = false
        return ++token
    }

    /** Something newer was copied or sent: every outstanding token is stale. */
    @Synchronized fun markChanged() { changed = true }

    /** True while [token] is the latest write and nothing has changed since (time not considered). */
    @Synchronized fun isCurrent(token: Long): Boolean = token == this.token && !changed

    /** [currentText] is the clipboard's text right now, or null if it can't be read. */
    @Synchronized fun shouldClear(token: Long, currentText: String?): Boolean =
        isCurrent(token) && (currentText == null || currentText == text) && clock() - wroteAt >= delayMs
}
