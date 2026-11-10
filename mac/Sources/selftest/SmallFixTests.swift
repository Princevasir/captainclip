import ClipBridgeCore
import Foundation

func smallFixTests() throws {
    // The Bluetooth cap is enforced where the file is actually sent, not only when the fetch arrives
    // (the phone may have switched from Wi-Fi to Bluetooth in between).
    let key = ClipCrypto.randomKey()
    let server = Server(key: key, macId: "0123456789abcdef", advertise: false)
    let statuses = Recorder<Server.Status>()
    server.onStatus = { statuses.add($0) }
    try server.start(); defer { server.stop() }
    let pipe = PacketPipe(mtu: 512)
    server.accept(transport: pipe.mac)
    let phone = Client(info: PairingInfo(macId: "0123456789abcdef", key: key, host: "ble", port: 1), device: "ble-phone")
    phone.start(transport: pipe.phone); defer { phone.stop() }
    _ = waitUntil(5) { statuses.all.last == .connected(device: "ble-phone") }
    let into = tempDir("cap-late"); defer { try? FileManager.default.removeItem(at: into) }
    let failed = Recorder<String>()
    phone.expectFile(id: "late", into: into) { if case .failure(let e) = $0 { failed.add(e.localizedDescription) } }
    let start = Message.fileStart(id: "late", name: "big.zip", mime: "application/zip", size: 6_000_000, sha256: "00")
    server.sendFile(start: start, stream: AnySequence([]))
    check(waitUntil(5) { failed.all == ["too-large-bluetooth"] }, "over-5 MB send on a Bluetooth session is refused at send time")
}
