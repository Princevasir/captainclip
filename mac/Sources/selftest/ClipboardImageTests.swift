import AppKit
import ClipBridgeCore
import Foundation

func pngData(_ side: Int = 64, color: NSColor = .systemTeal) -> Data {
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: side, pixelsHigh: side, bitsPerSample: 8,
                               samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB,
                               bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    color.setFill(); NSRect(x: 0, y: 0, width: side, height: side).fill()
    NSGraphicsContext.restoreGraphicsState()
    return rep.representation(using: .png, properties: [:])!
}

private let pngSignature = Data([0x89, 0x50, 0x4E, 0x47])

func clipboardImageTests() throws {
    let board = NSPasteboard(name: NSPasteboard.Name("clipbridge.img.\(UUID().uuidString)"))
    defer { board.releaseGlobally() }
    let png = pngData()

    board.clearContents(); board.setData(png, forType: .png)
    check(ClipboardImage.png(from: board) == png, "PNG on the clipboard is read as-is")

    let tiff = NSImage(data: png)!.tiffRepresentation!
    board.clearContents(); board.setData(tiff, forType: .tiff)
    check(ClipboardImage.png(from: board)?.prefix(4) == pngSignature, "TIFF (e.g. ⌃⇧⌘4 screenshot) is converted to PNG")

    board.clearContents(); board.setString("text", forType: .string)
    check(ClipboardImage.png(from: board) == nil, "text copies have no image")

    let dir = tempDir("imgsave")
    defer { try? FileManager.default.removeItem(at: dir) }
    let when = DateComponents(calendar: .current, year: 2026, month: 11, day: 10, hour: 19, minute: 40, second: 12).date!
    let first = try ClipboardImage.save(png, in: dir, now: when)
    check(first.lastPathComponent == "Screenshot 2026-11-10 at 19.40.12.png", "saved image gets a Screenshot-style name")
    check((try? Data(contentsOf: first)) == png, "saved image bytes intact")
    let second = try ClipboardImage.save(png, in: dir, now: when.addingTimeInterval(5))
    check(!FileManager.default.fileExists(atPath: first.path) && FileManager.default.fileExists(atPath: second.path),
          "older cached image is deleted when a new one is saved")
}

func watcherImageTests() {
    let board = NSPasteboard(name: NSPasteboard.Name("clipbridge.wimg.\(UUID().uuidString)"))
    defer { board.releaseGlobally() }
    let watcher = PasteboardWatcher(pasteboard: board, interval: 0.05)
    let images = Recorder<Data>(), texts = Recorder<String>(), files = Recorder<URL>()
    watcher.onImage = { images.add($0) }
    watcher.onText = { texts.add($0) }
    watcher.onFile = { files.add($0) }
    watcher.start(); defer { watcher.stop() }

    board.clearContents(); board.setData(pngData(), forType: .png)
    check(waitUntil { images.all.count == 1 }, "copying an image reports it as an image")
    check(texts.all.isEmpty, "image copy is not sent as text")

    let dir = tempDir("wimg"); defer { try? FileManager.default.removeItem(at: dir) }
    let pdf = randomFile(dir, name: "a.pdf", bytes: 100)
    board.clearContents()
    let item = NSPasteboardItem()
    item.setString(pdf.absoluteString, forType: .fileURL)
    item.setData(NSImage(data: pngData())!.tiffRepresentation!, forType: .tiff)  // Finder adds an icon image
    board.writeObjects([item])
    check(waitUntil { files.all.count == 1 }, "Finder file copy with an icon image is still a file")
    check(images.all.count == 1, "…and not an image")

    let secret = NSPasteboardItem()
    secret.setData(pngData(), forType: .png)
    secret.setData(Data(), forType: NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType"))
    board.clearContents(); board.writeObjects([secret])
    RunLoop.current.run(until: Date().addingTimeInterval(0.3))
    check(images.all.count == 1, "concealed image is ignored")

    watcher.writeImage(pngData(8, color: .red))
    RunLoop.current.run(until: Date().addingTimeInterval(0.3))
    check(images.all.count == 1, "an image ClipBridge itself writes is not reported back")
    check(board.data(forType: .png) != nil && board.data(forType: .tiff) != nil, "writeImage puts PNG and TIFF")
}

func bridgeImageOfferTests() throws {
    let key = ClipCrypto.randomKey()
    let board = NSPasteboard(name: NSPasteboard.Name("clipbridge.bimg.\(UUID().uuidString)"))
    defer { board.releaseGlobally() }
    let cache = tempDir("bimg-cache"), into = tempDir("bimg-in")
    defer { [cache, into].forEach { try? FileManager.default.removeItem(at: $0) } }
    let server = Server(key: key, macId: "0123456789abcdef", advertise: false)
    let bridge = Bridge(server: server, watcher: PasteboardWatcher(pasteboard: board, interval: 0.05), imageCache: cache)
    let statuses = Recorder<Server.Status>()
    bridge.onStatus = { statuses.add($0) }
    try bridge.start(); defer { bridge.stop() }
    _ = waitUntil { server.port != nil }
    let (phone, _, _) = makeClient(server, key: key); defer { phone.stop() }
    let messages = Recorder<Message>()
    phone.onMessage = { messages.add($0) }
    _ = waitUntil { statuses.all.last == .connected(device: "probe") }

    let png = pngData(120)
    board.clearContents(); board.setData(png, forType: .png)
    check(waitUntil { messages.all.contains { if case .offer = $0 { return true } else { return false } } }, "copied image is offered")
    guard case .offer(let id, let name, let mime, let size, _)? = messages.all.first(where: { if case .offer = $0 { return true } else { return false } }) else { return }
    check(name.hasPrefix("Screenshot ") && name.hasSuffix(".png") && mime == "image/png" && size == Int64(png.count),
          "image offer is a Screenshot …png of the right size")
    let got = Recorder<URL>()
    phone.expectFile(id: id, into: into) { if case .success(let u) = $0 { got.add(u) } }
    phone.send(message: .fetch(id: id))
    check(waitUntil(5) { got.all.count == 1 }, "phone can fetch the image on paste")
    check(got.all.first.flatMap { try? Data(contentsOf: $0) } == png, "fetched image identical")
}
