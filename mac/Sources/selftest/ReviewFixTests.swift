import AppKit
import ClipBridgeCore
import Foundation

private func makeBridge(_ tag: String) throws -> (Bridge, Server, NSPasteboard, URL, URL, Data, Recorder<Server.Status>) {
    let key = ClipCrypto.randomKey()
    let src = tempDir("\(tag)-src"), downloads = tempDir("\(tag)-dl")
    let board = NSPasteboard(name: NSPasteboard.Name("clipbridge.\(tag).\(UUID().uuidString)"))
    let server = Server(key: key, macId: "0123456789abcdef", advertise: false)
    let bridge = Bridge(server: server, watcher: PasteboardWatcher(pasteboard: board, interval: 0.05),
                        offers: OfferStore(), downloads: downloads)
    let statuses = Recorder<Server.Status>()
    bridge.onStatus = { statuses.add($0) }
    try bridge.start()
    _ = waitUntil { server.port != nil }
    return (bridge, server, board, src, downloads, key, statuses)
}

private func dirEntries(_ url: URL) -> [String] { (try? FileManager.default.contentsOfDirectory(atPath: url.path)) ?? [] }

/// Phone drops off Wi-Fi mid-upload and reconnects before the Mac noticed: no half file may stay.
func reconnectMidUploadTests() throws {
    let (bridge, server, board, src, downloads, key, statuses) = try makeBridge("reup")
    defer { bridge.stop(); board.releaseGlobally(); [src, downloads].forEach { try? FileManager.default.removeItem(at: $0) } }
    let (phoneA, _, _) = makeClient(server, key: key)
    _ = waitUntil { statuses.all.last == .connected(device: "probe") }
    phoneA.sendFile(url: randomFile(src, name: "video.mov", bytes: 20_000_000))
    check(waitUntil(10) { dirEntries(downloads).contains { $0.hasSuffix(".clipbridge-part") } }, "upload in progress")
    let (phoneB, _, _) = makeClient(server, key: key)  // same phone, new connection; old one never closed cleanly
    defer { phoneA.stop(); phoneB.stop() }
    check(waitUntil(5) { dirEntries(downloads).isEmpty }, "half upload removed when the phone reconnects (had \(dirEntries(downloads)))")
}

/// A second fetch while the Mac is still streaming must not hang the phone.
func refetchWhileStreamingTests() throws {
    let (bridge, server, board, src, downloads, key, statuses) = try makeBridge("refetch")
    defer { bridge.stop(); board.releaseGlobally(); [src, downloads].forEach { try? FileManager.default.removeItem(at: $0) } }
    let into = tempDir("refetch-in")
    defer { try? FileManager.default.removeItem(at: into) }
    let (phone, _, _) = makeClient(server, key: key)
    defer { phone.stop() }
    let messages = Recorder<Message>()
    phone.onMessage = { messages.add($0) }
    _ = waitUntil { statuses.all.last == .connected(device: "probe") }

    let big = randomFile(src, name: "slides.pdf", bytes: 20_000_000)
    finderCopy([big], on: board)
    _ = waitUntil { messages.all.contains { if case .offer = $0 { return true } else { return false } } }
    guard case .offer(let id, _, _, _, _)? = messages.all.first(where: { if case .offer = $0 { return true } else { return false } }) else { return }

    phone.expectFile(id: id, into: into) { _ in }
    phone.send(message: .fetch(id: id))
    _ = waitUntil(5) { messages.all.contains { if case .fileChunk = $0 { return true } else { return false } } }
    let second = Recorder<URL>()
    phone.expectFile(id: id, into: into) { if case .success(let u) = $0 { second.add(u) } }
    phone.send(message: .fetch(id: id))  // paste again while the first stream is running
    check(waitUntil(15) { second.all.count == 1 }, "second fetch during a stream completes instead of hanging")
    check(second.all.first.flatMap { try? Data(contentsOf: $0) } == (try? Data(contentsOf: big)), "second fetch bytes identical")
}
