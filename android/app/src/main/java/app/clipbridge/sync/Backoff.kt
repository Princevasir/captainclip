package app.clipbridge.sync

/** Reconnect delays: by default 1s, 2s, 5s, 10s, then 30s forever. Thread-safe. */
class Backoff(private val steps: LongArray = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000)) {
    private var index = 0

    val maxDelayMs: Long get() = steps.last()

    @Synchronized fun nextDelayMs(): Long = steps[index].also { if (index < steps.lastIndex) index++ }

    @Synchronized fun reset() { index = 0 }
}
