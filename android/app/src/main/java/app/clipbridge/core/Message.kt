package app.clipbridge.core

import org.json.JSONException
import org.json.JSONObject

/** Plaintext JSON messages, discriminated by "t". Same wire format as the Mac. */
sealed class Message {
    data class Hello(val device: String) : Message()
    data class Clip(val id: String, val text: String, val ts: Long) : Message()
    data object Ping : Message()

    fun toJson(): String = when (this) {
        is Hello -> JSONObject().put("t", "hello").put("device", device)
        is Clip -> JSONObject().put("t", "clip").put("id", id).put("text", text).put("ts", ts)
        Ping -> JSONObject().put("t", "ping")
    }.toString()

    companion object {
        fun parse(json: String): Message {
            val obj = try { JSONObject(json) } catch (e: JSONException) {
                throw IllegalArgumentException("invalid json", e)
            }
            return try {
                when (obj.getString("t")) {
                    "hello" -> Hello(obj.getString("device"))
                    "clip" -> Clip(obj.getString("id"), obj.getString("text"), obj.getLong("ts"))
                    "ping" -> Ping
                    else -> throw IllegalArgumentException("unknown type")
                }
            } catch (e: JSONException) {
                throw IllegalArgumentException("invalid message", e)
            }
        }
    }
}
