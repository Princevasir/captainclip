package app.clipbridge.sync

/**
 * Recent connection events, newest last, capped at [keep]. Holds no addresses or clipboard content.
 * "Keeps dropping" = at least [DROP_THRESHOLD] unexpected ("lost") disconnects within the last hour.
 * [dismissPrompt] hides that prompt until the next unexpected disconnect.
 */
class ConnectionLog(private val clock: () -> Long, private val keep: Int = 50) {
    private val events = ArrayList<ConnEvent>()
    private var dismissed = false

    @Synchronized
    fun add(kind: String) {
        events.add(ConnEvent(clock(), kind))
        while (events.size > keep) events.removeAt(0)
        if (kind == KIND_LOST) dismissed = false
    }

    @Synchronized
    fun recent(n: Int = 20): List<ConnEvent> = events.takeLast(n)

    @Synchronized
    fun keepsDropping(): Boolean {
        if (dismissed) return false
        val now = clock()
        return events.count { it.kind == KIND_LOST && now - it.at <= WINDOW_MS } >= DROP_THRESHOLD
    }

    @Synchronized
    fun dismissPrompt() {
        dismissed = true
    }

    private companion object {
        const val KIND_LOST = "lost"
        const val WINDOW_MS = 3_600_000L
        const val DROP_THRESHOLD = 3
    }
}

/** The one connection log for this process. */
object ConnectionLogHub {
    val log = ConnectionLog(System::currentTimeMillis)
}
