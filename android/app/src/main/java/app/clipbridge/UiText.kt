package app.clipbridge

import java.util.Locale
import kotlin.math.roundToLong

/** Small, user-facing wording helpers (unit-tested). */
object UiText {
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
