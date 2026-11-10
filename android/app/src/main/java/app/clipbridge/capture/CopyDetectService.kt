package app.clipbridge.capture

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * Watches for copy actions in other apps (see [CopyDetect]) and briefly opens [GhostActivity]
 * to read the new clipboard. Only reads event text — never window content.
 */
class CopyDetectService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastLaunch = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val trigger = CopyDetect.isCopyTrigger(
            event.eventType, event.className, event.packageName, event.text, event.contentDescription)
        if (!trigger) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunch < DEBOUNCE_MS) return
        lastLaunch = now
        // Give the app a moment to finish writing the clipboard before we read it.
        handler.postDelayed({ startActivity(GhostActivity.readIntent(this, announce = false)) }, READ_DELAY_MS)
    }

    override fun onInterrupt() {}

    private companion object {
        const val DEBOUNCE_MS = 1_000L
        const val READ_DELAY_MS = 150L
    }
}
