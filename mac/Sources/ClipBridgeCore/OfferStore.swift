import Foundation
import UniformTypeIdentifiers

public enum OfferError: Error, Equatable {
    case expired, changed
}

/// The one file currently offered to the phone. Content is only served for the current offer,
/// within 10 minutes, and only if the file hasn't changed since it was copied.
public final class OfferStore {
    public static let lifetime: TimeInterval = 10 * 60

    private struct Entry {
        let id: String
        let url: URL
        let size: Int64
        let modified: Date
        let created: Date
        let message: Message
    }

    private let now: () -> Date
    private let lock = NSLock()
    private var entry: Entry?

    public init(now: @escaping () -> Date = Date.init) {
        self.now = now
    }

    public var current: Message? { lock.withLock { entry.flatMap { isLive($0) ? $0.message : nil } } }

    /// Creates (and becomes) the current offer for `url`.
    public func make(url: URL) throws -> Message {
        let (size, modified) = try Self.stat(url)
        let id = UUID().uuidString
        let mime = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
        let created = now()
        let message = Message.offer(id: id, name: url.lastPathComponent, mime: mime, size: size,
                                    ts: Int64(created.timeIntervalSince1970 * 1000))
        lock.withLock { entry = Entry(id: id, url: url, size: size, modified: modified, created: created, message: message) }
        return message
    }

    public func clear() { lock.withLock { entry = nil } }

    public func lookup(id: String) -> Result<URL, OfferError> {
        lock.withLock {
            guard let entry, entry.id == id, isLive(entry) else { return .failure(.expired) }
            guard let (size, modified) = try? Self.stat(entry.url), size == entry.size, modified == entry.modified
            else { return .failure(.changed) }
            return .success(entry.url)
        }
    }

    private func isLive(_ e: Entry) -> Bool { now().timeIntervalSince(e.created) < Self.lifetime }

    private static func stat(_ url: URL) throws -> (Int64, Date) {
        let attrs = try FileManager.default.attributesOfItem(atPath: url.path)
        return ((attrs[.size] as? NSNumber)?.int64Value ?? -1, attrs[.modificationDate] as? Date ?? .distantPast)
    }
}
