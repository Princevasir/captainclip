package app.clipbridge.capture

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import app.clipbridge.sync.SyncService

/** "Share → Send to Mac": text goes to the Mac clipboard; files go to the Mac's Downloads + clipboard. */
class ShareReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = streams(intent).filter { SharePolicy.accepts(it.scheme, it.authority) }
        val message = when {
            uris.isNotEmpty() ->
                if (SyncService.sendFiles(this, uris)) {
                    if (uris.size == 1) "Sending to Mac…" else "Sending ${uris.size} files to Mac…"
                } else "Not connected to your Mac"
            intent.action == Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotEmpty() }?.let {
                if (SyncService.send(it)) "Sent to Mac" else "Not connected to your Mac"
            }
            else -> null
        }
        message?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
        finish()
    }

    @Suppress("DEPRECATION")
    private fun streams(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_STREAM))
        Intent.ACTION_SEND_MULTIPLE ->
            (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
        else -> emptyList()
    }
}
