import AppKit
import ClipBridgeCore
import Foundation

func offerSetTests() throws {
    // Message
    let set = Message.offers(items: [.init(id: "a", name: "a.pdf", mime: "application/pdf", size: 3),
                                     .init(id: "b", name: "b.docx", mime: "application/msword", size: 4)], ts: 9)
    check(try Message.decode(set.encoded()) == set, "offers message roundtrips")
    let json = (try? JSONSerialization.jsonObject(with: set.encoded())) as? [String: Any]
    check(json?["t"] as? String == "offers" && (json?["items"] as? [Any])?.count == 2, "offers JSON has t and items")

    check(FileLimit.setMaxBytes == 104_857_600 && FileLimit.setMaxFiles == 20, "several-files limits match spec")

    // Bridge end-to-end
    let key = ClipCrypto.randomKey()
    let board = NSPasteboard(name: NSPasteboard.Name("clipbridge.set.\(UUID().uuidString)"))
    defer { board.releaseGlobally() }
    let src = tempDir("set-src"), into = tempDir("set-in")
    defer { [src, into].forEach { try? FileManager.default.removeItem(at: $0) } }
    let server = Server(key: key, macId: "0123456789abcdef", advertise: false)
    let bridge = Bridge(server: server, watcher: PasteboardWatcher(pasteboard: board, interval: 0.05))
    let statuses = Recorder<Server.Status>()
    bridge.onStatus = { statuses.add($0) }
    try bridge.start(); defer { bridge.stop() }
    _ = waitUntil { server.port != nil }
    let (phone, clips, _) = makeClient(server, key: key); defer { phone.stop() }
    let messages = Recorder<Message>()
    phone.onMessage = { messages.add($0) }
    _ = waitUntil { statuses.all.last == .connected(device: "probe") }

    let files = (1...3).map { randomFile(src, name: "doc\($0).pdf", bytes: 200_000 + $0) }
    finderCopy(files, on: board)
    check(waitUntil { messages.all.contains { if case .offers = $0 { return true } else { return false } } }, "3 copied files → one offers message")
    guard case .offers(let items, _)? = messages.all.last(where: { if case .offers = $0 { return true } else { return false } }) else { return }
    check(items.map(\.name) == ["doc1.pdf", "doc2.pdf", "doc3.pdf"] && items.map(\.size) == [200_001, 200_002, 200_003],
          "offer set lists every file with its size, in order")
    check(clips.all.isEmpty, "no file names sent as text")

    let got = Recorder<URL>()
    for item in items {
        phone.expectFile(id: item.id, into: into) { if case .success(let u) = $0 { got.add(u) } }
        phone.send(message: .fetch(id: item.id))
        _ = waitUntil(5) { got.all.count == items.firstIndex(of: item)! + 1 }
    }
    check(got.all.count == 3, "each file in the set can be fetched")
    check(got.all.sorted { $0.lastPathComponent < $1.lastPathComponent }.enumerated().allSatisfy { i, u in
        (try? Data(contentsOf: u)) == (try? Data(contentsOf: files[i])) }, "fetched files are identical")

    let before = messages.all.count
    let many = (1...25).map { randomFile(src, name: "m\($0).txt", bytes: 10) }
    finderCopy(many, on: board)
    let folder = src.appendingPathComponent("Folder"); try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    RunLoop.current.run(until: Date().addingTimeInterval(0.3))
    finderCopy([files[0], folder], on: board)
    RunLoop.current.run(until: Date().addingTimeInterval(0.3))
    check(!messages.all.dropFirst(before).contains { if case .offers = $0 { return true }; if case .offer = $0 { return true }; return false },
          "25 files, or files plus a folder, offer nothing")
    check(messages.all.dropFirst(before).contains(.offerClear), "an ignored copy clears the previous offer")

    finderCopy([files[0]], on: board)
    check(waitUntil { messages.all.contains { if case .offer = $0 { return true } else { return false } } }, "a single file still sends a plain offer")
}
