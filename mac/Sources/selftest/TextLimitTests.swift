import AppKit
import ClipBridgeCore
import Foundation

func textLimitTests() throws {
    check(TextLimit.maxBytes == 262_144, "text cap is 256 KB")
    check(TextLimit.fits(String(repeating: "a", count: 262_144)), "256 KB of ASCII fits")
    check(!TextLimit.fits(String(repeating: "a", count: 262_145)), "256 KB + 1 does not fit")
    check(TextLimit.fits(String(repeating: "é", count: 131_072)), "cap counts UTF-8 bytes (fits)")
    check(!TextLimit.fits(String(repeating: "é", count: 131_073)), "cap counts UTF-8 bytes (too big)")
    check(!TextLimit.fits(""), "empty text does not fit")

    let key = ClipCrypto.randomKey()
    let (server, serverClips, statuses) = try makeServer(key: key)
    defer { server.stop() }
    _ = waitUntil { server.port != nil }
    let (phone, _, _) = makeClient(server, key: key)
    defer { phone.stop() }
    _ = waitUntil { statuses.all.last == .connected(device: "probe") }

    check(!server.send(text: String(repeating: "b", count: 300_000)), "server refuses 300 KB text")
    phone.send(text: String(repeating: "c", count: 300_000))
    phone.send(text: "small after big")
    check(waitUntil { serverClips.all == ["small after big"] }, "server drops oversize clip from phone, keeps small one")
}

func bridgeSkipsHugeCopy() throws {
    let key = ClipCrypto.randomKey()
    let board = NSPasteboard(name: NSPasteboard.Name("clipbridge.huge.\(UUID().uuidString)"))
    defer { board.releaseGlobally() }
    let server = Server(key: key, macId: "0123456789abcdef", advertise: false)
    let bridge = Bridge(server: server, watcher: PasteboardWatcher(pasteboard: board, interval: 0.05))
    let statuses = Recorder<Server.Status>()
    bridge.onStatus = { statuses.add($0) }
    try bridge.start()
    defer { bridge.stop() }
    _ = waitUntil { server.port != nil }
    let (phone, phoneClips, _) = makeClient(server, key: key)
    defer { phone.stop() }
    _ = waitUntil { statuses.all.last == .connected(device: "probe") }

    board.clearContents(); board.setString(String(repeating: "z", count: 800_000), forType: .string)
    RunLoop.current.run(until: Date().addingTimeInterval(0.3))
    board.clearContents(); board.setString("normal", forType: .string)
    check(waitUntil { phoneClips.all.contains("normal") }, "normal copy still arrives after a huge one")
    check(!phoneClips.all.contains { $0.count > 262_144 }, "huge Mac copy is never sent to phone")
}
