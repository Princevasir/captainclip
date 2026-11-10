import AppKit
import ClipBridgeCore
import Foundation

func resendOnConnectTests() throws {
    let key = ClipCrypto.randomKey()
    let board = NSPasteboard(name: NSPasteboard.Name("clipbridge.resend.\(UUID().uuidString)"))
    defer { board.releaseGlobally() }
    let server = Server(key: key, macId: "0123456789abcdef", advertise: false)
    let bridge = Bridge(server: server, watcher: PasteboardWatcher(pasteboard: board, interval: 0.05))
    let statuses = Recorder<Server.Status>()
    bridge.onStatus = { statuses.add($0) }
    try bridge.start()
    defer { bridge.stop() }
    _ = waitUntil { server.port != nil }

    board.clearContents(); board.setString("copied while phone away", forType: .string)
    RunLoop.current.run(until: Date().addingTimeInterval(0.3))
    let (phone, phoneClips, _) = makeClient(server, key: key)
    check(waitUntil { phoneClips.all == ["copied while phone away"] }, "Mac copy made while phone was away arrives on connect")

    phone.send(text: "from phone")
    check(waitUntil { board.string(forType: .string) == "from phone" }, "phone text lands on Mac")
    phone.stop()
    _ = waitUntil { statuses.all.last == .waiting }
    let (phone2, phone2Clips, _) = makeClient(server, key: key)
    defer { phone2.stop() }
    _ = waitUntil { statuses.all.last == .connected(device: "probe") }
    RunLoop.current.run(until: Date().addingTimeInterval(0.4))
    check(phone2Clips.all.isEmpty, "text that came from the phone is not sent back on reconnect")
    phone2.stop()
    _ = waitUntil { statuses.all.last == .waiting }

    let item = NSPasteboardItem()
    item.setString("secret", forType: .string)
    item.setData(Data(), forType: NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType"))
    board.clearContents(); board.writeObjects([item])
    RunLoop.current.run(until: Date().addingTimeInterval(0.2))
    let (phone3, phone3Clips, _) = makeClient(server, key: key)
    defer { phone3.stop() }
    _ = waitUntil { statuses.all.last == .connected(device: "probe") }
    RunLoop.current.run(until: Date().addingTimeInterval(0.4))
    check(!phone3Clips.all.contains("secret"), "concealed password is not sent on connect")
}
