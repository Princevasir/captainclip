package app.clipbridge.capture

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import app.clipbridge.sync.SyncService

/**
 * Invisible screen that exists for a split second. Android 10+ only lets the focused app read the
 * clipboard, so the accessibility service, tile and notification open this to read it, send it to
 * the Mac, and close. Pattern adapted from ClipSync's ClipboardGhostActivity (MIT).
 */
class GhostActivity : Activity() {
    companion object {
        const val ACTION_READ = "app.clipbridge.GHOST_READ"
        const val ACTION_WRITE = "app.clipbridge.GHOST_WRITE"
        const val EXTRA_TEXT = "text"
        /** Show a "Sent to Mac" toast (for manual sends from tile / notification). */
        const val EXTRA_ANNOUNCE = "announce"
        private const val SAFETY_TIMEOUT_MS = 2_000L

        fun readIntent(ctx: Context, announce: Boolean): Intent =
            Intent(ctx, GhostActivity::class.java)
                .setAction(ACTION_READ)
                .putExtra(EXTRA_ANNOUNCE, announce)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handler.postDelayed({ finishQuietly() }, SAFETY_TIMEOUT_MS)
        if (intent.action == ACTION_WRITE) {
            intent.getStringExtra(EXTRA_TEXT)?.let {
                clipboard().setPrimaryClip(ClipData.newPlainText("CaptainClip", it))
            }
            finishQuietly()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done || intent.action != ACTION_READ) return
        val clip = clipboard().primaryClip?.takeIf { it.itemCount > 0 }
        if (clip?.getItemAt(0)?.uri != null) {
            // A copied image (screenshot "Copy", Chrome "Copy image") → Mac clipboard. Read it before closing.
            val announce = intent.getBooleanExtra(EXTRA_ANNOUNCE, false)
            Thread {
                val outcome = ClipImages.sendIfImage(applicationContext, clip, manual = announce)
                ImageSend.message(outcome, manual = announce, paused = SyncService.holdsSends(applicationContext))?.let { text ->
                    runOnUiThread { Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show() }
                }
            }.start()
            finishQuietly()
            return
        }
        val text = clip?.getItemAt(0)?.coerceToText(this)?.toString()
        val sensitive = clip?.description?.extras?.getBoolean(ClipPolicy.EXTRA_IS_SENSITIVE, false) ?: false
        val hasUri = clip?.getItemAt(0)?.uri != null
        if (ClipPolicy.shouldSend(text, sensitive, hasUri)) {
            val html = ClipHtml.of(clip?.getItemAt(0))
            val announce = intent.getBooleanExtra(EXTRA_ANNOUNCE, false)
            // A tap on Send to Mac (tile, widget, shortcut) always sends; automatic copies keep the echo guard.
            val result = SyncService.send(this, text!!, html, userInitiated = announce)
            android.util.Log.i("ClipBridgeFiles", "phone copy → Mac: text ${text.length} chars, html ${html?.length ?: 0} chars, result=$result")
            if (announce) {
                Toast.makeText(this, app.clipbridge.UiText.sendResultText(result, SyncService.holdsSends(this)), Toast.LENGTH_SHORT).show()
            }
        }
        finishQuietly()
    }

    private fun clipboard() = getSystemService(ClipboardManager::class.java)

    private fun finishQuietly() {
        if (done) return
        done = true
        handler.removeCallbacksAndMessages(null)
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}
