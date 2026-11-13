import AppKit
import ClipBridgeCore
import Foundation

func clearAfterTests() throws {
    check(ClearAfter.shouldClear(writtenChangeCount: 7, currentChangeCount: 7), "clear when unchanged")
    check(!ClearAfter.shouldClear(writtenChangeCount: 7, currentChangeCount: 8), "clear skipped after a newer copy")

    let concealed = NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType")
    let board = NSPasteboard(name: NSPasteboard.Name("clipbridge.clearafter.\(UUID().uuidString)"))
    defer { board.releaseGlobally() }
    let watcher = PasteboardWatcher(pasteboard: board, interval: 0.05)
    let seen = Recorder<String>()
    watcher.onText = { seen.add($0) }
    watcher.start()
    defer { watcher.stop() }
    watcher.write("424242", html: nil, concealed: true)
    check(board.types?.contains(concealed) == true, "concealed write carries ConcealedType")
    check(board.string(forType: .string) == "424242", "concealed write still carries the text")
    RunLoop.current.run(until: Date().addingTimeInterval(0.3))
    check(seen.all.isEmpty, "watcher ignores its own concealed write")
    watcher.write("plain", html: nil, concealed: false)
    check(board.types?.contains(concealed) != true, "normal write is not concealed")

    // Through the bridge: a sensitive clip from the phone is concealed, then cleared after the delay.
    let key = ClipCrypto.randomKey()
    let bboard = NSPasteboard(name: NSPasteboard.Name("clipbridge.clearafter.bridge.\(UUID().uuidString)"))
    defer { bboard.releaseGlobally() }
    let server = Server(key: key, macId: "0123456789abcdef", advertise: false)
    let bridge = Bridge(server: server, watcher: PasteboardWatcher(pasteboard: bboard, interval: 0.05))
    bridge.sensitiveClearDelay = 0.6
    let statuses = Recorder<Server.Status>(), received = Recorder<String>()
    bridge.onStatus = { statuses.add($0) }
    bridge.onReceived = { received.add($0) }
    try bridge.start()
    defer { bridge.stop() }
    _ = waitUntil { server.port != nil }
    let (phone, phoneClips, _) = makeClient(server, key: key)
    defer { phone.stop() }
    check(waitUntil { statuses.all.last == .connected(device: "probe") }, "clear-after bridge connected")

    func sendCode(_ text: String) {
        phone.send(message: .clip(id: UUID().uuidString, text: text, ts: 1, html: nil, sensitive: true))
    }
    sendCode("111222")
    check(waitUntil { bboard.string(forType: .string) == "111222" }, "sensitive clip lands on the Mac pasteboard")
    check(bboard.types?.contains(concealed) == true, "sensitive clip is written concealed")
    check(waitUntil(4) { bboard.string(forType: .string) == nil }, "code is cleared after the delay")
    check(received.all.isEmpty, "a code is not handed to the notice callback")

    // A newer copy keeps the clipboard: the old timer must not clear it.
    sendCode("333444")
    check(waitUntil { bboard.string(forType: .string) == "333444" }, "second code lands")
    bboard.clearContents(); bboard.setString("my own copy", forType: .string)
    RunLoop.current.run(until: Date().addingTimeInterval(1.2))
    check(bboard.string(forType: .string) == "my own copy", "a newer copy is not cleared")

    // A newer code supersedes the older timer: the first timer must not clear the second code early.
    sendCode("555666")
    check(waitUntil { bboard.string(forType: .string) == "555666" }, "third code lands")
    RunLoop.current.run(until: Date().addingTimeInterval(0.35))
    sendCode("777888")
    check(waitUntil { bboard.string(forType: .string) == "777888" }, "fourth code lands")
    RunLoop.current.run(until: Date().addingTimeInterval(0.35))  // the third code's timer has fired by now
    check(bboard.string(forType: .string) == "777888", "older timer does not clear a newer code early")
    check(waitUntil(4) { bboard.string(forType: .string) == nil }, "newer code is cleared on its own timer")
    check(phoneClips.all.allSatisfy { $0 == "my own copy" }, "codes are never echoed back to the phone")

    // Paused: a sensitive clip is handled exactly like any other phone clip (ignored).
    bridge.paused = true
    sendCode("999000")
    RunLoop.current.run(until: Date().addingTimeInterval(0.3))
    check(bboard.string(forType: .string) == nil, "paused: sensitive clip is not received")
}
