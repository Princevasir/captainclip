import AppKit
import ClipBridgeCore
import Foundation

private func board(_ tag: String) -> NSPasteboard { NSPasteboard(name: NSPasteboard.Name("clipbridge.rich.\(tag).\(UUID().uuidString)")) }

func richTextTests() throws {
    // Reading
    let b1 = board("html"); defer { b1.releaseGlobally() }
    b1.clearContents(); b1.setString("Hello", forType: .string); b1.setString("<b>Hello</b>", forType: .html)
    check(RichText.html(from: b1) == "<b>Hello</b>", "HTML copies are read as HTML")

    let b2 = board("rtf"); defer { b2.releaseGlobally() }
    let bold = NSAttributedString(string: "Bold words", attributes: [.font: NSFont.boldSystemFont(ofSize: 12)])
    b2.clearContents(); b2.setString("Bold words", forType: .string)
    b2.setData(try bold.data(from: NSRange(location: 0, length: bold.length), documentAttributes: [.documentType: NSAttributedString.DocumentType.rtf]), forType: .rtf)
    let converted = RichText.html(from: b2) ?? ""
    check(converted.contains("Bold words") && (converted.contains("font-weight: bold") || converted.contains("<b>") || converted.lowercased().contains("bold")),
          "RTF-only copies (Notes, Pages) are converted to HTML keeping bold")

    let b3 = board("plain"); defer { b3.releaseGlobally() }
    b3.clearContents(); b3.setString("just text", forType: .string)
    check(RichText.html(from: b3) == nil, "plain copies have no HTML")

    // Writing
    let b4 = board("write"); defer { b4.releaseGlobally() }
    RichText.write(text: "Hi there", html: "<p><b>Hi</b> there</p>", to: b4)
    check(b4.string(forType: .string) == "Hi there", "write keeps the plain text")
    check(b4.string(forType: .html)?.contains("<b>Hi</b>") == true, "write puts HTML")
    check(b4.data(forType: .rtf) != nil, "write also puts RTF for Pages/Notes/Word")

    // Size rule
    check(RichText.fitting(text: "a", html: "<b>a</b>") == "<b>a</b>", "small HTML is kept")
    check(RichText.fitting(text: "a", html: String(repeating: "x", count: 300_000)) == nil, "HTML pushing past 256 KB is dropped")
}

