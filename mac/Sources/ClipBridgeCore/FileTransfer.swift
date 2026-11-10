import CryptoKit
import Foundation

public enum FileLimit {
    /// 25 MB per file.
    public static let maxBytes = 26_214_400
    /// Raw bytes per chunk (≈256 KiB once base64-encoded), well under the 1 MiB frame cap.
    public static let chunkBytes = 196_608
}

public enum FileTransferError: Error, Equatable {
    case tooLarge, notARegularFile, outOfOrder, tooMuchData, checksumMismatch, sizeMismatch, wrongTransfer, io
}

/// Reads a file lazily in chunks. The SHA-256 is computed by streaming once before `start` is sent.
public struct FileSender {
    public let id: String
    public let url: URL
    public let start: Message
    public var end: Message { .fileEnd(id: id) }

    public init(id: String, url: URL, name: String, mime: String) throws {
        let attrs = try FileManager.default.attributesOfItem(atPath: url.path)
        guard attrs[.type] as? FileAttributeType == .typeRegular else { throw FileTransferError.notARegularFile }
        let size = (attrs[.size] as? NSNumber)?.int64Value ?? 0
        guard size <= FileLimit.maxBytes else { throw FileTransferError.tooLarge }
        self.id = id
        self.url = url
        self.start = .fileStart(id: id, name: name, mime: mime, size: size, sha256: try Self.sha256(of: url))
    }

    public func chunks() -> AnySequence<Message> {
        let id = self.id, url = self.url
        return AnySequence { () -> AnyIterator<Message> in
            let handle = try? FileHandle(forReadingFrom: url)
            var seq: Int64 = 0
            return AnyIterator {
                guard let handle, let data = try? handle.read(upToCount: FileLimit.chunkBytes), !data.isEmpty else {
                    try? handle?.close()
                    return nil
                }
                defer { seq += 1 }
                return .fileChunk(id: id, seq: seq, data: data)
            }
        }
    }

    public static func sha256(of url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hasher = SHA256()
        while let data = try handle.read(upToCount: 1 << 20), !data.isEmpty { hasher.update(data: data) }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }
}

/// Writes incoming chunks straight to `<name>.clipbridge-part`, then verifies size + SHA-256 and moves the
/// file to a unique name in `directory`. Any problem deletes the temp file.
public final class FileReceiver {
    public let id: String
    public let name: String
    private let size: Int64
    private let sha256: String
    private let directory: URL
    private let temp: URL
    private var handle: FileHandle?
    private var hasher = SHA256()
    private var written: Int64 = 0
    private var nextSeq: Int64 = 0

    public init(start: Message, directory: URL) throws {
        guard case .fileStart(let id, let name, _, let size, let sha256) = start else { throw FileTransferError.wrongTransfer }
        guard size >= 0, size <= FileLimit.maxBytes else { throw FileTransferError.tooLarge }
        self.id = id
        self.name = Self.safeName(name)
        self.size = size
        self.sha256 = sha256.lowercased()
        self.directory = directory
        self.temp = directory.appendingPathComponent(self.name + ".clipbridge-part")
        guard FileManager.default.createFile(atPath: temp.path, contents: nil) else { throw FileTransferError.io }
        handle = try FileHandle(forWritingTo: temp)
    }

    /// Returns the final file URL once `file-end` verifies; nil for chunks.
    public func accept(_ message: Message) throws -> URL? {
        do {
            switch message {
            case .fileChunk(let id, let seq, let data):
                guard id == self.id else { throw FileTransferError.wrongTransfer }
                guard seq == nextSeq else { throw FileTransferError.outOfOrder }
                guard written + Int64(data.count) <= size else { throw FileTransferError.tooMuchData }
                try handle?.write(contentsOf: data)
                hasher.update(data: data)
                written += Int64(data.count)
                nextSeq += 1
                return nil
            case .fileEnd(let id):
                guard id == self.id else { throw FileTransferError.wrongTransfer }
                guard written == size else { throw FileTransferError.sizeMismatch }
                let digest = hasher.finalize().map { String(format: "%02x", $0) }.joined()
                guard digest == sha256 else { throw FileTransferError.checksumMismatch }
                try handle?.close()
                handle = nil
                let final = uniqueURL()
                try FileManager.default.moveItem(at: temp, to: final)
                return final
            default:
                throw FileTransferError.wrongTransfer
            }
        } catch {
            abort()
            throw error
        }
    }

    public func abort() {
        try? handle?.close()
        handle = nil
        try? FileManager.default.removeItem(at: temp)
    }

    private func uniqueURL() -> URL {
        let base = (name as NSString).deletingPathExtension
        let ext = (name as NSString).pathExtension
        var candidate = directory.appendingPathComponent(name)
        var n = 1
        while FileManager.default.fileExists(atPath: candidate.path) {
            let numbered = ext.isEmpty ? "\(base) (\(n))" : "\(base) (\(n)).\(ext)"
            candidate = directory.appendingPathComponent(numbered)
            n += 1
        }
        return candidate
    }

    /// Keeps only the last path component, no leading dots, no control characters.
    static func safeName(_ raw: String) -> String {
        let last = raw.replacingOccurrences(of: "\\", with: "/").split(separator: "/").last.map(String.init) ?? ""
        var cleaned = String(last.unicodeScalars.filter { !CharacterSet.controlCharacters.contains($0) && $0 != ":" })
        while cleaned.hasPrefix(".") { cleaned.removeFirst() }
        return cleaned.isEmpty ? "file" : String(cleaned.prefix(200))
    }
}
