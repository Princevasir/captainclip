import AppKit
import Foundation

/// Wires the pasteboard watcher to the server: text sync (v1) plus file offers, fetches and incoming files (v2).
/// All public callbacks are delivered on the main queue.
public final class Bridge {
    /// Paused until resumed (no end time).
    public var paused = false
    /// Paused until this time (a timed pause); nil when none.
    public var pausedUntil: Date?
    /// How long a one-time code from the phone stays on the Mac clipboard (if nothing newer is copied).
    public var sensitiveClearDelay: TimeInterval = 60
    public var now: () -> Date = Date.init
    public var isPaused: Bool { PauseWindow.isActive(manual: paused, until: pausedUntil, now: now()) }
    public var onStatus: ((Server.Status) -> Void)?
    public var onReceived: ((String) -> Void)?
    /// A file from the phone landed in Downloads (and is on the pasteboard).
    public var onFileReceived: ((URL) -> Void)?
    /// An image from the phone was put on the Mac clipboard (byte count).
    public var onImageReceived: ((Int) -> Void)?
    /// The file currently offered to the phone changed (nil = none).
    public var onOfferChanged: ((String?) -> Void)?

    private let server: Server
    private let watcher: PasteboardWatcher
    private let offers: OfferStore
    private let downloads: URL
    private let imageCache: URL
    private let loopGuard = LoopGuard()
    private let files = DispatchQueue(label: "clipbridge.bridge.files")
    private var receivers: [String: FileReceiver] = [:]  // on `files`
    private var clipboardImageIds: Set<String> = []      // on `files`: transfers meant for the clipboard
    private var imageMimes: [String: String] = [:]       // on `files`: their image types

