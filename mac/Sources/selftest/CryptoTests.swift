import Foundation
import ClipBridgeCore

/// Shared with Android (docs/test-vector.txt): key 00..1f, nonce 00..0b, plaintext {"t":"ping"}.
let knownVectorHex = "000102030405060708090a0b3c20a239ffc7b272e326b5f68f06b385001a35d68d5681074ae47dc3"

func cryptoTests() throws {
    let key = bytes(0x00...0x1f)
    let crypto = try ClipCrypto(key: key)

    let text = Data("héllo 👋".utf8)
    check(try crypto.open(crypto.seal(text)) == text, "seal/open roundtrip")

    let vector = try crypto.seal(Data(#"{"t":"ping"}"#.utf8), nonce: bytes(0x00...0x0b))
    print("known vector: \(vector.hex)")
    check(vector.hex == knownVectorHex, "known vector matches recorded hex")

    let other = try ClipCrypto(key: ClipCrypto.randomKey())
    let box = try crypto.seal(text)
    checkThrows("open with wrong key throws") { _ = try other.open(box) }

    var tampered = box
    tampered[tampered.count - 1] ^= 0x01
    checkThrows("open with flipped byte throws") { _ = try crypto.open(tampered) }

    checkThrows("key of 16 bytes rejected") { _ = try ClipCrypto(key: Data(count: 16)) }
    check(ClipCrypto.randomKey().count == 32, "random key is 32 bytes")
}
