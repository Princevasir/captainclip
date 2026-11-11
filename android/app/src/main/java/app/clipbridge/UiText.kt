package app.clipbridge

import java.util.Locale
import kotlin.math.roundToLong

/** Small, user-facing wording helpers (unit-tested). */
object UiText {
    /** Second line under the "Send to Mac" quick toggle. */
    fun tileSubtitle(connected: Boolean, bluetooth: Boolean): String = when {
        !connected -> "Mac not connected"
        bluetooth -> "Via Bluetooth"
        else -> "Ready"
    }

    /** The tip at the bottom of the main screen, worded for ClipBridge Pro (automatic copy) or the standard app. */
    fun tip(autoCopy: Boolean, bluetooth: Boolean): String {
        val how = if (autoCopy) "Copies on the phone go to your Mac by themselves. The “Send to Mac” quick toggle and " +
            "Share → Send to Mac work too."
        else "After copying on the phone, tap the “Send to Mac” quick toggle or use Share → Send to Mac."
        val where = if (bluetooth) "Same Wi-Fi, or Bluetooth when nearby" else "Same Wi-Fi"
        return "$how $where · end-to-end encrypted."
    }

    private const val OFFER_LIFETIME_MS = 10 * 60_000L

    fun size(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return scaled(kb, "KB")
        return scaled(kb / 1024.0, "MB")
    }

    private fun scaled(value: Double, unit: String): String =
        if (value < 10) String.format(Locale.US, "%.1f %s", value, unit).replace(".0 ", " ")
        else "${value.roundToLong()} $unit"

    fun timeLeft(msSinceOffer: Long): String {
        val remaining = OFFER_LIFETIME_MS - msSinceOffer
        if (remaining <= 0) return "Expiring now"
        val minutes = maxOf(1, remaining / 60_000)
        return "Available for $minutes more minute${if (minutes == 1L) "" else "s"}"
    }
}
