import Foundation

/// Wording for the Mac menu (unit-tested).
public enum MenuText {
    public static func status(_ status: Server.Status, paused: Bool) -> String {
        if paused { return "Paused — nothing is synced" }
        switch status {
        case .waiting: return "Waiting for your phone…"
        case .connected(let device): return "Connected to \(device)"
        }
    }

    /// The grey line under the status: how the phone is connected, or how to connect it.
    public static func detail(_ status: Server.Status, paused: Bool, bluetooth: Bool) -> String? {
        if paused { return nil }
        switch status {
        case .connected: return bluetooth ? "Via Bluetooth · end-to-end encrypted" : "Same Wi-Fi · end-to-end encrypted"
        case .waiting: return "Open ClipBridge on your phone — same Wi-Fi, or nearby with Bluetooth"
        }
    }

    public static func offer(_ name: String) -> String {
        let prefix = "Ready to paste on phone: "
        let room = 64 - prefix.count
        let shown = name.count <= room ? name : String(name.prefix(room - 10)) + "…" + String(name.suffix(9))
        return prefix + shown
    }
}
