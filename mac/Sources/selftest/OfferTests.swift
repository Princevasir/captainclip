import AppKit
import ClipBridgeCore
import Foundation

/// Puts files on a pasteboard the way Finder does: file URL(s) plus the names as plain text.
func finderCopy(_ urls: [URL], on board: NSPasteboard) {
    board.clearContents()
    let items = urls.map { url -> NSPasteboardItem in
        let item = NSPasteboardItem()
        item.setString(url.absoluteString, forType: .fileURL)
        item.setString(url.lastPathComponent, forType: .string)
        return item
    }
    board.writeObjects(items)
}

func watcherFileTests() {
    let dir = tempDir("watch")
    defer { try? FileManager.default.removeItem(at: dir) }
    let pdf = randomFile(dir, name: "report.pdf", bytes: 10_000)
    let other = randomFile(dir, name: "notes.docx", bytes: 10_000)
    let big = dir.appendingPathComponent("huge.mov")
    FileManager.default.createFile(atPath: big.path, contents: nil)
    if let h = try? FileHandle(forWritingTo: big) { try? h.truncate(atOffset: 30_000_000); try? h.close() }
    let folder = dir.appendingPathComponent("Folder")
    try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)

    let board = NSPasteboard(name: NSPasteboard.Name("clipbridge.files.\(UUID().uuidString)"))
    defer { board.releaseGlobally() }
    let watcher = PasteboardWatcher(pasteboard: board, interval: 0.05)
    let texts = Recorder<String>(), files = Recorder<URL>(), ignored = Recorder<Int>()
    watcher.onText = { texts.add($0) }
    watcher.onFile = { files.add($0) }
    watcher.onIgnoredFiles = { ignored.add(1) }
    watcher.start()
    defer { watcher.stop() }

    finderCopy([pdf], on: board)
    check(waitUntil { files.all.map(\.lastPathComponent) == ["report.pdf"] }, "Finder copy of one file is reported as a file")
    RunLoop.current.run(until: Date().addingTimeInterval(0.2))
    check(texts.all.isEmpty, "file name from a Finder copy is NOT sent as text")

    finderCopy([pdf, other], on: board)
    check(waitUntil { ignored.all.count == 1 }, "copying two files is ignored")
    finderCopy([folder], on: board)
    check(waitUntil { ignored.all.count == 2 }, "copying a folder is ignored")
    finderCopy([big], on: board)
    check(waitUntil { ignored.all.count == 3 }, "copying a 30 MB file is ignored")
    RunLoop.current.run(until: Date().addingTimeInterval(0.2))
    check(files.all.count == 1 && texts.all.isEmpty, "ignored copies send neither file nor names")
    check(watcher.currentText() == nil, "currentText is nil while files are on the pasteboard")

    finderCopy([pdf], on: board)
    _ = waitUntil { files.all.count == 2 }
    check(watcher.currentFile()?.lastPathComponent == "report.pdf", "currentFile reports the single copied file")

    board.clearContents(); board.setString("plain text", forType: .string)
    check(waitUntil { texts.all == ["plain text"] }, "plain text copies still work")
    check(watcher.currentFile() == nil, "no current file after a text copy")
}

func offerStoreTests() throws {
    let dir = tempDir("offer")
    defer { try? FileManager.default.removeItem(at: dir) }
    let pdf = randomFile(dir, name: "report.pdf", bytes: 1234)
    var now = Date(timeIntervalSince1970: 1_000_000)
    let store = OfferStore(now: { now })

    let offer = try store.make(url: pdf)
    guard case .offer(let id, let name, let mime, let size, _) = offer else { check(false, "make returns an offer"); return }
    check(name == "report.pdf" && mime == "application/pdf" && size == 1234, "offer has name, PDF mime type and size")
    check(store.current == offer, "store remembers the current offer")
    check((try? store.lookup(id: id).get()) == pdf, "current offer can be fetched")

    now += 9 * 60
    check((try? store.lookup(id: id).get()) == pdf, "still fetchable at 9 minutes")
    now += 2 * 60
    check(store.lookup(id: id) == .failure(.expired), "expired after 10 minutes")

    let fresh = try store.make(url: pdf)
    guard case .offer(let id2, _, _, _, _) = fresh else { return }
    check(store.lookup(id: id) == .failure(.expired), "old offer id is expired once replaced")
    try Data("changed!".utf8).write(to: pdf)
    check(store.lookup(id: id2) == .failure(.changed), "file edited after copy is refused as changed")

    _ = try store.make(url: pdf)
    store.clear()
    check(store.current == nil, "clear removes the offer")
}
