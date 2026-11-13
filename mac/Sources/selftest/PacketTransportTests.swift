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
    check(BLEIds.localName(macId: "0123456789abcdef") == "9f9f5111", "Bluetooth advertised name carries the pairing tag")
    // macOS drops the name unless flags (3) + 128-bit service UUID (18) + name (2 + n) fit in 31 bytes.
    check(3 + 18 + 2 + BLEIds.localName(macId: "0123456789abcdef").utf8.count <= 31, "advertised name fits in one Bluetooth advertisement")
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
    phone.onRichClip = { _, html, _ in clips.add(html) }
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

func bluetoothSizeCapTests() throws {
    check(FileLimit.bluetoothMaxBytes == 5_242_880, "Bluetooth file cap is 5 MB")
    let key = ClipCrypto.randomKey()
    let board = NSPasteboard(name: NSPasteboard.Name("clipbridge.blecap.\(UUID().uuidString)"))
    defer { board.releaseGlobally() }
    let downloads = tempDir("blecap-dl"), src = tempDir("blecap-src"), into = tempDir("blecap-in")
    defer { [downloads, src, into].forEach { try? FileManager.default.removeItem(at: $0) } }
    let server = Server(key: key, macId: "0123456789abcdef", advertise: false)
    let bridge = Bridge(server: server, watcher: PasteboardWatcher(pasteboard: board, interval: 0.05), downloads: downloads)
    let statuses = Recorder<Server.Status>()
    bridge.onStatus = { statuses.add($0) }
    try bridge.start(); defer { bridge.stop() }
    let pipe = PacketPipe(mtu: 512)
    server.accept(transport: pipe.mac)
    let phone = Client(info: PairingInfo(macId: "0123456789abcdef", key: key, host: "ble", port: 1), device: "ble-phone")
    let messages = Recorder<Message>()
    phone.onMessage = { messages.add($0) }
    phone.start(transport: pipe.phone); defer { phone.stop() }
    _ = waitUntil(5) { statuses.all.last == .connected(device: "ble-phone") }

    func offerId(for url: URL) -> String? {
        let before = messages.all.count
        finderCopy([url], on: board)
        _ = waitUntil(5) { messages.all.dropFirst(before).contains { if case .offer = $0 { return true } else { return false } } }
        for m in messages.all.dropFirst(before).reversed() { if case .offer(let id, _, _, _, _) = m { return id } }
        return nil
    }

    let big = randomFile(src, name: "big.zip", bytes: 6_000_000)
    guard let bigId = offerId(for: big) else { check(false, "big file offered"); return }
    let failed = Recorder<String>()
    phone.expectFile(id: bigId, into: into) { if case .failure(let e) = $0 { failed.add(e.localizedDescription) } }
    phone.send(message: .fetch(id: bigId))
    check(waitUntil(5) { failed.all == ["too-large-bluetooth"] }, "6 MB file over Bluetooth is refused with too-large-bluetooth")

    let small = randomFile(src, name: "small.pdf", bytes: 300_000)
    guard let smallId = offerId(for: small) else { check(false, "small file offered"); return }
    let got = Recorder<URL>()
    phone.expectFile(id: smallId, into: into) { if case .success(let u) = $0 { got.add(u) } }
    phone.send(message: .fetch(id: smallId))
    check(waitUntil(10) { got.all.count == 1 }, "small file is still served over Bluetooth")

    let upload = randomFile(src, name: "upload.bin", bytes: 6_000_000)
    phone.sendFile(url: upload)
    check(waitUntil(10) { messages.all.contains { if case .fileError(_, "too-large-bluetooth") = $0 { return true } else { return false } } },
          "6 MB upload over Bluetooth is refused")
    RunLoop.current.run(until: Date().addingTimeInterval(0.5))
    check(((try? FileManager.default.contentsOfDirectory(atPath: downloads.path)) ?? []).filter { $0.hasPrefix("upload") }.isEmpty,
          "refused upload leaves nothing in Downloads")
}
