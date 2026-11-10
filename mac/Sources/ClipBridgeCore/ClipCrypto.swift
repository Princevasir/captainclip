import CryptoKit
import Foundation

public enum ClipCryptoError: Error {
    case badKeyLength
    case badNonceLength
}

/// AES-256-GCM. Box layout: nonce(12) ‖ ciphertext ‖ tag(16). AAD = "clipbridge-v1".
public struct ClipCrypto {
    public static let aad = Data("clipbridge-v1".utf8)
    private let key: SymmetricKey

    public init(key: Data) throws {
        guard key.count == 32 else { throw ClipCryptoError.badKeyLength }
        self.key = SymmetricKey(data: key)
    }

    public static func randomKey() -> Data {
        SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) }
    }

    public func seal(_ plaintext: Data, nonce: Data? = nil) throws -> Data {
        let gcmNonce: AES.GCM.Nonce
        if let nonce {
            guard nonce.count == 12 else { throw ClipCryptoError.badNonceLength }
            gcmNonce = try AES.GCM.Nonce(data: nonce)
        } else {
            gcmNonce = AES.GCM.Nonce()
        }
        let sealed = try AES.GCM.seal(plaintext, using: key, nonce: gcmNonce, authenticating: Self.aad)
        return sealed.combined!  // always non-nil with the default 12-byte nonce
    }

    public func open(_ box: Data) throws -> Data {
        let sealed = try AES.GCM.SealedBox(combined: box)
        return try AES.GCM.open(sealed, using: key, authenticating: Self.aad)
    }
}
