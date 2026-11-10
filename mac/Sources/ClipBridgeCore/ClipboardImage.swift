import AppKit

/// Images on the Mac clipboard that aren't files (⌃⇧⌘4 screenshots, "Copy Image" in browsers/Preview).
public enum ClipboardImage {
    /// PNG bytes of the clipboard image, converting TIFF if needed. Nil if there's no image.
    public static func png(from pasteboard: NSPasteboard) -> Data? {
        if let png = pasteboard.data(forType: .png), !png.isEmpty { return png }
        guard let tiff = pasteboard.data(forType: .tiff), let rep = NSBitmapImageRep(data: tiff) else { return nil }
        return rep.representation(using: .png, properties: [:])
    }

    /// Whether this copy should be treated as an image: image data and no real text
    /// (formatted text from Word/Notes can carry a preview image alongside its text).
    static func isImageCopy(_ pasteboard: NSPasteboard) -> Bool {
        let types = pasteboard.types ?? []
        guard types.contains(.png) || types.contains(.tiff) else { return false }
        let text = pasteboard.string(forType: .string)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return text.isEmpty
    }

    /// Saves `png` as "Screenshot YYYY-MM-DD at HH.mm.ss.png" in `dir`, deleting older cached images first.
    public static func save(_ png: Data, in dir: URL, now: Date = Date()) throws -> URL {
        let fm = FileManager.default
        try fm.createDirectory(at: dir, withIntermediateDirectories: true)
        for old in (try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? [] where old.pathExtension == "png" {
            try? fm.removeItem(at: old)
        }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd 'at' HH.mm.ss"
        let url = dir.appendingPathComponent("Screenshot \(formatter.string(from: now)).png")
        try png.write(to: url)
        return url
    }

    /// Default cache folder for clipboard images (never Downloads).
    public static var defaultCache: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("ClipBridge/clipboard-images", isDirectory: true)
    }
}
