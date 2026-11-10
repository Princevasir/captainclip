import Foundation
import Security

/// Keeps the Mac's id and pairing key in the login Keychain (generic password).
public struct KeyStore {
    public let service: String
    private let account = "pairing"

    public init(service: String = "app.clipbridge.mac") {
        self.service = service
    }

    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: account]
    }

    public func load() -> (macId: String, key: Data)? {
        var q = query
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data,
              let text = String(data: data, encoding: .utf8)
        else { return nil }
        let parts = text.split(separator: ":", maxSplits: 1).map(String.init)
        guard parts.count == 2, let key = Data(base64Encoded: parts[1]), key.count == 32 else { return nil }
        return (parts[0], key)
    }

    @discardableResult
    public func save(macId: String, key: Data) -> Bool {
        delete()
        var q = query
        q[kSecValueData as String] = Data("\(macId):\(key.base64EncodedString())".utf8)
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(q as CFDictionary, nil) == errSecSuccess
    }

    public func delete() {
        SecItemDelete(query as CFDictionary)
    }
}
