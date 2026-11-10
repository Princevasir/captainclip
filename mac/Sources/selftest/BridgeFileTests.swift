import AppKit
import ClipBridgeCore
import Foundation

func bridgeFileTests() throws {
    let key = ClipCrypto.randomKey()
    let src = tempDir("bf-src"), downloads = tempDir("bf-dl"), into = tempDir("bf-into")
    defer { [src, downloads, into].forEach { try? FileManager.default.removeItem(at: $0) } }
    let board = NSPasteboard(name: NSPasteboard.Name("clipbridge.bf.\(UUID().uuidString)"))
    defer { board.releaseGlobally() }
    let server = Server(key: key, macId: "0123456789abcdef", advertise: false)
    let watcher = PasteboardWatcher(pasteboard: board, interval: 0.05)
    let bridge = Bridge(server: server, watcher: watcher, offers: OfferStore(), downloads: downloads)
    let statuses = Recorder<Server.Status>(), arrived = Recorder<URL>()
    bridge.onStatus = { statuses.add($0) }
    bridge.onFileReceived = { arrived.add($0) }
    try bridge.start()
    defer { bridge.stop() }
    _ = waitUntil { server.port != nil }

    let (phone, phoneClips, _) = makeClient(server, key: key)
    defer { phone.stop() }
    let messages = Recorder<Message>()
    phone.onMessage = { messages.add($0) }
    _ = waitUntil { statuses.all.last == .connected(device: "probe") }

    // 1. Copy a file → offer only (no content, no text)
    let pdf = randomFile(src, name: "report.pdf", bytes: 700_000)
    finderCopy([pdf], on: board)
    check(waitUntil { messages.all.contains { if case .offer = $0 { return true } else { return false } } }, "file copy sends an offer")
    RunLoop.current.run(until: Date().addingTimeInterval(0.2))
    check(phoneClips.all.isEmpty, "file copy sends no clipboard text")
    check(!messages.all.contains { if case .fileStart = $0 { return true } else { return false } }, "no file content sent before fetch")
    guard case .offer(let id, _, _, let size, _)? = messages.all.first(where: { if case .offer = $0 { return true } else { return false } }) else { return }
    check(size == 700_000, "offer has the file size")

    // 2. Fetch → identical bytes
    let got = Recorder<URL>()
    phone.expectFile(id: id, into: into) { if case .success(let u) = $0 { got.add(u) } }
    phone.send(message: .fetch(id: id))
    check(waitUntil(10) { got.all.count == 1 }, "fetched file arrives")
    check(got.all.first.flatMap { try? Data(contentsOf: $0) } == (try? Data(contentsOf: pdf)), "fetched bytes identical")

    // 3. Edited after copy → changed
    try Data("edited".utf8).write(to: pdf)
    let failed = Recorder<String>()
    phone.expectFile(id: id, into: into) { if case .failure(let e) = $0 { failed.add(e.localizedDescription) } }
    phone.send(message: .fetch(id: id))
    check(waitUntil { failed.all == ["changed"] }, "file edited after copy is refused with 'changed'")

    // 4. Text copy after file → offer-clear then clip
    messages.add(.ping)  // marker
    board.clearContents(); board.setString("some text", forType: .string)
    check(waitUntil { phoneClips.all == ["some text"] }, "text copy still syncs")
    let afterMarker = messages.all.drop(while: { $0 != .ping })
    check(afterMarker.contains(.offerClear), "text copy clears the file offer on the phone")

    // 5. Phone sends a file → Downloads + pasteboard, no offer echoed back
    let fromPhone = randomFile(src, name: "scan.pdf", bytes: 500_000)
    let offersBefore = messages.all.filter { if case .offer = $0 { return true } else { return false } }.count
    phone.sendFile(url: fromPhone, mime: "application/pdf")
    check(waitUntil(10) { arrived.all.count == 1 }, "phone file reported as received")
    let landed = arrived.all.first
    check(landed?.deletingLastPathComponent().standardizedFileURL == downloads.standardizedFileURL && landed?.lastPathComponent == "scan.pdf",
          "phone file lands in Downloads with its name")
    check(landed.flatMap { try? Data(contentsOf: $0) } == (try? Data(contentsOf: fromPhone)), "phone file bytes identical")
    check(waitUntil { watcher.currentFile()?.lastPathComponent == "scan.pdf" }, "received file is on the Mac clipboard for ⌘V")
    RunLoop.current.run(until: Date().addingTimeInterval(0.4))
    check(messages.all.filter { if case .offer = $0 { return true } else { return false } }.count == offersBefore,
          "received file is not offered back to the phone")
}

func bridgeUploadAbortTests() throws {
    let key = ClipCrypto.randomKey()
    let src = tempDir("ab-src"), downloads = tempDir("ab-dl")
    defer { [src, downloads].forEach { try? FileManager.default.removeItem(at: $0) } }
    let board = NSPasteboard(name: NSPasteboard.Name("clipbridge.ab.\(UUID().uuidString)"))
    defer { board.releaseGlobally() }
    let server = Server(key: key, macId: "0123456789abcdef", advertise: false)
    let bridge = Bridge(server: server, watcher: PasteboardWatcher(pasteboard: board, interval: 0.05),
                        offers: OfferStore(), downloads: downloads)
    let statuses = Recorder<Server.Status>(), arrived = Recorder<URL>()
    bridge.onStatus = { statuses.add($0) }
    bridge.onFileReceived = { arrived.add($0) }
    try bridge.start()
    defer { bridge.stop() }
    _ = waitUntil { server.port != nil }
    let (phone, _, _) = makeClient(server, key: key)
    _ = waitUntil { statuses.all.last == .connected(device: "probe") }

    let big = randomFile(src, name: "video.mov", bytes: 20_000_000)
    phone.sendFile(url: big)
    let started = waitUntil(10) {
        ((try? FileManager.default.contentsOfDirectory(atPath: downloads.path)) ?? []).contains { $0.hasSuffix(".clipbridge-part") }
    }
    check(started, "upload in progress creates a temp file")
    phone.stop()
    check(waitUntil(5) { statuses.all.last == .waiting }, "Mac notices the phone left")
    let leftovers = (try? FileManager.default.contentsOfDirectory(atPath: downloads.path)) ?? []
    check(waitUntil(3) { ((try? FileManager.default.contentsOfDirectory(atPath: downloads.path)) ?? []).isEmpty },
          "interrupted upload leaves nothing in Downloads (had: \(leftovers))")
    check(arrived.all.isEmpty, "interrupted upload is not reported as received")
}
