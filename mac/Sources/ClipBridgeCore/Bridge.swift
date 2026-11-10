import Foundation

/// Wires the pasteboard watcher to the server: text sync (v1) plus file offers, fetches and incoming files (v2).
/// All public callbacks are delivered on the main queue.
public final class Bridge {
    public var paused = false
    public var onStatus: ((Server.Status) -> Void)?
    public var onReceived: ((String) -> Void)?
    /// A file from the phone landed in Downloads (and is on the pasteboard).
    public var onFileReceived: ((URL) -> Void)?
    /// The file currently offered to the phone changed (nil = none).
    public var onOfferChanged: ((String?) -> Void)?

    private let server: Server
    private let watcher: PasteboardWatcher
    private let offers: OfferStore
    private let downloads: URL
    private let loopGuard = LoopGuard()
    private let files = DispatchQueue(label: "clipbridge.bridge.files")
    private var receivers: [String: FileReceiver] = [:]  // on `files`

    public init(server: Server, watcher: PasteboardWatcher, offers: OfferStore = OfferStore(),
                downloads: URL = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]) {
        self.server = server
        self.watcher = watcher
        self.offers = offers
        self.downloads = downloads
    }

    public func start() throws {
        watcher.onText = { [weak self] text in
            guard let self, !self.paused else { return }
            self.clearOffer()
            guard TextLimit.fits(text), self.loopGuard.shouldSend(text) else { return }
            if self.server.send(text: text) { self.loopGuard.markSent(text) }
        }
        watcher.onFile = { [weak self] url in
            guard let self, !self.paused, let offer = try? self.offers.make(url: url) else { return }
            self.server.cancelStream()  // stop sending the previous offer, if a paste was in progress
            self.server.send(message: offer)  // just name/type/size; content waits for a fetch
            self.onOfferChanged?(url.lastPathComponent)
        }
        watcher.onIgnoredFiles = { [weak self] in self?.clearOffer() }
        server.onClip = { [weak self] text in
            DispatchQueue.main.async {
                guard let self, !self.paused else { return }
                self.clearOffer()  // the Mac clipboard no longer holds the offered file
                self.loopGuard.markReceived(text)
                self.watcher.write(text)
                self.onReceived?(text)
            }
        }
        server.onMessage = { [weak self] message in
            self?.files.async { self?.handleFileMessage(message) }
        }
        server.onStatus = { [weak self] status in
            // A new connection (or none) means any half upload from the previous one is dead.
            self?.files.async { self?.abortIncoming() }
            DispatchQueue.main.async {
                guard let self else { return }
                if case .connected = status { self.sendCurrentClipboard() }
                self.onStatus?(status)
            }
        }
        try server.start()
        watcher.start()
    }

    public func stop() {
        watcher.stop()
        server.stop()
        files.sync { abortIncoming() }
    }

    private func clearOffer() {
        guard offers.current != nil else { return }
        offers.clear()
        server.cancelStream()
        server.send(message: .offerClear)
        onOfferChanged?(nil)
    }

    /// A phone just connected: give it what's on the Mac clipboard (an offer, or text it may have missed).
    private func sendCurrentClipboard() {
        guard !paused else { return }
        if let offer = offers.current {
            server.send(message: offer)
            return
        }
        guard let text = watcher.currentText(), TextLimit.fits(text), !loopGuard.wasReceived(text) else { return }
        if server.send(text: text) { loopGuard.markSent(text) }
    }

    // MARK: files (on `files` queue)

    private func handleFileMessage(_ message: Message) {
        switch message {
        case .fetch(let id):
            serve(id: id)
        case .fileStart(let id, _, _, _, _):
            receivers[id]?.abort()
            do { receivers[id] = try FileReceiver(start: message, directory: downloads) }
            catch { server.send(message: .fileError(id: id, reason: error as? FileTransferError == .tooLarge ? "too-large" : "io")) }
        case .fileChunk(let id, _, _), .fileEnd(let id):
            guard let receiver = receivers[id] else { return }
            do {
                if let url = try receiver.accept(message) {
                    receivers[id] = nil
                    DispatchQueue.main.async {
                        self.clearOffer()
                        self.watcher.writeFile(url)
                        self.onFileReceived?(url)
                    }
                }
            } catch {
                receivers[id] = nil
                server.send(message: .fileError(id: id, reason: "bad-data"))
            }
        case .fileError(let id, _):
            receivers.removeValue(forKey: id)?.abort()
        default:
            break
        }
    }

    private func serve(id: String) {
        guard !paused else { server.send(message: .fileError(id: id, reason: "expired")); return }
        switch offers.entry(id: id) {
        case .failure(let error):
            server.send(message: .fileError(id: id, reason: error == .changed ? "changed" : "expired"))
        case .success(let entry):
            guard let sender = try? FileSender(id: id, url: entry.url, name: entry.name, mime: entry.mime)
            else { server.send(message: .fileError(id: id, reason: "io")); return }
            server.sendFile(start: sender.start, stream: sender.stream())
        }
    }

    private func abortIncoming() {
        receivers.values.forEach { $0.abort() }
        receivers.removeAll()
    }
}
