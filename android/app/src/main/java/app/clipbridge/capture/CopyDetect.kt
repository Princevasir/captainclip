package app.clipbridge.capture

import android.view.accessibility.AccessibilityEvent

/**
 * Decides whether an accessibility event means "the user just copied something".
 * Approach adapted from ClipSync's ClipboardAccessibilityService (MIT, see THIRD_PARTY_NOTICES.md):
 * a click on a "Copy" control, a "Copied" toast, or the Android 13+ system "Copied" overlay.
 */
object CopyDetect {
    private const val OWN_PACKAGE = "app.clipbridge"
    private const val SYSTEM_UI = "com.android.systemui"

    /** Exact labels of copy buttons/menu items (after lowercasing and trimming). */
    private val copyLabels = setOf(
        "copy", "copy text", "copy link", "copy link address", "copy to clipboard",
        "copy message", "copy code", "copy url",
        "कॉपी", "कॉपी करें", "लिंक कॉपी करें",
    )

    /** Phrases found inside "copied" confirmations. */
    private val copiedPhrases = listOf("copied", "कॉपी किया", "कॉपी हो गया")

    private val excluded = listOf("copyright", "©")

    fun matches(text: String): Boolean {
        val t = text.trim().lowercase()
        if (t.isEmpty() || excluded.any { it in t }) return false
        return t in copyLabels || copiedPhrases.any { it in t }
    }

    fun isCopyTrigger(
        eventType: Int,
        className: CharSequence?,
        packageName: CharSequence?,
        texts: List<CharSequence>,
        description: CharSequence?,
    ): Boolean {
        if (packageName == OWN_PACKAGE) return false
        val candidates = (texts + listOfNotNull(description)).map { it.toString() }
        val anyMatch = candidates.any(::matches)
        return when (eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> anyMatch
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> className?.contains("Toast") == true && anyMatch
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> packageName == SYSTEM_UI && anyMatch
            else -> false
        }
    }
}
