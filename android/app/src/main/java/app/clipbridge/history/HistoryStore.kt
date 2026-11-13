package app.clipbridge.history

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

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
            if (e.held) put("held", true)
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
                fileName = o.optStringOrNull("name"), mime = o.optStringOrNull("mime"), size = o.optLong("size", 0), held = o.optBoolean("held", false),
            )
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? = if (has(key)) getString(key) else null
}

/**
 * The phone's clip history: one per process (see HistoryHub). Changes apply in memory at once; encrypting and
 * writing happen on [writer] (a background thread in the app), coalesced, so callers on the main or connection
 * thread never wait for disk or crypto. A file that is truly unreadable (wrong key, damaged) is discarded; a
 * passing error (keystore busy) leaves it alone and this run keeps history in memory only.
 */
class HistoryStore(
    private val file: File,
    private val cipher: HistoryCipher,
    private val clock: () -> Long = System::currentTimeMillis,
    limits: HistoryLimits = HistoryLimits(),
    enabled: Boolean = true,
    includeFromMac: Boolean = false,
    private val writer: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "clipbridge-history").apply { isDaemon = true } },
) {
    var limits: HistoryLimits = limits
        private set
    var enabled: Boolean = enabled
        private set
    /**
     * Whether text that arrives from the Mac is kept. Android can't tell whether it was ever pasted on the phone,
     * so by default it isn't: everything copied on the Mac would otherwise pile up here.
     */
    var includeFromMac: Boolean = includeFromMac
        private set
    private val tmp = File(file.parentFile, file.name + ".tmp")
    private var persist = true    // false after a passing load error: don't overwrite the history we couldn't read
    private var version = 0L      // bumped on every change; a queued write that's out of date is skipped
    private var pending = false
    private val history: History = History(load())

    init { if (!includeFromMac && history.removeMacText()) save() }

    @Synchronized fun entries(): List<HistoryEntry> { pruneAndMaybeSave(); return history.entries }

    @Synchronized fun search(query: String): List<HistoryEntry> { pruneAndMaybeSave(); return history.search(query) }

    @Synchronized fun recordText(text: String, html: String?, direction: Direction, sensitive: Boolean = false, held: Boolean = false) {
        if (direction == Direction.FROM_MAC && !includeFromMac && !held) return
        if (enabled && history.addText(text, html, direction, clock(), sensitive, held)) save()
    }

    @Synchronized fun recordFile(id: String, name: String, mime: String, size: Long, direction: Direction) {
        if (enabled && history.addFile(id, name, mime, size, direction, clock())) save()
    }

    @Synchronized fun delete(id: String) { history.delete(id); save() }

    @Synchronized fun clear() {
        history.clear()
        version++
        file.delete(); tmp.delete()
    }

    @Synchronized fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) clear()
    }

    @Synchronized fun setIncludeFromMac(on: Boolean) {
        includeFromMac = on
        if (!on && history.removeMacText()) save()
    }

    @Synchronized fun applyLimits(new: HistoryLimits) { limits = new; save() }

    private fun pruneAndMaybeSave() {
        val before = history.entries.size
        history.prune(limits, clock())
        if (history.entries.size != before) save()
    }

    /** Called with the lock held: note the change and make sure one write is queued. */
    private fun save() {
        history.prune(limits, clock())
        version++
        if (!persist || pending) return
        pending = true
        writer.execute { flush() }
    }

    /** On the writer: snapshot under the lock, encrypt outside it, write only if nothing newer happened since. */
    private fun flush() {
        val (snapshot, v) = synchronized(this) { pending = false; history.entries to version }
        val bytes = if (snapshot.isEmpty()) null else runCatching { cipher.encrypt(HistoryCodec.encode(snapshot)) }.getOrNull()
        synchronized(this) {
            if (v != version || !persist) return
            if (bytes == null) { file.delete(); return }
            runCatching {
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            }
        }
    }

    private fun load(): List<HistoryEntry> {
        if (!file.exists()) return emptyList()
        return try {
            HistoryCodec.decode(cipher.decrypt(file.readBytes()))
        } catch (e: Exception) {
            if (unreadable(e)) file.delete() else persist = false
            emptyList()
        }
    }

    /** Wrong key / damaged / not ours: safe to discard. Anything else may be a passing error. */
    private fun unreadable(e: Exception): Boolean =
        e is java.security.GeneralSecurityException || e is org.json.JSONException || e is IllegalArgumentException ||
            e is java.io.EOFException
}
