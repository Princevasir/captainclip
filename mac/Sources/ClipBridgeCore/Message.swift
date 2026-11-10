import Foundation

public enum MessageError: Error {
    case invalid
}

/// One file in a several-files offer.
public struct OfferItem: Equatable {
    public let id: String, name: String, mime: String, size: Int64
    public init(id: String, name: String, mime: String, size: Int64) {
        self.id = id; self.name = name; self.mime = mime; self.size = size
    }
}

/// Plaintext JSON messages, discriminated by key "t".
/// Unknown types decode to `.unknown` and are ignored, so newer messages never break a connection.
public enum Message: Equatable {
    case hello(device: String)
    case clip(id: String, text: String, ts: Int64, html: String? = nil)
    case ping
    // v2 files
    case offer(id: String, name: String, mime: String, size: Int64, ts: Int64)
    /// Several files copied together (⌘C on 2–20 files in Finder).
    case offers(items: [OfferItem], ts: Int64)
    case offerClear
    case fetch(id: String)
    case fileStart(id: String, name: String, mime: String, size: Int64, sha256: String, purpose: String = "file")
    case fileChunk(id: String, seq: Int64, data: Data)
    case fileEnd(id: String)
    case fileError(id: String, reason: String)
    case unknown(type: String)

    public func encoded() throws -> Data {
        let object: [String: Any]
        switch self {
        case .hello(let device): object = ["t": "hello", "device": device]
        case .clip(let id, let text, let ts, let html):
            var o: [String: Any] = ["t": "clip", "id": id, "text": text, "ts": ts]
            if let html { o["html"] = html }
            object = o
        case .ping: object = ["t": "ping"]
        case .offer(let id, let name, let mime, let size, let ts):
            object = ["t": "offer", "id": id, "name": name, "mime": mime, "size": size, "ts": ts]
        case .offers(let items, let ts):
            object = ["t": "offers", "ts": ts,
                      "items": items.map { ["id": $0.id, "name": $0.name, "mime": $0.mime, "size": $0.size] }]
        case .offerClear: object = ["t": "offer-clear"]
        case .fetch(let id): object = ["t": "fetch", "id": id]
        case .fileStart(let id, let name, let mime, let size, let sha256, let purpose):
            var o: [String: Any] = ["t": "file-start", "id": id, "name": name, "mime": mime, "size": size, "sha256": sha256]
            if purpose != "file" { o["purpose"] = purpose }
            object = o
        case .fileChunk(let id, let seq, let data):
            object = ["t": "file-chunk", "id": id, "seq": seq, "data": data.base64EncodedString()]
        case .fileEnd(let id): object = ["t": "file-end", "id": id]
        case .fileError(let id, let reason): object = ["t": "file-error", "id": id, "reason": reason]
        case .unknown(let type): object = ["t": type]
        }
        return try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
    }

    public static func decode(_ data: Data) throws -> Message {
        guard let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = o["t"] as? String
        else { throw MessageError.invalid }
        func str(_ k: String) throws -> String {
            guard let v = o[k] as? String else { throw MessageError.invalid }
            return v
        }
        func int(_ k: String) throws -> Int64 {
            guard let v = o[k] as? NSNumber else { throw MessageError.invalid }
            return v.int64Value
        }
        switch type {
        case "hello": return .hello(device: try str("device"))
        case "clip": return .clip(id: try str("id"), text: try str("text"), ts: try int("ts"), html: o["html"] as? String)
        case "ping": return .ping
        case "offer":
            return .offer(id: try str("id"), name: try str("name"), mime: try str("mime"),
                          size: try int("size"), ts: try int("ts"))
        case "offers":
            guard let raw = o["items"] as? [[String: Any]] else { throw MessageError.invalid }
            let items = try raw.map { item -> OfferItem in
                guard let id = item["id"] as? String, let name = item["name"] as? String,
                      let mime = item["mime"] as? String, let size = (item["size"] as? NSNumber)?.int64Value
                else { throw MessageError.invalid }
                return OfferItem(id: id, name: name, mime: mime, size: size)
            }
            return .offers(items: items, ts: try int("ts"))
        case "offer-clear": return .offerClear
        case "fetch": return .fetch(id: try str("id"))
        case "file-start":
            return .fileStart(id: try str("id"), name: try str("name"), mime: try str("mime"),
                              size: try int("size"), sha256: try str("sha256"),
                              purpose: o["purpose"] as? String ?? "file")
        case "file-chunk":
            guard let bytes = Data(base64Encoded: try str("data")) else { throw MessageError.invalid }
            return .fileChunk(id: try str("id"), seq: try int("seq"), data: bytes)
        case "file-end": return .fileEnd(id: try str("id"))
        case "file-error": return .fileError(id: try str("id"), reason: try str("reason"))
        default: return .unknown(type: type)
        }
    }
}
