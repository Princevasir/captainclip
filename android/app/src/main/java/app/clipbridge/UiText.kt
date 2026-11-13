package app.clipbridge

import java.util.Locale
import kotlin.math.roundToLong

/** Small, user-facing wording helpers (unit-tested). */
object UiText {
    /** After a tap on Send to Mac: sent, kept until the Mac is back (away or paused), or refused. */
    fun sendResultText(r: app.clipbridge.sync.SendResult): String = when (r) {
        app.clipbridge.sync.SendResult.SENT -> "Sent to Mac"
        app.clipbridge.sync.SendResult.QUEUED -> "Waiting — sends when your Mac is back"
        app.clipbridge.sync.SendResult.FAILED -> "Couldn't send"
    }

    /** (title, subtitle) for the home-screen widget and the connection toggle. */
    fun connectionLine(paired: Boolean, syncOn: Boolean, connected: Boolean, macName: String?, bluetooth: Boolean): Pair<String, String> = when {
        !paired -> "ClipBridge" to "Not paired"
        !syncOn -> "ClipBridge" to "Paused"
        connected -> (macName ?: "Your Mac") to (if (bluetooth) "Connected · Bluetooth" else "Connected · Wi-Fi")
        else -> (macName ?: "Your Mac") to "Looking for your Mac…"
    }

    /** Status line while a timed pause is running; null once it has ended. */
    fun pausedLine(pausedUntil: Long, now: Long, time: (Long) -> String): String? =
        if (pausedUntil > now) "Paused · resumes at ${time(pausedUntil)}" else null

    /** Android 13+ confirms every clipboard write itself ("Copied"); our own message would be a second pop-up. */
    fun showOwnCopiedMessage(sdk: Int): Boolean = sdk < 33

    /** Second line under the "Send to Mac" quick toggle. */
    fun tileSubtitle(connected: Boolean, bluetooth: Boolean): String = when {
        !connected -> "Mac not connected"
        bluetooth -> "Via Bluetooth"
        else -> "Ready"
    }

    /** The tip at the bottom of the main screen, worded for ClipBridge Pro (automatic copy) or the standard app. */
    fun tip(autoCopy: Boolean, bluetooth: Boolean): String {
        val how = if (autoCopy) "Copies on the phone go to your Mac by themselves. The Send to Mac button in your notification panel and " +
            "Share → Send to Mac work too."
        else "After copying on the phone, tap Send to Mac in your notification panel or use Share → Send to Mac."
        val where = if (bluetooth) "Same Wi-Fi, or Bluetooth when nearby" else "Same Wi-Fi"
        return "$how $where · end-to-end encrypted."
    }

    /** Value line under "Notification panel buttons" on the main screen. */
    fun panelSummary(sendAdded: Boolean, switchAdded: Boolean): String = when {
        sendAdded && switchAdded -> "Both added"
        sendAdded -> "Send to Mac added"
        switchAdded -> "ClipBridge added"
        else -> "Not added"
    }

    /** Value line under "Clipboard history". */
    fun historySummary(enabled: Boolean, items: Int, days: Int, fromMac: Boolean = false): String =
        if (!enabled) "Off"
        else "On · last $items clips, $days ${if (days == 1) "day" else "days"}" + if (fromMac) " · incl. Mac copies" else ""

    /** Value line under "Stay connected in the background". */
    fun backgroundSummary(linked: Boolean, noBatteryLimits: Boolean): String = when {
        linked -> "On — your Mac is linked"
        noBatteryLimits -> "On — no battery limits"
        else -> "Off — tap to turn on"
    }

    private const val OFFER_LIFETIME_MS = 10 * 60_000L

    /** "Just now", "5 min ago", "2 h ago", "Yesterday", "3 days ago". */
    fun ago(msAgo: Long): String {
        val min = msAgo / 60_000
        val hours = msAgo / 3_600_000
        val days = msAgo / 86_400_000
        return when {
            min < 1 -> "Just now"
            hours < 1 -> "$min min ago"
            hours < 24 -> "$hours h ago"
            days < 2 -> "Yesterday"
            else -> "$days days ago"
        }
    }

    /** History section header: "Today", "Yesterday" or "Earlier", by calendar day in [zone]. */
    fun dayGroup(at: Long, now: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
        val day = java.time.Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when (day) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> "Earlier"
        }
    }

    /** Compact time for list rows: "now", "5 min", "2 h", "Yesterday", "3 d". */
    fun agoShort(msAgo: Long): String {
        val min = msAgo / 60_000
        val hours = msAgo / 3_600_000
        val days = msAgo / 86_400_000
        return when {
            min < 1 -> "now"
            hours < 1 -> "$min min"
            hours < 24 -> "$hours h"
            days < 2 -> "Yesterday"
            else -> "$days d"
        }
    }

    /** One compact paragraph (≤ 140 chars + "…") for a history row; files as "📄 name · size". */
    fun historyPreview(entry: app.clipbridge.history.HistoryEntry): String {
        if (entry.isFile) return "📄 ${entry.fileName} · ${size(entry.size)}"
        val flat = (entry.text ?: "").replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= 140) flat else flat.take(140) + "…"
    }

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