    public init(server: Server, watcher: PasteboardWatcher, offers: OfferStore = OfferStore(),
                downloads: URL = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0],
                imageCache: URL = ClipboardImage.defaultCache) {
        self.server = server
        self.watcher = watcher
        self.offers = offers
        self.downloads = downloads
        self.imageCache = imageCache
    }

    public func start() throws {
        watcher.onText = { [weak self] text in
            guard let self, !self.isPaused else { return }
            self.clearOffer()
            guard TextLimit.fits(text), self.loopGuard.shouldSend(text) else { return }
            let html = RichText.fitting(text: text, html: self.watcher.currentHTML())
            if self.server.send(text: text, html: html) { self.loopGuard.markSent(text) }
        }
        watcher.onFile = { [weak self] url in
            guard let self, !self.isPaused, let offer = try? self.offers.make(url: url) else { return }
            self.server.cancelStream()  // stop sending the previous offer, if a paste was in progress
            self.server.send(message: offer)  // just name/type/size; content waits for a fetch
            self.onOfferChanged?(url.lastPathComponent)
        }
        watcher.onFiles = { [weak self] urls in
            guard let self, !self.isPaused, let offer = try? self.offers.makeSet(urls: urls) else { return }
            self.server.cancelStream()
            self.server.send(message: offer)  // names/types/sizes only; each file waits for its own fetch
            self.onOfferChanged?("\(urls.count) files")
        }
        watcher.onImage = { [weak self] png in
            // Offered like a file: only name/type/size go to the phone until it pastes.
            guard let self, !self.isPaused, let url = try? ClipboardImage.save(png, in: self.imageCache) else { return }
            self.watcher.onFile?(url)
        }
        watcher.onIgnoredFiles = { [weak self] in self?.clearOffer() }
        server.onRichClip = { [weak self] text, html, sensitive in
            DispatchQueue.main.async {
                guard let self, !self.isPaused else { return }
                self.clearOffer()  // the Mac clipboard no longer holds the offered file
                self.loopGuard.markReceived(text)
                if sensitive {
                    // Concealed, never reported onward or announced; cleared later unless something newer was copied.
                    let written = self.watcher.write(text, html: html, concealed: true)
                    DispatchQueue.main.asyncAfter(deadline: .now() + self.sensitiveClearDelay) { [weak self] in
                        guard let self,
                              ClearAfter.shouldClear(writtenChangeCount: written, currentChangeCount: self.watcher.changeCount)
                        else { return }
                        self.watcher.clear()
                    }
                    return
                }
                if let html { self.watcher.write(text, html: html) } else { self.watcher.write(text) }
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

    /// A pause ended: clear it and give a connected phone what was copied meanwhile.
    public func resumed() {
        paused = false
        pausedUntil = nil
        sendCurrentClipboard()
    }

    /// A phone just connected: give it what's on the Mac clipboard (an offer, or text it may have missed).
    private func sendCurrentClipboard() {
        guard !isPaused else { return }
        if let offer = offers.current {
            server.send(message: offer)
            return
        }
        guard let text = watcher.currentText(), TextLimit.fits(text), !loopGuard.wasReceived(text) else { return }
        if server.send(text: text, html: RichText.fitting(text: text, html: watcher.currentHTML())) { loopGuard.markSent(text) }
    }

    // MARK: files (on `files` queue)

    private func handleFileMessage(_ message: Message) {
        switch message {
        case .fetch(let id):
            serve(id: id)
        case .fileStart(let id, _, let mime, let size, _, let purpose):
            receivers[id]?.abort()
            if size > FileLimit.bluetoothMaxBytes, server.activeIsBluetooth {
                server.send(message: .fileError(id: id, reason: "too-large-bluetooth"))
                return
            }
            let toClipboard = purpose == "clipboard" && mime.hasPrefix("image/")
            if toClipboard { clipboardImageIds.insert(id); imageMimes[id] = mime } else { clipboardImageIds.remove(id) }
            let dir = toClipboard ? imageCache.appendingPathComponent("incoming", isDirectory: true) : downloads
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            do { receivers[id] = try FileReceiver(start: message, directory: dir) }
            catch { server.send(message: .fileError(id: id, reason: error as? FileTransferError == .tooLarge ? "too-large" : "io")) }
        case .fileChunk(let id, _, _), .fileEnd(let id):
            guard let receiver = receivers[id] else { return }
            let mime = imageMimes[id] ?? "image/png"
            do {
                if let url = try receiver.accept(message) {
                    receivers[id] = nil
                    if clipboardImageIds.remove(id) != nil {
                        // Phone image → Mac clipboard as an image; the temp file is not kept.
                        // Kept in its original format (JPEG stays JPEG): no decoding, so big photos can't blow up memory.
                        let data = (try? Data(contentsOf: url, options: .mappedIfSafe)) ?? Data()
                        try? FileManager.default.removeItem(at: url)
                        if let type = ClipboardImage.pasteboardType(forMime: mime), !data.isEmpty {
                            DispatchQueue.main.async {
                                self.clearOffer()
                                self.watcher.writeImage(data, type: type)
                                self.onImageReceived?(data.count)
                            }
                        }
                    } else {
                        DispatchQueue.main.async {
                            self.clearOffer()
                            self.watcher.writeFile(url)
                            self.onFileReceived?(url)
                        }
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
        guard !isPaused else { server.send(message: .fileError(id: id, reason: "expired")); return }
        switch offers.entry(id: id) {
        case .failure(let error):
            bleLog.info("serve \(id.prefix(8), privacy: .public) refused \(error == .changed ? "changed" : "expired", privacy: .public)")
            server.send(message: .fileError(id: id, reason: error == .changed ? "changed" : "expired"))
        case .success(let entry):
            let size = (try? entry.url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            if size > FileLimit.bluetoothMaxBytes, server.activeIsBluetooth {
                server.send(message: .fileError(id: id, reason: "too-large-bluetooth"))
                return
            }
            guard let sender = try? FileSender(id: id, url: entry.url, name: entry.name, mime: entry.mime)
            else { server.send(message: .fileError(id: id, reason: "io")); return }
            bleLog.info("serve \(id.prefix(8), privacy: .public) size=\(size)")
            server.sendFile(start: sender.start, stream: sender.stream()) { ok in
                bleLog.info("served \(id.prefix(8), privacy: .public) ok=\(ok)")
            }
        }
    }

    private func abortIncoming() {
        receivers.values.forEach { $0.abort() }
        receivers.removeAll()
    }
}
