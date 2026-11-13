import ClipBridgeCore
import Darwin
import Foundation

/// Plain blocking TCP peer for attacker-style tests (raw bytes, no CaptainClip logic).
final class RawPeer {
    private let fd: Int32

    init?(port: UInt16) {
        fd = socket(AF_INET, SOCK_STREAM, 0)
        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = port.bigEndian
        addr.sin_addr.s_addr = inet_addr("127.0.0.1")
        let ok = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        var timeout = timeval(tv_sec: 2, tv_usec: 0)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
        if ok != 0 { close(fd); return nil }
    }

    deinit { close(fd) }

    func write(_ data: Data) { _ = data.withUnsafeBytes { Darwin.write(fd, $0.baseAddress, data.count) } }

    private func readExactly(_ n: Int) -> Data? {
        var out = Data()
        var buf = [UInt8](repeating: 0, count: n)
        while out.count < n {
            let got = recv(fd, &buf, n - out.count, 0)
            if got <= 0 { return nil }
            out.append(contentsOf: buf[0..<got])
        }
        return out
    }

    /// Next frame payload, or nil if the peer closed / timed out.
    func readFrame() -> Data? {
        guard let header = readExactly(4) else { return nil }
        let length = header.reduce(0) { ($0 << 8) | Int($1) }
        return readExactly(length)
    }

    /// True if the server closed the connection (recv returns 0 or reset) within the timeout.
    func isClosedByPeer() -> Bool {
        var byte: UInt8 = 0
        return recv(fd, &byte, 1, 0) <= 0 && errno != EAGAIN
    }
}

func sealedFrame(_ message: Message, key: Data, direction: Direction, challenge: Data) throws -> Data {
    let crypto = try ClipCrypto(key: key)
    let box = try crypto.seal(message.encoded(), aad: ClipCrypto.sessionAAD(direction: direction, challenge: challenge))
    return try FrameCodec.encode(box)
}

func handshakeTests() throws {
    // Shared session vector (Android asserts the same hex).
    let crypto = try ClipCrypto(key: bytes(0x00...0x1f))
    let aad = ClipCrypto.sessionAAD(direction: .phoneToMac, challenge: bytes(0x00...0x0f))
    let vector = try crypto.seal(Data(#"{"t":"ping"}"#.utf8), nonce: bytes(0x00...0x0b), aad: aad)
    print("session vector: \(vector.hex)")
    check(vector.hex == sessionVectorHex, "session vector matches recorded hex")
    check(try crypto.open(vector, aad: aad) == Data(#"{"t":"ping"}"#.utf8), "session vector opens")

    let key = ClipCrypto.randomKey()
    let (server, _, statuses) = try makeServer(key: key)
    defer { server.stop() }
    check(waitUntil { server.port != nil }, "server up for handshake tests")
    let port = server.port!

    // Honest phone: gets a 16-byte challenge, sends hello, gets an encrypted hello back, ping → ping.
    let honest = RawPeer(port: port)!
    let challenge1 = honest.readFrame()
    check(challenge1?.count == 16, "Mac sends a 16-byte challenge first")
    let hello1 = try sealedFrame(.hello(device: "raw"), key: key, direction: .phoneToMac, challenge: challenge1!)
    honest.write(hello1)
    let ack = honest.readFrame().flatMap {
        try? Message.decode(cryptoFor(key).open($0, aad: ClipCrypto.sessionAAD(direction: .macToPhone, challenge: challenge1!)))
    }
    check({ if case .hello = ack { return true } else { return false } }(), "Mac answers hello with its own hello")
    honest.write(try sealedFrame(.ping, key: key, direction: .phoneToMac, challenge: challenge1!))
    let pong = honest.readFrame().flatMap {
        try? Message.decode(cryptoFor(key).open($0, aad: ClipCrypto.sessionAAD(direction: .macToPhone, challenge: challenge1!)))
    }
    check(pong == .ping, "Mac answers ping with ping")
    check(waitUntil { statuses.all.last == .connected(device: "raw") }, "honest raw phone is connected")

    // Replay: the honest hello frame resent on a new connection (new challenge) must be rejected.
    let replayer = RawPeer(port: port)!
    _ = replayer.readFrame()
    replayer.write(hello1)
    check(replayer.isClosedByPeer(), "replayed hello from an old connection is rejected")
    check(statuses.all.last == .connected(device: "raw"), "replay does not kick the real phone off")

    // Reflection: a frame sealed in the Mac→phone direction must not be accepted by the Mac.
    let reflector = RawPeer(port: port)!
    let challenge3 = reflector.readFrame()!
    reflector.write(try sealedFrame(.hello(device: "reflected"), key: key, direction: .macToPhone, challenge: challenge3))
    check(reflector.isClosedByPeer(), "frame sealed for the other direction is rejected")
    check(!statuses.all.contains(.connected(device: "reflected")), "reflected hello never connects")
}

private func cryptoFor(_ key: Data) -> ClipCrypto { try! ClipCrypto(key: key) }
