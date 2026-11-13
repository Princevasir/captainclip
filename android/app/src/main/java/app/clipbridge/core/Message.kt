package app.clipbridge.core

import org.json.JSONException
import org.json.JSONObject
import java.util.Base64

/**
 * Plaintext JSON messages, discriminated by "t". Same wire format as the Mac.
 * Unknown types parse to [Unknown] and are ignored, so newer messages never break a connection.
 */
sealed class Message {
    data class Hello(val device: String) : Message()
    data class Clip(val id: String, val text: String, val ts: Long, val html: String? = null, val sensitive: Boolean = false) : Message()
    data object Ping : Message()

    // v2 files
    data class Offer(val id: String, val name: String, val mime: String, val size: Long, val ts: Long) : Message()
    /** One file in a several-files offer. */
    data class OfferItem(val id: String, val name: String, val mime: String, val size: Long)
    /** Several files copied together on the Mac. */
    data class Offers(val items: List<OfferItem>, val ts: Long) : Message() {
        val offers: List<Offer> get() = items.map { Offer(it.id, it.name, it.mime, it.size, ts) }
    }
    data object OfferClear : Message()
    data class Fetch(val id: String) : Message()
    data class FileStart(
        val id: String, val name: String, val mime: String, val size: Long, val sha256: String,
        val purpose: String = "file",
    ) : Message()
    class FileChunk(val id: String, val seq: Long, val data: ByteArray) : Message() {
        override fun equals(other: Any?) =
            other is FileChunk && other.id == id && other.seq == seq && other.data.contentEquals(data)
        override fun hashCode() = 31 * (31 * id.hashCode() + seq.hashCode()) + data.contentHashCode()
        override fun toString() = "FileChunk(id=$id, seq=$seq, ${data.size} bytes)"
    }
    data class FileEnd(val id: String) : Message()
    data class FileError(val id: String, val reason: String) : Message()
    data class Unknown(val type: String) : Message()

    fun toJson(): String = when (this) {
        is Hello -> JSONObject().put("t", "hello").put("device", device)
        is Clip -> JSONObject().put("t", "clip").put("id", id).put("text", text).put("ts", ts)
            .apply { html?.let { put("html", it) }; if (sensitive) put("sensitive", true) }
        Ping -> JSONObject().put("t", "ping")
        is Offer -> JSONObject().put("t", "offer").put("id", id).put("name", name).put("mime", mime)
            .put("size", size).put("ts", ts)
        is Offers -> JSONObject().put("t", "offers").put("ts", ts).put("items", org.json.JSONArray().apply {
            items.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("mime", it.mime).put("size", it.size)) }
        })
        OfferClear -> JSONObject().put("t", "offer-clear")
        is Fetch -> JSONObject().put("t", "fetch").put("id", id)
        is FileStart -> JSONObject().put("t", "file-start").put("id", id).put("name", name).put("mime", mime)
            .put("size", size).put("sha256", sha256)
            .apply { if (purpose != "file") put("purpose", purpose) }
        is FileChunk -> JSONObject().put("t", "file-chunk").put("id", id).put("seq", seq)
            .put("data", Base64.getEncoder().encodeToString(data))
        is FileEnd -> JSONObject().put("t", "file-end").put("id", id)
        is FileError -> JSONObject().put("t", "file-error").put("id", id).put("reason", reason)
        is Unknown -> JSONObject().put("t", type)
    }.toString()

    companion object {
        fun parse(json: String): Message {
            val o = try { JSONObject(json) } catch (e: JSONException) {
                throw IllegalArgumentException("invalid json", e)
            }
            return try {
                when (val t = o.getString("t")) {
                    "hello" -> Hello(o.getString("device"))
                    "clip" -> Clip(o.getString("id"), o.getString("text"), o.getLong("ts"), o.optString("html").takeIf { o.has("html") },
                        o.optBoolean("sensitive", false))
                    "ping" -> Ping
                    "offer" -> Offer(o.getString("id"), o.getString("name"), o.getString("mime"), o.getLong("size"), o.getLong("ts"))
                    "offers" -> {
                        val arr = o.getJSONArray("items")
                        Offers((0 until arr.length()).map { i ->
                            val it = arr.getJSONObject(i)
                            OfferItem(it.getString("id"), it.getString("name"), it.getString("mime"), it.getLong("size"))
                        }, o.getLong("ts"))
                    }
                    "offer-clear" -> OfferClear
                    "fetch" -> Fetch(o.getString("id"))
                    "file-start" -> FileStart(o.getString("id"), o.getString("name"), o.getString("mime"),
                        o.getLong("size"), o.getString("sha256"), o.optString("purpose", "file"))
                    "file-chunk" -> FileChunk(o.getString("id"), o.getLong("seq"), Base64.getDecoder().decode(o.getString("data")))
                    "file-end" -> FileEnd(o.getString("id"))
                    "file-error" -> FileError(o.getString("id"), o.getString("reason"))
                    else -> Unknown(t)
                }
            } catch (e: JSONException) {
                throw IllegalArgumentException("invalid message", e)
            }
        }
    }
}
