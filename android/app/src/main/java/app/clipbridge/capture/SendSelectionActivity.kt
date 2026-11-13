package app.clipbridge.capture

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Html
import android.text.Spanned
import android.text.style.CharacterStyle
import android.widget.Toast
import app.clipbridge.sync.SyncService

/**
 * "Send to Mac" in Android's text-selection menu (Copy · Share · … · Send to Mac). Android hands over the
 * selected text directly — no clipboard reading, no special permission. Sends it (with formatting) and closes.
 */
class SendSelectionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val selected: CharSequence? = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
        val text = selected?.toString()
        val outcome = SelectedText.check(text)
        var result: app.clipbridge.sync.SendResult? = null
        if (outcome == SelectedText.Outcome.SEND && text != null) {
            val html = (selected as? Spanned)
                ?.takeIf { it.getSpans(0, it.length, CharacterStyle::class.java).isNotEmpty() }
                ?.let { Html.toHtml(it, Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE) }
            if (!SyncService.isRunning) SyncService.start(this)
            result = SyncService.send(this, text, html, userInitiated = true)  // an explicit tap: send even a repeat
        }
        val message = result?.let { app.clipbridge.UiText.sendResultText(it, SyncService.holdsSends(this)) } ?: SelectedText.message(outcome, sent = false)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }
}
