package app.clipbridge.capture

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import app.clipbridge.sync.SyncService

/** "Share → ClipBridge": sends shared text to the Mac clipboard. */
class ShareReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT)
        if (!text.isNullOrEmpty()) {
            SyncService.send(this, text)
            Toast.makeText(this, "Sent to Mac", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
