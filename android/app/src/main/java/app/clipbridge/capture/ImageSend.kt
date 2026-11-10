package app.clipbridge.capture

/** Outcome and wording for sending a clipboard image (unit-tested). */
object ImageSend {
    enum class Outcome { SEND, NOT_CONNECTED, NOT_AN_IMAGE }

    fun decide(isImage: Boolean, connected: Boolean): Outcome = when {
        !isImage -> Outcome.NOT_AN_IMAGE
        !connected -> Outcome.NOT_CONNECTED
        else -> Outcome.SEND
    }

    /** Automatic copies stay silent unless something was sent; manual sends (tile, button) always explain. */
    fun message(outcome: Outcome, manual: Boolean): String? = when {
        !manual -> null
        outcome == Outcome.SEND -> "Image sent to Mac"
        outcome == Outcome.NOT_CONNECTED -> "Not connected to your Mac"
        else -> "That's a file — use Share → Send to Mac"
    }
}
