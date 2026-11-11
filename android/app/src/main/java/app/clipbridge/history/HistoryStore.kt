package app.clipbridge.history

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Encrypts the history file at rest (Keystore AES-GCM on the phone; a fake in tests). */
interface HistoryCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(box: ByteArray): ByteArray
}

/** History file contents: `{"v":1,"entries":[…]}`, UTF-8. */
object HistoryCodec {
    fun encode(entries: List<HistoryEntry>): ByteArray {
        val arr = JSONArray()
        for (e in entries) arr.put(JSONObject().apply {
            put("id", e.id); put("at", e.at); put("dir", e.direction.name)
            e.text?.let { put("text", it) }; e.html?.let { put("html", it) }
            e.fileName?.let { put("name", it) }; e.mime?.let { put("mime", it) }
            if (e.isFile) put("size", e.size)
        })
        return JSONObject().put("v", 1).put("entries", arr).toString().toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): List<HistoryEntry> {
        val arr = JSONObject(String(bytes, Charsets.UTF_8)).getJSONArray("entries")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            HistoryEntry(
                id = o.getString("id"), at = o.getLong("at"), direction = Direction.valueOf(o.getString("dir")),
                text = o.optStringOrNull("text"), html = o.optStringOrNull("html"),
                fileName = o.optStringOrNull("name"), mime = o.optStringOrNull("mime"), size = o.optLong("size", 0),
            )
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? = if (has(key)) getString(key) else null
}

/**
 * The phone's clip history: one per process (see HistoryHub). Every change prunes to [limits] and is written
 * encrypted, atomically. A file that can't be read is discarded — history starts empty, never a crash.
 */
class HistoryStore(
    private val file: File,
    private val cipher: HistoryCipher,
    private val clock: () -> Long = System::currentTimeMillis,
    limits: HistoryLimits = HistoryLimits(),
    enabled: Boolean = true,
) {
    var limits: HistoryLimits = limits
        private set
    var enabled: Boolean = enabled
        private set
    private val history: History = History(load())

    @Synchronized fun entries(): List<HistoryEntry> { pruneAndMaybeSave(); return history.entries }

    @Synchronized fun search(query: String): List<HistoryEntry> { pruneAndMaybeSave(); return history.search(query) }

    @Synchronized fun recordText(text: String, html: String?, direction: Direction, sensitive: Boolean = false) {
        if (enabled && history.addText(text, html, direction, clock(), sensitive)) save()
    }

    @Synchronized fun recordFile(id: String, name: String, mime: String, size: Long, direction: Direction) {
        if (enabled && history.addFile(id, name, mime, size, direction, clock())) save()
    }

    @Synchronized fun delete(id: String) { history.delete(id); save() }

    @Synchronized fun clear() { history.clear(); file.delete() }

    @Synchronized fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) clear()
    }

    @Synchronized fun applyLimits(new: HistoryLimits) { limits = new; save() }

    private fun pruneAndMaybeSave() {
        val before = history.entries.size
        history.prune(limits, clock())
        if (history.entries.size != before) save()
    }

    private fun save() {
        history.prune(limits, clock())
        if (history.entries.isEmpty()) { file.delete(); return }
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(cipher.encrypt(HistoryCodec.encode(history.entries)))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    private fun load(): List<HistoryEntry> {
        if (!file.exists()) return emptyList()
        return runCatching { HistoryCodec.decode(cipher.decrypt(file.readBytes())) }
            .getOrElse { file.delete(); emptyList() }
    }
}
