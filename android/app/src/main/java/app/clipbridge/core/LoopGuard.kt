package app.clipbridge.core

/** A local copy equal to the last text received from (or sent to) the Mac is not sent again. */
class LoopGuard {
    @Volatile private var lastReceived: String? = null
    @Volatile private var lastSent: String? = null

    fun markReceived(text: String) { lastReceived = text }
    fun markSent(text: String) { lastSent = text }
    fun shouldSend(text: String): Boolean = text.isNotEmpty() && text != lastReceived && text != lastSent
}
