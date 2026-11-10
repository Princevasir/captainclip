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

/// Something arriving from the phone replaces the Mac clipboard, so the old file offer must go —
/// otherwise a phone reconnect would re-send it and overwrite what the user copied on the phone.
func phoneCopyClearsOfferTests() throws {
    let (bridge, server, board, src, downloads, key, statuses) = try makeBridge("clear")
    defer { bridge.stop(); board.releaseGlobally(); [src, downloads].forEach { try? FileManager.default.removeItem(at: $0) } }
    let (phone, _, _) = makeClient(server, key: key)
    let messages = Recorder<Message>()
    phone.onMessage = { messages.add($0) }
    _ = waitUntil { statuses.all.last == .connected(device: "probe") }

    finderCopy([randomFile(src, name: "a.pdf", bytes: 1000)], on: board)
    _ = waitUntil { messages.all.contains { if case .offer = $0 { return true } else { return false } } }
    phone.send(text: "copied on phone")
    check(waitUntil { messages.all.contains(.offerClear) }, "text from the phone clears the Mac's file offer")
    phone.stop()
    _ = waitUntil { statuses.all.last == .waiting }

    let (phone2, _, _) = makeClient(server, key: key)
    defer { phone2.stop() }
    let after = Recorder<Message>()
    phone2.onMessage = { after.add($0) }
    _ = waitUntil { statuses.all.last == .connected(device: "probe") }
    RunLoop.current.run(until: Date().addingTimeInterval(0.4))
    check(!after.all.contains { if case .offer = $0 { return true } else { return false } }, "reconnect does not re-send the old offer")

    finderCopy([randomFile(src, name: "b.pdf", bytes: 1000)], on: board)
    _ = waitUntil { after.all.contains { if case .offer = $0 { return true } else { return false } } }
    phone2.sendFile(url: randomFile(src, name: "from-phone.pdf", bytes: 1000))
    check(waitUntil(5) { after.all.contains(.offerClear) }, "a file from the phone also clears the offer")
}

/// Finder often writes file *reference* URLs (file:///.file/id=…); offers must still use the real name.
func fileReferenceURLTests() {
    let dir = tempDir("ref")
    defer { try? FileManager.default.removeItem(at: dir) }
    let pdf = randomFile(dir, name: "report.pdf", bytes: 2000)
    // Swift bridges fileReferenceURL() back to a path URL, so ask Objective-C for the raw NSURL string.
    let raw = (pdf as NSURL).perform(#selector(NSURL.fileReferenceURL))?.takeUnretainedValue() as? NSURL
    guard let refString = raw?.absoluteString else { check(false, "can make a file reference URL"); return }
    check(refString.contains("/.file/id="), "test uses a real file reference URL (\(refString))")
    let board = NSPasteboard(name: NSPasteboard.Name("clipbridge.ref.\(UUID().uuidString)"))
    defer { board.releaseGlobally() }
    let watcher = PasteboardWatcher(pasteboard: board, interval: 0.05)
    let files = Recorder<URL>()
    watcher.onFile = { files.add($0) }
    watcher.start()
    defer { watcher.stop() }
    board.clearContents()
    let item = NSPasteboardItem()
    item.setString(refString, forType: .fileURL)
    board.writeObjects([item])
    check(waitUntil { files.all.first?.lastPathComponent == "report.pdf" }, "file reference URL is resolved to the real file name")
}

/// Files from the phone get the quarantine flag, so Gatekeeper checks them like any download.
func quarantineTests() throws {
    let src = tempDir("q-src"), dst = tempDir("q-dst")
    defer { [src, dst].forEach { try? FileManager.default.removeItem(at: $0) } }
    let file = randomFile(src, name: "tool.sh", bytes: 100)
    let sender = try FileSender(id: "q", url: file, name: "tool.sh", mime: "text/x-shellscript")
    let receiver = try FileReceiver(start: sender.start, directory: dst)
    var out: URL?
    for m in sender.stream() { out = try receiver.accept(m) ?? out }
    let props = try out.flatMap { try $0.resourceValues(forKeys: [.quarantinePropertiesKey]).quarantineProperties }
    check(props != nil, "received file carries the macOS quarantine flag")
}
