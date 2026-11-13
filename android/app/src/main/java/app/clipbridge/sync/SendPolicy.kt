package app.clipbridge.sync

/** What to do with a copy headed for the Mac (unit-tested). */
object SendPolicy {
    enum class Decision { SEND, QUEUE, DROP }

    /**
     * [paused] covers a timed pause and "Until I resume" (sync off). While paused only what the user sent on purpose
     * waits; automatic copies are dropped. While the Mac is merely away, every copy waits.
     */
    fun decide(connected: Boolean, paused: Boolean, userInitiated: Boolean): Decision = when {
        paused && !userInitiated -> Decision.DROP
        connected && !paused -> Decision.SEND
        else -> Decision.QUEUE
    }
}
