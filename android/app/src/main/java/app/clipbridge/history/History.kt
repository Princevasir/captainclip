package app.clipbridge.history

import java.util.UUID

/** Which way a clip crossed between the devices. */
enum class Direction { FROM_MAC, TO_MAC }

/** One clip that crossed: text (with optional HTML) or a file (name/type/size only — contents are never kept). */
data class HistoryEntry(
    val id: String,
    val at: Long,
    val direction: Direction,
    val text: String? = null,
    val html: String? = null,
    val fileName: String? = null,
    val mime: String? = null,
    val size: Long = 0,
) {
    val isFile: Boolean get() = fileName != null
}

/** The window: entries beyond [maxItems] or older than [maxAgeMs] drop off. */
data class HistoryLimits(val maxItems: Int = 50, val maxAgeMs: Long = 7 * DAY_MS) {
    companion object {
        const val DAY_MS = 86_400_000L
        val ITEM_CHOICES = listOf(25, 50, 100)
        val DAY_CHOICES = listOf(1, 7, 30)
        const val MAX_HTML_BYTES = 65_536

        /** From saved settings; values outside the offered choices fall back to the defaults (50, 7 days). */
        fun from(items: Int, days: Int): HistoryLimits = HistoryLimits(
            maxItems = items.takeIf { it in ITEM_CHOICES } ?: 50,
            maxAgeMs = (days.takeIf { it in DAY_CHOICES } ?: 7) * DAY_MS,
        )
    }
}

/** The stack of clips, newest first. Not thread-safe; [HistoryStore] serializes access. */
class History(entries: List<HistoryEntry> = emptyList()) {
    private val list = entries.toMutableList()
    val entries: List<HistoryEntry> get() = list.toList()

    /** False when nothing changed (sensitive or blank text). Equal to the newest entry → that entry moves up. */
    fun addText(text: String, html: String?, direction: Direction, now: Long, sensitive: Boolean = false): Boolean {
        if (sensitive || text.isBlank()) return false
        val keptHtml = html?.takeIf { it.toByteArray(Charsets.UTF_8).size <= HistoryLimits.MAX_HTML_BYTES }
        val top = list.firstOrNull()
        if (top != null && !top.isFile && top.text == text) {
            list[0] = top.copy(at = now, direction = direction, html = keptHtml)  // the latest crossing wins
            return true
        }
        list.add(0, HistoryEntry(UUID.randomUUID().toString(), now, direction, text = text, html = keptHtml))
        return true
    }

    /** False when this transfer id is already recorded (a file is listed once, however often it's read). */
    fun addFile(id: String, name: String, mime: String, size: Long, direction: Direction, now: Long): Boolean {
        if (list.any { it.id == id }) return false
        list.add(0, HistoryEntry(id, now, direction, fileName = name, mime = mime, size = size))
        return true
    }

    fun prune(limits: HistoryLimits, now: Long) {
        list.removeAll { now - it.at > limits.maxAgeMs }
        while (list.size > limits.maxItems) list.removeAt(list.lastIndex)
    }

    fun search(query: String): List<HistoryEntry> {
        val q = query.trim()
        if (q.isEmpty()) return entries
        return list.filter { (it.text ?: it.fileName ?: "").contains(q, ignoreCase = true) }
    }

    fun delete(id: String) { list.removeAll { it.id == id } }

    fun clear() = list.clear()
}
