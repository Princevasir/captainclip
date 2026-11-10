import AppKit
import ClipBridgeCore
import Foundation

func watcherTests() {
    check(PasteboardWatcher.shouldSkip(types: ["org.nspasteboard.ConcealedType", "public.utf8-plain-text"]),
          "password-manager (concealed) copies are skipped")
    check(PasteboardWatcher.shouldSkip(types: ["org.nspasteboard.TransientType", "public.utf8-plain-text"]),
          "transient copies are skipped")
    check(!PasteboardWatcher.shouldSkip(types: ["public.utf8-plain-text"]), "normal text is not skipped")

    let board = NSPasteboard(name: NSPasteboard.Name("clipbridge.selftest.\(UUID().uuidString)"))
    defer { board.releaseGlobally() }
    let watcher = PasteboardWatcher(pasteboard: board, interval: 0.05)
    let seen = Recorder<String>()
    watcher.onText = { seen.add($0) }
    watcher.start()
    defer { watcher.stop() }

    board.clearContents()
    board.setString("copied on mac", forType: .string)
    check(waitUntil { seen.all == ["copied on mac"] }, "watcher reports new copied text")

    watcher.write("from phone")
    RunLoop.current.run(until: Date().addingTimeInterval(0.3))
    check(board.string(forType: .string) == "from phone", "write puts text on the pasteboard")
    check(seen.all == ["copied on mac"], "watcher does not report its own write")

    let item = NSPasteboardItem()
    item.setString("hunter2", forType: .string)
    item.setData(Data(), forType: NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType"))
    board.clearContents()
    board.writeObjects([item])
    RunLoop.current.run(until: Date().addingTimeInterval(0.3))
    check(!seen.all.contains("hunter2"), "concealed password is never reported")
}

func keyStoreTests() {
    let store = KeyStore(service: "app.clipbridge.selftest")
    store.delete()
    check(store.load() == nil, "empty keystore loads nil")
    let key = ClipCrypto.randomKey()
    check(store.save(macId: "abcdef0123456789", key: key), "keystore saves")
    let loaded = store.load()
    check(loaded?.macId == "abcdef0123456789" && loaded?.key == key, "keystore loads what was saved")
    store.delete()
    check(store.load() == nil, "keystore delete removes item")
}

func networkInfoTests() {
    if let ip = NetworkInfo.primaryIPv4() {
        let parts = ip.split(separator: ".").compactMap { Int($0) }
        check(parts.count == 4 && parts.allSatisfy { (0...255).contains($0) }, "primary IPv4 is dotted quad")
        check(!ip.hasPrefix("127.") && !ip.hasPrefix("169.254."), "primary IPv4 is not loopback/link-local")
    } else {
        print("note: no LAN IPv4 found (offline?) — skipping IP checks")
    }
}
