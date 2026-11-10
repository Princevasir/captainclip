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

    public static func offer(_ name: String) -> String {
        let prefix = "Ready to paste on phone: "
        let room = 64 - prefix.count
        let shown = name.count <= room ? name : String(name.prefix(room - 10)) + "…" + String(name.suffix(9))
        return prefix + shown
    }
}
