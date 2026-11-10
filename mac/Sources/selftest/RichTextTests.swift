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
