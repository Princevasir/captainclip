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

    /// The pasteboard's current plain text, or nil if empty or marked concealed/transient.
    public func currentText() -> String? {
        let types = (pasteboard.types ?? []).map(\.rawValue)
        guard !Self.shouldSkip(types: types), let text = pasteboard.string(forType: .string), !text.isEmpty
        else { return nil }
        return text
    }

    private func poll() {
        let count = pasteboard.changeCount
        guard count != lastChangeCount else { return }
        lastChangeCount = count
        let types = (pasteboard.types ?? []).map(\.rawValue)
        guard !Self.shouldSkip(types: types),
              let text = pasteboard.string(forType: .string), !text.isEmpty
        else { return }
        onText?(text)
    }
}
