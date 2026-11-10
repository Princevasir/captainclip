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

    /// Puts a file on the pasteboard (for ⌘V in Finder/Mail) without reporting it back through onFile.
    public func writeFile(_ url: URL) {
        pasteboard.clearContents()
        pasteboard.writeObjects([url as NSURL])
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
            if let url = Self.offerable(files) { onFile?(url) } else { onIgnoredFiles?() }
            return
        }
        guard let text = pasteboard.string(forType: .string), !text.isEmpty else { return }
        onText?(text)
    }
}
