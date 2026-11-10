package app.clipbridge.core

import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/** Parsed from the Mac's QR: clipbridge://pair?v=1&id=..&key=<base64url>&host=..&port=.. */
class PairingInfo(val macId: String, val key: ByteArray, val host: String, val port: Int) {
    /** Bonjour/NSD instance name the Mac advertises. */
    val serviceName: String get() = "ClipBridge-" + macId.take(6)

    val uri: String
        get() = "clipbridge://pair?v=1&id=$macId&key=" +
            Base64.getUrlEncoder().withoutPadding().encodeToString(key) + "&host=$host&port=$port"

    companion object {
        fun parse(text: String): PairingInfo? {
            val uri = try { URI(text) } catch (_: Exception) { return null }
            if (uri.scheme != "clipbridge" || uri.host != "pair") return null
            val params = (uri.rawQuery ?: return null).split("&").mapNotNull {
                val parts = it.split("=", limit = 2)
                if (parts.size == 2) parts[0] to URLDecoder.decode(parts[1], "UTF-8") else null
            }.toMap()
            if (params["v"] != "1") return null
            val macId = params["id"]?.takeIf { it.isNotEmpty() } ?: return null
            val key = try { Base64.getUrlDecoder().decode(params["key"] ?: return null) } catch (_: Exception) { return null }
            if (key.size != 32) return null
            val host = params["host"]?.takeIf { it.isNotEmpty() } ?: return null
            val port = params["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            return PairingInfo(macId, key, host, port)
        }
    }
}
