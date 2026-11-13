package app.clipbridge.capture

import app.clipbridge.UiText
import app.clipbridge.sync.SendResult

/** Outcome and wording for sending a clipboard image (unit-tested). */
object ImageSend {
    enum class Outcome { SEND, QUEUED, FAILED, NOT_AN_IMAGE }

    /** Automatic copies stay silent unless something was sent; manual sends (tile, button) always explain. */
    fun message(outcome: Outcome, manual: Boolean): String? = when {
        !manual -> null
        outcome == Outcome.SEND -> "Image sent to Mac"
        outcome == Outcome.QUEUED -> UiText.sendResultText(SendResult.QUEUED)
        outcome == Outcome.FAILED -> UiText.sendResultText(SendResult.FAILED)
        else -> "That's a file — use Share → Send to Mac"
    }
}
