import AppKit
import ClipBridgeCore
import Foundation

func bridgeTests() throws {
    let key = ClipCrypto.randomKey()
    let board = NSPasteboard(name: NSPasteboard.Name("clipbridge.bridge.\(UUID().uuidString)"))
    defer { board.releaseGlobally() }
    let server = Server(key: key, macId: "0123456789abcdef", advertise: false)
    let bridge = Bridge(server: server, watcher: PasteboardWatcher(pasteboard: board, interval: 0.05))
    let statuses = Recorder<Server.Status>(), received = Recorder<String>()
    bridge.onStatus = { statuses.add($0) }
    bridge.onReceived = { received.add($0) }
    try bridge.start()
    defer { bridge.stop() }
    _ = waitUntil { server.port != nil }

    let (phone, phoneClips, _) = makeClient(server, key: key)
    defer { phone.stop() }
    check(waitUntil { statuses.all.last == .connected(device: "probe") }, "bridge reports phone connected")

    board.clearContents(); board.setString("mac copy", forType: .string)
    check(waitUntil { phoneClips.all == ["mac copy"] }, "Mac copy reaches phone")

    phone.send(text: "phone copy")
    check(waitUntil { board.string(forType: .string) == "phone copy" }, "phone copy lands on Mac pasteboard")
    check(received.all == ["phone copy"], "bridge reports received text (for notification)")
    RunLoop.current.run(until: Date().addingTimeInterval(0.3))
    check(phoneClips.all == ["mac copy"], "phone copy is not echoed back to phone")

    bridge.paused = true
    board.clearContents(); board.setString("while paused", forType: .string)
    RunLoop.current.run(until: Date().addingTimeInterval(0.3))
    check(!phoneClips.all.contains("while paused"), "nothing is sent while paused")
    phone.send(text: "phone while paused")
    RunLoop.current.run(until: Date().addingTimeInterval(0.3))
    check(board.string(forType: .string) == "while paused", "nothing is received while paused")
}
