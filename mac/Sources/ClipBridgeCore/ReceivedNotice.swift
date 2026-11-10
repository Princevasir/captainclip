import Foundation

/// Notification text for a clip from the phone. Never includes the text itself: it could be a
/// password, and notifications stay visible in Notification Center.
public enum ReceivedNotice {
    public static func body(for text: String) -> String {
        "\(text.count) characters ready to paste"
    }
}