func bridgeRichTextTests() throws {
    let key = ClipCrypto.randomKey()
    let b = board("bridge"); defer { b.releaseGlobally() }
    let server = Server(key: key, macId: "0123456789abcdef", advertise: false)
    let watcher = PasteboardWatcher(pasteboard: b, interval: 0.05)
    let bridge = Bridge(server: server, watcher: watcher)
    let statuses = Recorder<Server.Status>()
    bridge.onStatus = { statuses.add($0) }
    try bridge.start(); defer { bridge.stop() }
    _ = waitUntil { server.port != nil }
    let (phone, plainClips, _) = makeClient(server, key: key); defer { phone.stop() }
    let rich = Recorder<String?>()
    phone.onRichClip = { _, html in rich.add(html) }
    _ = waitUntil { statuses.all.last == .connected(device: "probe") }

    b.clearContents(); b.setString("Formatted", forType: .string); b.setString("<b>Formatted</b>", forType: .html)
    check(waitUntil { rich.all.contains("<b>Formatted</b>") }, "Mac formatted copy reaches the phone with HTML")

    b.clearContents(); b.setString("huge", forType: .string); b.setString(String(repeating: "y", count: 300_000), forType: .html)
    check(waitUntil { plainClips.all.contains("huge") }, "huge HTML copy still sends the plain text")
    check(rich.all.last == .some(nil), "huge HTML itself is dropped")

    phone.send(text: "From phone", html: "<i>From phone</i>")
    check(waitUntil { b.string(forType: .html)?.contains("<i>From phone</i>") == true }, "phone HTML lands on the Mac clipboard")
    check(b.data(forType: .rtf) != nil && b.string(forType: .string) == "From phone", "with plain text and RTF too")
    let before = plainClips.all.count
    RunLoop.current.run(until: Date().addingTimeInterval(0.4))
    check(plainClips.all.count == before, "phone HTML is not echoed back")

    let item = NSPasteboardItem()
    item.setString("secret", forType: .string); item.setString("<b>secret</b>", forType: .html)
    item.setData(Data(), forType: NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType"))
    b.clearContents(); b.writeObjects([item])
    RunLoop.current.run(until: Date().addingTimeInterval(0.4))
    check(!plainClips.all.contains("secret"), "concealed formatted copy is not sent")
}

func richTextSafetyTests() {
    let html = #"<p>Hi <img src="https://tracker.example/pixel.png"> <link rel="stylesheet" href="https://x.example/a.css"><script>alert(1)</script><iframe src="https://x.example"></iframe><b>there</b></p>"#
    let safe = RichText.importable(html)
    check(!safe.contains("<img") && !safe.contains("<link") && !safe.contains("<script") && !safe.contains("<iframe"),
          "remote images, stylesheets, scripts and frames are removed before converting to RTF")
    check(safe.contains("<b>there</b>") && safe.contains("Hi"), "formatting and text survive")

    let board = NSPasteboard(name: NSPasteboard.Name("clipbridge.safe.\(UUID().uuidString)"))
    defer { board.releaseGlobally() }
    let started = Date()
    RichText.write(text: "Hi there", html: html, to: board)
    check(Date().timeIntervalSince(started) < 2, "writing HTML with remote resources does not hang")
    check(board.string(forType: .html) == html, "the original HTML is still offered to apps as-is")
    check(board.data(forType: .rtf) != nil && board.string(forType: .string) == "Hi there", "RTF and text written too")
}

private func runs(_ rtf: Data) -> (bold: Int, italic: Int, link: Int, background: Int, color: Int, fonts: Set<String>) {
    let a = NSAttributedString(rtf: rtf, documentAttributes: nil) ?? NSAttributedString()
    var r = (bold: 0, italic: 0, link: 0, background: 0, color: 0, fonts: Set<String>())
    a.enumerateAttributes(in: NSRange(location: 0, length: a.length)) { attrs, range, _ in
        if let f = attrs[.font] as? NSFont {
            r.fonts.insert(f.familyName ?? "?")
            if f.fontDescriptor.symbolicTraits.contains(.bold) { r.bold += range.length }
            if f.fontDescriptor.symbolicTraits.contains(.italic) { r.italic += range.length }
        }
        if attrs[.link] != nil { r.link += range.length }
        if attrs[.backgroundColor] != nil { r.background += range.length }
        if attrs[.foregroundColor] != nil { r.color += range.length }
    }
    return r
}

func richTextCleanRTFTests() {
    // Shape of a copy from GitHub in Chrome: semi-bold in a web font, colours, white background, a link.
    let html = #"<strong style="font-weight: 600; color: rgb(31,35,40); font-family: 'Mona Sans VF', sans-serif; background-color: rgb(255,255,255);">Usage:</strong><span style="color: rgb(31,35,40); background-color: rgb(255,255,255);"> run </span><em>quickly</em> <a href="https://example.com">docs</a>"#
    let board = NSPasteboard(name: NSPasteboard.Name("clipbridge.clean.\(UUID().uuidString)"))
    defer { board.releaseGlobally() }
    RichText.write(text: "Usage: run quickly docs", html: html, to: board)
    guard let rtf = board.data(forType: .rtf) else { check(false, "RTF written"); return }
    let r = runs(rtf)
    check(r.bold >= 6, "semi-bold (600) text becomes real bold in RTF (\(r.bold) chars)")
    check(r.italic >= 7, "italics kept (\(r.italic) chars)")
    check(r.link >= 4, "links kept")
    check(r.background == 0, "source background colours are dropped (no white boxes in dark mode)")
    check(r.color == 0 || r.color == r.link, "source text colours are dropped (links may keep theirs)")
    check(!r.fonts.contains("Mona Sans VF"), "source web fonts are replaced by the system font")
}
