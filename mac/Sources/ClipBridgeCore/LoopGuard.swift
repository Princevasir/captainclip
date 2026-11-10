import Foundation

/// Stops clipboard ping-pong: a local change equal to the last text received
/// from (or sent to) the other device is not sent again.
public final class LoopGuard {
    private let lock = NSLock()
    private var lastReceived: String?
    private var lastSent: String?

    public init() {}

    public func markReceived(_ text: String) { lock.withLock { lastReceived = text } }
    public func markSent(_ text: String) { lock.withLock { lastSent = text } }

    public func shouldSend(_ text: String) -> Bool {
        lock.withLock { !text.isEmpty && text != lastReceived && text != lastSent }
    }
}
