import AppKit

/// Polls the pasteboard's changeCount and reports new plain text.
/// Skips items password managers mark as concealed/transient (nspasteboard.org convention).
/// Runs on the main run loop.
public final class PasteboardWatcher {
    public static let skippedTypes: Set<String> = [
        "org.nspasteboard.ConcealedType",
        "org.nspasteboard.TransientType",
    ]

    public var onText: ((String) -> Void)?
    /// Exactly one regular file ≤ 25 MB was copied (e.g. ⌘C in Finder).
    public var onFile: ((URL) -> Void)?
    /// 2–20 regular files copied together (each ≤ 25 MB, ≤ 100 MB in total).
    public var onFiles: (([URL]) -> Void)?
    /// An image (not a file) was copied, e.g. a ⌃⇧⌘4 screenshot. PNG bytes.
    public var onImage: ((Data) -> Void)?
    /// Files were copied but can't be offered (several files, a folder, or too large).
    public var onIgnoredFiles: (() -> Void)?

    private let pasteboard: NSPasteboard
    private let interval: TimeInterval
    private var timer: Timer?
    private var lastChangeCount: Int

    public init(pasteboard: NSPasteboard = .general, interval: TimeInterval = 0.5) {
        self.pasteboard = pasteboard
        self.interval = interval
        self.lastChangeCount = pasteboard.changeCount
    }

    public static func shouldSkip(types: [String]) -> Bool {
        types.contains { skippedTypes.contains($0) }
    }

    public func start() {
        lastChangeCount = pasteboard.changeCount
        let timer = Timer(timeInterval: interval, repeats: true) { [weak self] _ in self?.poll() }
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer
    }

    public func stop() {
        timer?.invalidate()
        timer = nil
    }

    /// Puts text on the pasteboard without reporting it back through onText.
    public func write(_ text: String) {
        pasteboard.clearContents()
        pasteboard.setString(text, forType: .string)
        lastChangeCount = pasteboard.changeCount
    }

    /// Puts image bytes on the pasteboard under their own type (no re-encoding), without reporting them back.
    public func writeImage(_ data: Data, type: NSPasteboard.PasteboardType) {
        pasteboard.clearContents()
        pasteboard.setData(data, forType: type)
        lastChangeCount = pasteboard.changeCount
    }

    /// Puts a file on the pasteboard (for ⌘V in Finder/Mail) without reporting it back through onFile.
    public func writeFile(_ url: URL) {
        pasteboard.clearContents()
        pasteboard.writeObjects([url as NSURL])
        lastChangeCount = pasteboard.changeCount
    }

    /// HTML of the current copy (nil for plain, concealed or file copies).
    public func currentHTML() -> String? {
        let types = (pasteboard.types ?? []).map(\.rawValue)
        guard !Self.shouldSkip(types: types), fileURLs().isEmpty else { return nil }
        return RichText.html(from: pasteboard)
    }

    /// Puts formatted text on the pasteboard without reporting it back through onText.
    public func write(_ text: String, html: String) {
        RichText.write(text: text, html: html, to: pasteboard)
        lastChangeCount = pasteboard.changeCount
    }

    /// Puts text (and HTML if given) on the pasteboard, marked concealed when `concealed`, without reporting it back.
    /// Returns the change count right after the write.
    @discardableResult
    public func write(_ text: String, html: String?, concealed: Bool) -> Int {
        if let html { RichText.write(text: text, html: html, to: pasteboard) }
        else {
            pasteboard.clearContents()
            pasteboard.setString(text, forType: .string)
        }
        if concealed { pasteboard.setData(Data(), forType: NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType")) }
        lastChangeCount = pasteboard.changeCount
        return lastChangeCount
    }

    public var changeCount: Int { pasteboard.changeCount }

    /// Empties the pasteboard (not reported as a copy).
    public func clear() {
        pasteboard.clearContents()
        lastChangeCount = pasteboard.changeCount
    }

    /// The pasteboard's current plain text, or nil if empty, marked concealed/transient, or a file copy.
    public func currentText() -> String? {
        let types = (pasteboard.types ?? []).map(\.rawValue)
        guard !Self.shouldSkip(types: types), fileURLs().isEmpty,
              let text = pasteboard.string(forType: .string), !text.isEmpty
        else { return nil }
        return text
    }

    /// The single offerable file on the pasteboard, if any.
    public func currentFile() -> URL? {
        let types = (pasteboard.types ?? []).map(\.rawValue)
        guard !Self.shouldSkip(types: types) else { return nil }
        return Self.offerable(fileURLs())
    }

    private func fileURLs() -> [URL] {
        (pasteboard.pasteboardItems ?? []).compactMap { item in
            item.string(forType: .fileURL).flatMap(URL.init(string:)).flatMap { $0.isFileURL ? $0 : nil }
        }
    }

    /// 2–20 regular files within the per-file and total limits, else nil.
    static func offerableSet(_ urls: [URL]) -> [URL]? {
        guard urls.count >= 2, urls.count <= FileLimit.setMaxFiles else { return nil }
        var total = 0
        for url in urls {
            guard let v = try? url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey]),
                  v.isRegularFile == true, let size = v.fileSize, size <= FileLimit.maxBytes else { return nil }
            total += size
        }
        return total <= FileLimit.setMaxBytes ? urls : nil
    }

    /// One regular file within the size limit, else nil.
    static func offerable(_ urls: [URL]) -> URL? {
        guard urls.count == 1, let url = urls.first,
              let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey]),
              values.isRegularFile == true, (values.fileSize ?? Int.max) <= FileLimit.maxBytes
        else { return nil }
        return url
    }

    private func poll() {
        let count = pasteboard.changeCount
        guard count != lastChangeCount else { return }
        lastChangeCount = count
        let types = (pasteboard.types ?? []).map(\.rawValue)
        guard !Self.shouldSkip(types: types) else { return }
        let files = fileURLs()
        if !files.isEmpty {
            // A Finder copy: never send the accompanying file names as text.
            if let url = Self.offerable(files) { onFile?(url) }
            else if let set = Self.offerableSet(files) { onFiles?(set) }
            else { onIgnoredFiles?() }
            return
        }
        if ClipboardImage.isImageCopy(pasteboard) {
            if let png = ClipboardImage.png(from: pasteboard), png.count <= FileLimit.maxBytes { onImage?(png) } else { onIgnoredFiles?() }
            return
        }
        guard let text = pasteboard.string(forType: .string), !text.isEmpty else { return }
        onText?(text)
    }
}
