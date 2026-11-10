import Foundation

/// App-level cap on clipboard text: 256 KB of UTF-8. Kept well under Android's ~1 MB Binder
/// limit (Strings are UTF-16 in a Parcel), so the phone can always put it on its clipboard.
public enum TextLimit {
    public static let maxBytes = 262_144

    public static func fits(_ text: String) -> Bool {
        let n = text.utf8.count
        return n > 0 && n <= maxBytes
    }
}
