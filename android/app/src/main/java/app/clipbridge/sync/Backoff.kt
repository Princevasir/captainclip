package app.clipbridge.sync

/** Reconnect delays: 1s, 2s, 5s, 10s, then 30s forever. */
class Backoff {
    private val steps = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000)
    private var index = 0

    fun nextDelayMs(): Long = steps[index].also { if (index < steps.lastIndex) index++ }

    fun reset() { index = 0 }
}
