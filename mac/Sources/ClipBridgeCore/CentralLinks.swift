/// One Bluetooth link per nearby device (central). A device subscribing never drops another device's link —
/// only the encrypted handshake decides which phone is the active session (see `Server`).
public final class CentralLinks<Key: Hashable, Link: AnyObject> {
    private var links: [Key: Link] = [:]
    private let make: (Key) -> Link
    private let close: (Link) -> Void

    public init(make: @escaping (Key) -> Link, close: @escaping (Link) -> Void) {
        self.make = make
        self.close = close
    }

    /// A device subscribed: gives it a fresh link (replacing only its own older one).
    @discardableResult
    public func subscribed(_ key: Key) -> Link {
        if let old = links[key] { close(old) }
        let link = make(key)
        links[key] = link
        return link
    }

    public func link(for key: Key) -> Link? { links[key] }

    public func unsubscribed(_ key: Key) {
        if let link = links.removeValue(forKey: key) { close(link) }
    }

    public func closeAll() {
        let all = Array(links.values)
        links.removeAll()
        all.forEach(close)
    }

    public var all: [Link] { Array(links.values) }
}
