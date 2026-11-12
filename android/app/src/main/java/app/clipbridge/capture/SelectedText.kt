package app.clipbridge.capture

import app.clipbridge.core.TextLimit

/** "Send to Mac" from the text-selection menu: what to do with the selected text. */
object SelectedText {
    enum class Outcome { SEND, EMPTY, TOO_LARGE }

    fun check(text: String?): Outcome = when {
        text.isNullOrBlank() -> Outcome.EMPTY
        !TextLimit.fits(text) -> Outcome.TOO_LARGE
        else -> Outcome.SEND
    }

    fun message(outcome: Outcome, sent: Boolean): String = when (outcome) {
        Outcome.SEND -> if (sent) "Sent to Mac" else "Not connected to your Mac"  // selection sends are always explicit
        Outcome.EMPTY -> "Select some text first"
        Outcome.TOO_LARGE -> "Too much text to send (max 256 KB)"
    }
}
