package app.clipbridge.files

/** Sharing the Mac's offered files to another app (Gmail, WhatsApp, Drive…) via Android's share menu. */
object ShareSet {
    /** The type to announce: the files' own type when they all match, any image for mixed images, else any file. */
    fun mimeFor(mimes: List<String>): String {
        val distinct = mimes.distinct()
        if (distinct.isEmpty()) return "*/*"
        if (distinct.size == 1 && distinct[0].count { it == '/' } == 1) return distinct[0]
        val kinds = distinct.map { it.substringBefore('/', "") }.distinct()
        return if (kinds.size == 1 && kinds[0].isNotEmpty() && distinct.all { '/' in it }) "${kinds[0]}/*" else "*/*"
    }
}
