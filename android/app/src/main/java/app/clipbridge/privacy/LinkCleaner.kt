package app.clipbridge.privacy

/** Removes well-known tracking tags from a copied link, leaving everything else byte-for-byte as copied. */
object LinkCleaner {
    private val tracking = setOf(
        "fbclid", "gclid", "gbraid", "wbraid", "dclid", "msclkid",
        "mc_cid", "mc_eid", "igshid", "yclid", "_hsenc", "_hsmi",
    )

    /** Returns [text] unchanged unless it is a single http(s) URL carrying at least one tracking tag. */
    fun clean(text: String): String {
        val url = text.trim()
        val isUrl = (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) &&
            url.none { it.isWhitespace() }
        if (!isUrl) return text

        val hash = url.indexOf('#').let { if (it < 0) url.length else it }
        val q = url.indexOf('?').takeIf { it in 0 until hash } ?: return text
        val params = url.substring(q + 1, hash).split('&')
        val kept = params.filterNot { isTracking(it.substringBefore('=')) }
        if (kept.size == params.size) return text

        val base = url.substring(0, q)
        val query = kept.filter { it.isNotEmpty() }.joinToString("&")
        return base + (if (query.isEmpty()) "" else "?$query") + url.substring(hash)
    }

    private fun isTracking(name: String) = name.startsWith("utm_") || name in tracking
}
