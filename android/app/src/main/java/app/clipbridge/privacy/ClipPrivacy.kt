package app.clipbridge.privacy

/** What to do with a clip that arrived from the Mac. */
data class Incoming(val text: String, val putOnClipboard: Boolean, val sensitive: Boolean, val recordInHistory: Boolean)

/** What to send to the Mac for a clip copied on this phone. */
data class Outgoing(val text: String, val sensitive: Boolean)

/** Pure privacy decisions for clips in and out (unit-tested). */
object ClipPrivacy {
    fun incoming(text: String, html: String?, cleanLinks: Boolean, macToClipboard: Boolean, includeFromMac: Boolean): Incoming {
        val out = prepare(text, html, cleanLinks)
        val sensitive = OneTimeCode.isCode(out)
        return Incoming(
            text = out,
            putOnClipboard = macToClipboard,
            sensitive = sensitive,
            recordInHistory = !sensitive && (includeFromMac || !macToClipboard),
        )
    }

    fun outgoing(text: String, html: String?, cleanLinks: Boolean): Outgoing {
        val out = prepare(text, html, cleanLinks)
        return Outgoing(out, OneTimeCode.isCode(out))
    }

    /** Links are cleaned only for plain-text clips; HTML clips stay untouched. */
    private fun prepare(text: String, html: String?, cleanLinks: Boolean) =
        if (cleanLinks && html == null) LinkCleaner.clean(text) else text
}
