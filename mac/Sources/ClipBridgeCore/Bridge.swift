import Foundation

/// Wires the pasteboard watcher to the server, with loop protection and a pause switch.
/// All callbacks are delivered on the main queue.
public final class Bridge {
    public var paused = false
    public var onStatus: ((Server.Status) -> Void)?
    public var onReceived: ((String) -> Void)?

    private let server: Server
    private let watcher: PasteboardWatcher
    private let loopGuard = LoopGuard()

    public init(server: Server, watcher: PasteboardWatcher) {
        self.server = server
        self.watcher = watcher
    }

    public func start() throws {
        watcher.onText = { [weak self] text in
            guard let self, !self.paused, TextLimit.fits(text), self.loopGuard.shouldSend(text) else { return }
            if self.server.send(text: text) { self.loopGuard.markSent(text) }
        }
        server.onClip = { [weak self] text in
            DispatchQueue.main.async {
                guard let self, !self.paused else { return }
                self.loopGuard.markReceived(text)
                self.watcher.write(text)
                self.onReceived?(text)
            }
        }
        server.onStatus = { [weak self] status in
            DispatchQueue.main.async {
                guard let self else { return }
                if case .connected = status { self.sendCurrentClipboard() }
                self.onStatus?(status)
            }
        }
        try server.start()
        watcher.start()
    }

    /// A phone just connected: give it the Mac's current clipboard, which it may have missed
    /// while away — unless that text came from the phone in the first place.
    private func sendCurrentClipboard() {
        guard !paused, let text = watcher.currentText(), TextLimit.fits(text), !loopGuard.wasReceived(text)
        else { return }
        if server.send(text: text) { loopGuard.markSent(text) }
    }

    public func stop() {
        watcher.stop()
        server.stop()
    }
}
