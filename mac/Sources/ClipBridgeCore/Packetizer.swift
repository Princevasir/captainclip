import CryptoKit
import Foundation

/// Cuts a byte stream into BLE-sized packets. BLE delivers them in order, so no header is needed.
public enum Packetizer {
    public static func split(_ data: Data, mtu: Int) -> [Data] {
        guard !data.isEmpty, mtu > 0 else { return [] }
        return stride(from: 0, to: data.count, by: mtu).map { start in
            data.subdata(in: data.startIndex + start ..< data.startIndex + min(start + mtu, data.count))
        }
    }
}

/// Short, non-secret tag advertised over Bluetooth so a phone only connects to its own Mac
/// (authentication is still the encrypted handshake).
public enum PairingTag {
    public static func of(macId: String) -> Data {
        Data(SHA256.hash(data: Data(macId.utf8)).prefix(4))
    }
}

public extension Data {
    var hexString: String { map { String(format: "%02x", $0) }.joined() }
}
