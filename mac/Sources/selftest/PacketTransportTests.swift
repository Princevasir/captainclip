import AppKit
import ClipBridgeCore
import Foundation

/// Two linked in-memory transports behaving like a BLE link: ordered packets of at most `mtu` bytes.
final class PacketPipe {
    final class End: Transport {
        let isBluetooth = true
        var onReady: (() -> Void)?
        var onData: ((Data) -> Void)?
        var onClose: (() -> Void)?
        weak var peer: End?
        let mtu: Int
        private var queue: DispatchQueue?
        private var closed = false
        private var early: [Data] = []   // packets that arrive before start (a real BLE stack buffers them)
        private let lock = NSLock()
        init(mtu: Int) { self.mtu = mtu }
        func start(queue: DispatchQueue) {
            lock.withLock { self.queue = queue }
            queue.async {
                self.onReady?()
                let pending = self.lock.withLock { () -> [Data] in let p = self.early; self.early = []; return p }
                pending.forEach { self.onData?($0) }
            }
        }
        func send(_ data: Data, completion: @escaping (Error?) -> Void) {
            guard !closed, let peer else { completion(NSError(domain: "pipe", code: 1)); return }
            for packet in Packetizer.split(data, mtu: mtu) { peer.deliver(packet) }
            queue?.async { completion(nil) }
        }
        func deliver(_ packet: Data) {
            let q = lock.withLock { () -> DispatchQueue? in
                if queue == nil { early.append(packet) }
                return queue
            }
            q?.async { if !self.closed { self.onData?(packet) } }
        }
        func cancel() {
            guard !closed else { return }
            closed = true
            peer?.remoteClosed()
        }
        func remoteClosed() { queue?.async { if !self.closed { self.closed = true; self.onClose?() } } }
    }
    let mac: End, phone: End
    init(mtu: Int = 182) {
        mac = End(mtu: mtu); phone = End(mtu: mtu)
        mac.peer = phone; phone.peer = mac
    }
}

func packetizerTests() {
    let data = Data((0..<1000).map { UInt8($0 % 256) })
    let packets = Packetizer.split(data, mtu: 182)
    check(packets.count == 6 && packets.allSatisfy { $0.count <= 182 }, "1000 bytes → 6 packets of ≤ 182")
    check(packets.reduce(Data(), +) == data, "packets reassemble to the original bytes")
    check(Packetizer.split(Data(), mtu: 182).isEmpty, "empty data → no packets")

    let tag = PairingTag.of(macId: "0123456789abcdef")
    print("pairing tag vector: \(tag.hex)")
    check(tag.count == 4, "pairing tag is 4 bytes")
    check(tag.hex == pairingTagVector, "pairing tag matches recorded vector")
    check(PairingTag.of(macId: "fedcba9876543210") != tag, "different Macs have different tags")
}

let pairingTagVector = "9f9f5111"

func sessionOverBluetoothTests() throws {
    let key = ClipCrypto.randomKey()
    let board = NSPasteboard(name: NSPasteboard.Name("clipbridge.ble.\(UUID().uuidString)"))
    defer { board.releaseGlobally() }
    let downloads = tempDir("ble-dl"), src = tempDir("ble-src")
    defer { [downloads, src].forEach { try? FileManager.default.removeItem(at: $0) } }
    let server = Server(key: key, macId: "0123456789abcdef", advertise: false)
    let bridge = Bridge(server: server, watcher: PasteboardWatcher(pasteboard: board, interval: 0.05), downloads: downloads)
    let statuses = Recorder<Server.Status>(), arrived = Recorder<URL>()
    bridge.onStatus = { statuses.add($0) }
    bridge.onFileReceived = { arrived.add($0) }
    try bridge.start(); defer { bridge.stop() }

    let pipe = PacketPipe()
    server.accept(transport: pipe.mac)
    let info = PairingInfo(macId: "0123456789abcdef", key: key, host: "ble", port: 1)
    let phone = Client(info: info, device: "ble-phone")
    let clips = Recorder<String?>()
    phone.onRichClip = { _, html in clips.add(html) }
    phone.start(transport: pipe.phone)
    defer { phone.stop() }
    check(waitUntil(5) { statuses.all.last == .connected(device: "ble-phone") }, "handshake completes over 182-byte packets")

    board.clearContents(); board.setString("over bluetooth", forType: .string); board.setString("<b>over bluetooth</b>", forType: .html)
    check(waitUntil(5) { clips.all.contains("<b>over bluetooth</b>") }, "formatted clip reaches the phone over Bluetooth")

    let file = randomFile(src, name: "notes.pdf", bytes: 2_000_000)
    phone.sendFile(url: file, mime: "application/pdf")
    check(waitUntil(20) { arrived.all.count == 1 }, "2 MB file crosses the Bluetooth-like link")
    check(arrived.all.first.flatMap { try? Data(contentsOf: $0) } == (try? Data(contentsOf: file)), "file intact over Bluetooth")
}
