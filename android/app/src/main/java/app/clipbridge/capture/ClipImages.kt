package app.clipbridge.capture

import android.content.ClipData
import android.content.Context
import android.provider.OpenableColumns
import android.util.Log
import app.clipbridge.core.FileLimit
import app.clipbridge.core.FileReceiver
import app.clipbridge.sync.OutgoingFile
import app.clipbridge.sync.SendResult
import app.clipbridge.sync.SyncService
import java.io.File

/** Sends an image that's on the phone clipboard to the Mac clipboard (used by the ghost screen and the app). */
object ClipImages {
    /**
     * Sends the clip's image to the Mac if it is one (or queues it while away). Call while the clip is readable.
     * [manual] is an explicit tap; an automatic copy made while paused is dropped.
     */
    fun sendIfImage(context: Context, clip: ClipData?, manual: Boolean): ImageSend.Outcome {
        val uri = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri ?: return ImageSend.Outcome.NOT_AN_IMAGE
        val resolver = context.contentResolver
        val mime = runCatching { resolver.getType(uri) }.getOrNull() ?: clip.description.getMimeType(0)
        val sensitive = clip.description.extras?.getBoolean(ClipPolicy.EXTRA_IS_SENSITIVE, false) ?: false
        var name = "Image.png"
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        if (size < 0) size = runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1
        val isImage = ClipImagePolicy.accepts(uri.scheme, uri.authority, mime, sensitive, if (size < 0) 1 else size)
        if (!isImage) return ImageSend.Outcome.NOT_AN_IMAGE  // checked before copying anything
        val dir = File(context.cacheDir, "outgoing").apply { mkdirs() }
        val target = File(dir, "${System.nanoTime()}-${FileReceiver.safeName(name)}")
        return try {
            var copied = 0L
            resolver.openInputStream(uri)!!.use { input ->
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
            when (SyncService.sendClipboardImage(context, OutgoingFile(target, name, mime!!, purpose = "clipboard"), key = "$uri:$copied",
                manual = manual)) {
                SendResult.SENT -> ImageSend.Outcome.SEND
                SendResult.QUEUED -> ImageSend.Outcome.QUEUED
                SendResult.FAILED -> ImageSend.Outcome.FAILED
            }
        } catch (e: Exception) {
            Log.w("ClipBridgeFiles", "couldn't read clipboard image: ${e.javaClass.simpleName}")  // no URI or path
            target.delete()
            ImageSend.Outcome.NOT_AN_IMAGE
        }
    }
}
