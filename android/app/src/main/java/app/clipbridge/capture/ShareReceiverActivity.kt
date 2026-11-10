package app.clipbridge.capture

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import app.clipbridge.core.FileLimit
import app.clipbridge.core.FileReceiver
import app.clipbridge.sync.OutgoingFile
import app.clipbridge.sync.SyncService
import java.io.File

/**
 * "Share → Send to Mac": text goes to the Mac clipboard; files go to the Mac's Downloads + clipboard.
 * Android only lets this screen (not our background service) read shared files, so files are copied into
 * ClipBridge's private cache here first, then handed to the service in-process.
 */
class ShareReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = streams(intent).filter { SharePolicy.accepts(it.scheme, it.authority) }
        when {
            uris.isNotEmpty() -> sendFiles(uris)
            intent.action == Intent.ACTION_SEND -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotEmpty() }?.let {
                    toast(if (SyncService.send(it, intent.getStringExtra(Intent.EXTRA_HTML_TEXT))) "Sent to Mac" else "Not connected to your Mac")
                }
                finish()
            }
            else -> finish()
        }
    }

    private fun sendFiles(uris: List<Uri>) {
        if (!SyncService.isRunning) { toast("Not connected to your Mac"); finish(); return }
        toast(if (uris.size == 1) "Sending to Mac…" else "Sending ${uris.size} files to Mac…")
        Thread {
            val outbox = File(cacheDir, "outgoing").apply { mkdirs() }
            val copies = uris.mapNotNull { uri -> copyIn(uri, outbox) }
            runOnUiThread {
                if (copies.isNotEmpty()) SyncService.sendLocalFiles(copies)
                finish()
            }
        }.start()
    }

    /** Copies one shared file (≤ 25 MB) into [dir]; returns (file, display name, mime) or null. */
    private fun copyIn(uri: Uri, dir: File): OutgoingFile? = try {
        var name = uri.lastPathSegment ?: "file"
        var size = -1L
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (size > FileLimit.MAX_BYTES) {
            runOnUiThread { toast("$name is too large (max 25 MB)") }
            null
        } else {
            val target = File(dir, "${System.nanoTime()}-${FileReceiver.safeName(name)}")
            var copied = 0L
            contentResolver.openInputStream(uri)!!.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        copied += n
                        if (copied > FileLimit.MAX_BYTES) throw IllegalStateException("too large")
                        out.write(buf, 0, n)
                    }
                }
            }
            OutgoingFile(target, name, contentResolver.getType(uri) ?: "application/octet-stream")
        }
    } catch (e: Exception) {
        Log.w("ClipBridgeFiles", "couldn't read shared $uri", e)
        null
    }

    private fun toast(text: String) = Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()

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
