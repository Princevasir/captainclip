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
    data class Clip(val id: String, val text: String, val ts: Long) : Message()
    data object Ping : Message()

    // v2 files
    data class Offer(val id: String, val name: String, val mime: String, val size: Long, val ts: Long) : Message()
    data object OfferClear : Message()
    data class Fetch(val id: String) : Message()
    data class FileStart(val id: String, val name: String, val mime: String, val size: Long, val sha256: String) : Message()
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
        Ping -> JSONObject().put("t", "ping")
        is Offer -> JSONObject().put("t", "offer").put("id", id).put("name", name).put("mime", mime)
            .put("size", size).put("ts", ts)
        OfferClear -> JSONObject().put("t", "offer-clear")
        is Fetch -> JSONObject().put("t", "fetch").put("id", id)
        is FileStart -> JSONObject().put("t", "file-start").put("id", id).put("name", name).put("mime", mime)
            .put("size", size).put("sha256", sha256)
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
                    "clip" -> Clip(o.getString("id"), o.getString("text"), o.getLong("ts"))
                    "ping" -> Ping
                    "offer" -> Offer(o.getString("id"), o.getString("name"), o.getString("mime"), o.getLong("size"), o.getLong("ts"))
                    "offer-clear" -> OfferClear
                    "fetch" -> Fetch(o.getString("id"))
                    "file-start" -> FileStart(o.getString("id"), o.getString("name"), o.getString("mime"),
                        o.getLong("size"), o.getString("sha256"))
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
