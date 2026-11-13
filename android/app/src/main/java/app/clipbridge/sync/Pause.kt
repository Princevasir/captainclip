package app.clipbridge.sync

/** How long the user can pause syncing for. */
enum class PauseChoice(val minutes: Int?, val label: String) {
    FIFTEEN_MIN(15, "15 minutes"),
    ONE_HOUR(60, "1 hour"),
    UNTIL_RESUMED(null, "Until I resume"),
}

/** Pure pause rules (unit-tested). */
object Pause {
    /** When a pause started at [now] ends; null means it lasts until the user resumes. */
    fun until(choice: PauseChoice, now: Long): Long? = choice.minutes?.let { now + it * 60_000L }

    /** True while a timed pause is still running (0 = no timed pause). */
    fun isTimedPause(pausedUntil: Long, now: Long): Boolean = pausedUntil > now
}
