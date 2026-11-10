import Foundation

public enum MessageError: Error {
    case invalid
}

/// Plaintext JSON messages, discriminated by key "t".
public enum Message: Equatable {
    case hello(device: String)
    case clip(id: String, text: String, ts: Int64)
    case ping

    public func encoded() throws -> Data {
        let object: [String: Any]
        switch self {
        case .hello(let device): object = ["t": "hello", "device": device]
        case .clip(let id, let text, let ts): object = ["t": "clip", "id": id, "text": text, "ts": ts]
        case .ping: object = ["t": "ping"]
        }
        return try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
    }

    public static func decode(_ data: Data) throws -> Message {
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = object["t"] as? String
        else { throw MessageError.invalid }
        switch type {
        case "hello":
            guard let device = object["device"] as? String else { throw MessageError.invalid }
            return .hello(device: device)
        case "clip":
            guard let id = object["id"] as? String,
                  let text = object["text"] as? String,
                  let ts = (object["ts"] as? NSNumber)?.int64Value
            else { throw MessageError.invalid }
            return .clip(id: id, text: text, ts: ts)
        case "ping":
            return .ping
        default:
            throw MessageError.invalid
        }
    }
}
