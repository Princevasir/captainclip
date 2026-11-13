import Foundation
import ClipBridgeCore

func pairingTests() throws {
    let key = bytes(0x00...0x1f)
    let info = PairingInfo(macId: "0123456789abcdef", key: key, host: "192.168.1.20", port: 52001)
    let uri = info.uri
    check(uri.hasPrefix("clipbridge://pair?v=1&"), "uri has scheme and version")
    check(uri == "clipbridge://pair?v=1&id=0123456789abcdef&key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8&host=192.168.1.20&port=52001",
          "uri has exact expected form")
    let ffKey = PairingInfo(macId: "0123456789abcdef", key: Data(repeating: 0xff, count: 32), host: "h", port: 1)
    check(ffKey.uri.contains("key=__________________________________________8&"),
          "key is base64url without padding")
    check(PairingInfo(uri: ffKey.uri) == ffKey, "base64url key roundtrips")
    check(PairingInfo(uri: uri) == info, "uri roundtrips")
    check(PairingInfo(uri: uri.replacingOccurrences(of: "v=1", with: "v=2")) == nil, "v=2 rejected")
    let shortKey = PairingInfo(macId: "0123456789abcdef", key: Data(count: 16), host: "h", port: 1).uri
    check(PairingInfo(uri: shortKey) == nil, "16-byte key rejected")
    check(PairingInfo(uri: "https://example.com") == nil, "foreign uri rejected")
    check(Server.serviceType == "_clipbridge._tcp" && Server.serviceNamePrefix == "ClipBridge-" && uri.hasPrefix("clipbridge://"),
          "protocol identifiers unchanged by the CaptainClip rename")
}

func loopGuardTests() {
    let guardian = LoopGuard()
    check(guardian.shouldSend("a"), "fresh text is sent")
    guardian.markReceived("a")
    check(!guardian.shouldSend("a"), "text just received is not echoed back")
    check(guardian.shouldSend("b"), "different text is sent")
    guardian.markSent("b")
    check(!guardian.shouldSend("b"), "text just sent is not re-sent")
    check(!guardian.shouldSend(""), "empty text never sent")
}
