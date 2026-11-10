package app.clipbridge.capture

import android.os.SystemClock

/** What may be sent from the phone to the Mac. */
object ClipPolicy {
    /** Extra password managers set on Android 13+ (ClipDescription.EXTRA_IS_SENSITIVE). */
    const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    fun shouldSend(text: String?, sensitive: Boolean): Boolean = !text.isNullOrEmpty() && !sensitive
}

/** Remembers when ClipBridge itself last wrote the clipboard, so copy detection can ignore it. */
class OwnWriteWindow(private val windowMs: Long = 2_000, private val clock: () -> Long) {
    @Volatile private var lastWrite: Long? = null

    fun markNow() { lastWrite = clock() }

    fun isRecent(): Boolean = lastWrite?.let { clock() - it <= windowMs } ?: false
}

/** App-wide instance: the sync service marks its writes, the accessibility service checks them. */
val OwnWrites = OwnWriteWindow { SystemClock.elapsedRealtime() }
