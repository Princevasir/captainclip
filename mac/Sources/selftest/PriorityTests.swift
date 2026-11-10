import ClipBridgeCore
import Foundation

func priorityTests() throws {
    let key = ClipCrypto.randomKey()
    let (server, _, statuses) = try makeServer(key: key)
    defer { server.stop() }
    _ = waitUntil { server.port != nil }
    let (phone, _, _) = makeClient(server, key: key)
    defer { phone.stop() }
    _ = waitUntil { statuses.all.last == .connected(device: "probe") }

    let dir = tempDir("prio"), into = tempDir("prio-in")
    defer { try? FileManager.default.removeItem(at: dir); try? FileManager.default.removeItem(at: into) }
    let file = randomFile(dir, name: "big.bin", bytes: 20_000_000)
    let sender = try FileSender(id: "p1", url: file, name: "big.bin", mime: "application/octet-stream")

    let order = Recorder<String>()
    var sentUrgent = false
    phone.onMessage = { m in
        switch m {
        case .fileChunk(_, let seq, _) where seq == 0 && !sentUrgent:
            sentUrgent = true
            order.add("chunk0")
            server.send(text: "urgent")
        case .fileEnd: order.add("end")
        default: break
        }
    }
    phone.onClip = { order.add("clip:\($0)") }
    let received = Recorder<URL>()
    phone.expectFile(id: "p1", into: into) { if case .success(let url) = $0 { received.add(url) } }

    check(server.send(message: sender.start), "server sends file-start")
    let done = Recorder<Bool>()
    server.sendStream(AnySequence(Array(sender.chunks()) + [sender.end])) { done.add($0) }
    check(waitUntil(20) { order.all.contains("end") }, "20 MB stream completes")
    let o = order.all
    check(o.firstIndex(of: "clip:urgent").map { $0 < o.firstIndex(of: "end")! } ?? false,
          "clipboard text sent mid-transfer arrives before the file ends")
    check(waitUntil(5) { done.all == [true] }, "stream completion reports success")
    check(waitUntil(5) { received.all.count == 1 }, "client assembled and verified the file")
    check(received.all.first.flatMap { try? Data(contentsOf: $0) } == (try? Data(contentsOf: file)), "streamed bytes identical")
}
