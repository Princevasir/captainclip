package app.clipbridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.ImageView
import android.widget.Toast
import app.clipbridge.capture.OwnWrites
import app.clipbridge.history.Direction
import app.clipbridge.history.HistoryEntry

/** Shared bits for showing and reusing history clips (main screen card and the history screen). */
object HistoryUi {
    fun bindBadge(badge: ImageView, e: HistoryEntry) {
        when {
            e.isFile -> { badge.setBackgroundResource(R.drawable.bg_badge_file); badge.setImageResource(R.drawable.ic_file) }
            e.direction == Direction.FROM_MAC -> { badge.setBackgroundResource(R.drawable.bg_badge_from); badge.setImageResource(R.drawable.ic_from_mac) }
            else -> { badge.setBackgroundResource(R.drawable.bg_badge_to); badge.setImageResource(R.drawable.ic_to_mac) }
        }
        badge.contentDescription = if (e.direction == Direction.FROM_MAC) "From Mac" else "To Mac"
    }

    /** Puts a text clip back on the phone's clipboard (with formatting). Files: explains that only the name is kept. */
    fun copy(ctx: Context, e: HistoryEntry) {
        if (e.isFile) { toast(ctx, "Only the file's name is kept — its contents aren't stored"); return }
        val text = e.text ?: return
        OwnWrites.markNow()  // ClipBridge Pro: don't send this back to the Mac by itself
        val clip = if (e.html != null) ClipData.newHtmlText("ClipBridge", text, e.html) else ClipData.newPlainText("ClipBridge", text)
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        toast(ctx, "Copied")
    }

    private fun toast(ctx: Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
}
