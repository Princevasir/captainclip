import Foundation

public enum FrameError: Error {
    case tooLarge
    case empty
}

/// Frame = 4-byte big-endian length N (1...maxPayload) followed by N payload bytes.
public enum FrameCodec {
    public static let maxPayload = 1_048_576

    public static func encode(_ payload: Data) throws -> Data {
        guard !payload.isEmpty else { throw FrameError.empty }
        guard payload.count <= maxPayload else { throw FrameError.tooLarge }
        var length = UInt32(payload.count).bigEndian
        return Data(bytes: &length, count: 4) + payload
    }
}

/// Accumulates bytes from a stream and returns complete payloads.
public final class FrameDecoder {
    private var buffer = Data()

    public init() {}

    public func append(_ bytes: Data) throws -> [Data] {
        buffer.append(bytes)
        var payloads: [Data] = []
        while buffer.count >= 4 {
            let header = buffer.prefix(4)
            let length = header.reduce(0) { ($0 << 8) | Int($1) }
            guard length > 0 else { throw FrameError.empty }
            guard length <= FrameCodec.maxPayload else { throw FrameError.tooLarge }
            guard buffer.count >= 4 + length else { break }
            payloads.append(Data(buffer.dropFirst(4).prefix(length)))
            buffer = Data(buffer.dropFirst(4 + length))
        }
        return payloads
    }
}
