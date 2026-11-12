import AppKit
import ClipBridgeCore

/// The Play Store panels. The notification-panel screen is a real screenshot; the app screens are drawn with the
/// app's own layout and wording and made-up sample clips (so no real copies ever appear).
enum StorePanels {
    static func all(screenshots: URL) -> [StoreArt.Panel] {
        [
            .init(file: "store-1-copy-paste.png", lead: "Copy on Mac.", plain: "Paste on", highlight: "Android",
                  sub: "Nothing to tap: what you copy on your Mac is already on your phone. Just paste.",
                  screen: .cards(pasteScreen)),
            .init(file: "store-2-select-send.png", lead: "Select text.", plain: "Send to", highlight: "Mac",
                  sub: "Send to Mac sits right next to Copy, in any app. Then paste on your Mac.",
                  screen: .cards(selectionScreen)),
            .init(file: "store-3-one-tap.png", lead: "One tap.", plain: "From", highlight: "anywhere",
                  sub: "Notification panel, home-screen widget, or long-press the ClipBridge icon.",
                  screen: .image(screenshots.appendingPathComponent("2-quick-toggle.png")),
                  spotlight: NSPoint(x: 797, y: 1630)),  // the Send to Mac toggle in that screenshot
            .init(file: "store-4-pictures.png", lead: "Screenshots too.", plain: "Paste on", highlight: "phone",
                  sub: "Take a screenshot or copy a picture on your Mac, then paste it into any app.",
                  screen: .cards(pictureScreen)),
            .init(file: "store-5-files.png", lead: "Files.", plain: "Both", highlight: "ways",
                  sub: "⌘C up to 20 files on your Mac and share them anywhere. Share → Send to Mac goes back.",
                  screen: .cards(filesScreen)),
            .init(file: "store-6-history.png", lead: "Sent earlier?", plain: "Find it", highlight: "again",
                  sub: "Search what you sent from your phone. Tap to copy, or send it again.",
                  screen: .cards(historyScreen)),
            .init(file: "store-7-bluetooth.png", lead: "No Wi-Fi?", plain: "Still", highlight: "connected",
                  sub: "Away from home? ClipBridge switches to Bluetooth by itself, and back to Wi-Fi.",
                  screen: .cards(bluetoothScreen)),
            .init(file: "store-8-private.png", lead: "Your clipboard.", plain: "Only", highlight: "yours",
                  sub: "Straight from your phone to your Mac, end-to-end encrypted. No account, no cloud.",
                  screen: .cards(privacyScreen)),
        ]
    }

    // MARK: other apps (drawn generically: a notes app and the system text menu)

    private static func appBar(_ s: NSRect, _ title: String) {
        StoreArt.text("‹", NSFont.systemFont(ofSize: 64, weight: .medium), StoreArt.ink, at: NSPoint(x: s.minX + 44, y: s.minY + 96))
        StoreArt.text(title, NSFont.systemFont(ofSize: 46, weight: .semibold), StoreArt.ink, at: NSPoint(x: s.minX + 104, y: s.minY + 110))
    }

    private static func wrapped(_ str: String, _ font: NSFont, _ color: NSColor, in r: NSRect) {
        NSAttributedString(string: str, attributes: [.font: font, .foregroundColor: color]).draw(with: r, options: [.usesLineFragmentOrigin])
    }

    /// The system's floating text menu (white, shadowed), with one item emphasised.
    private static func textMenu(_ items: [String], at p: NSPoint, emphasise: String? = nil) {
        let f = NSFont.systemFont(ofSize: 34, weight: .medium)
        let widths = items.map { ($0 as NSString).size(withAttributes: [.font: f]).width + 56 }
        let bar = NSRect(x: p.x, y: p.y, width: widths.reduce(0, +) + 16, height: 100)
        NSGraphicsContext.saveGraphicsState()
        let shadow = NSShadow(); shadow.shadowBlurRadius = 24; shadow.shadowOffset = NSSize(width: 0, height: -6)
        shadow.shadowColor = NSColor(white: 0, alpha: 0.22); shadow.set()
        NSColor.white.setFill(); NSBezierPath(roundedRect: bar, xRadius: 50, yRadius: 50).fill()
        NSGraphicsContext.restoreGraphicsState()
        var x = bar.minX + 8
        for (item, w) in zip(items, widths) {
            let on = item == emphasise
            if on {
                let r = NSRect(x: x, y: bar.minY + 8, width: w, height: 84)
                StoreArt.soft.setFill(); NSBezierPath(roundedRect: r, xRadius: 42, yRadius: 42).fill()
                let ring = NSBezierPath(roundedRect: r.insetBy(dx: -14, dy: -14), xRadius: 56, yRadius: 56)
                ring.lineWidth = 10
                NSColor(srgbRed: 0xFA / 255, green: 0xCC / 255, blue: 0x15 / 255, alpha: 1).setStroke(); ring.stroke()
            }
            StoreArt.text(item, on ? NSFont.systemFont(ofSize: 34, weight: .bold) : f, on ? BrandIcon.indigo : StoreArt.ink,
                          at: NSPoint(x: x + 28, y: bar.minY + 29))
            x += w
        }
    }

    /// A small pill saying where the clipboard came from.
    private static func sourceChip(_ s: NSRect, y: CGFloat, _ label: String) {
        let f = NSFont.systemFont(ofSize: 30, weight: .semibold)
        let w = (label as NSString).size(withAttributes: [.font: f]).width + 120
        let r = NSRect(x: s.midX - w / 2, y: s.minY + y, width: w, height: 76)
        BrandIcon.indigo.setFill(); NSBezierPath(roundedRect: r, xRadius: 38, yRadius: 38).fill()
        StoreArt.badge(.fromMac, center: NSPoint(x: r.minX + 46, y: r.midY), size: 50)
        StoreArt.text(label, f, .white, at: NSPoint(x: r.minX + 86, y: r.minY + 20))
    }

    static func pasteScreen(_ s: NSRect) {
        appBar(s, "New note")
        let c = StoreArt.card(in: s, y: 420, height: 520)
        StoreArt.text("Thursday", NSFont.systemFont(ofSize: 44, weight: .bold), StoreArt.ink, at: NSPoint(x: c.minX + 44, y: c.minY + 44))
        wrapped("Meeting moved to 4:30 — see you in room 2B. Agenda: https://example.com/q3-plan",
                NSFont.systemFont(ofSize: 38), StoreArt.ink, in: NSRect(x: c.minX + 44, y: c.minY + 130, width: c.width - 88, height: 300))
        textMenu(["Paste", "Select all"], at: NSPoint(x: c.minX + 30, y: s.minY + 300), emphasise: "Paste")
        sourceChip(s, y: 1000, "Copied on your Mac · just now")
    }

    static func selectionScreen(_ s: NSRect) {
        appBar(s, "Messages")
        let c = StoreArt.card(in: s, y: 430, height: 320)
        let f = NSFont.systemFont(ofSize: 38)
        StoreArt.text("Here's the address for Saturday:", f, StoreArt.ink, at: NSPoint(x: c.minX + 44, y: c.minY + 50))
        let line = "Flat 4B, Rose Court, MG Road, Pune 411001"
        let lw = (line as NSString).size(withAttributes: [.font: f]).width
        let sel = NSRect(x: c.minX + 38, y: c.minY + 120, width: lw + 12, height: 58)
        NSColor(srgbRed: 0xC7 / 255, green: 0xD2 / 255, blue: 0xFE / 255, alpha: 1).setFill(); NSBezierPath(roundedRect: sel, xRadius: 8, yRadius: 8).fill()
        StoreArt.text(line, f, StoreArt.ink, at: NSPoint(x: c.minX + 44, y: c.minY + 124))
        BrandIcon.indigo.setFill()
        NSBezierPath(ovalIn: NSRect(x: sel.minX - 22, y: sel.minY - 10, width: 36, height: 36)).fill()
        NSBezierPath(ovalIn: NSRect(x: sel.maxX - 14, y: sel.maxY - 10, width: 36, height: 36)).fill()
        StoreArt.text("Gate code is 2580. See you at 7!", f, StoreArt.ink, at: NSPoint(x: c.minX + 44, y: c.minY + 210))
        textMenu(["Cut", "Copy", "Send to Mac", "⋮"], at: NSPoint(x: s.minX + 60, y: s.minY + 300), emphasise: "Send to Mac")
        let t = StoreArt.card(in: s, y: 800, height: 150)
        StoreArt.badge(.toMac, center: NSPoint(x: t.minX + 80, y: t.midY), size: 76)
        StoreArt.text("Sent to your Mac — paste it there", NSFont.systemFont(ofSize: 34, weight: .medium), StoreArt.ink,
                      at: NSPoint(x: t.minX + 140, y: t.minY + 52))
    }

    static func pictureScreen(_ s: NSRect) {
        appBar(s, "New message")
        let c = StoreArt.card(in: s, y: 420, height: 820)
        StoreArt.text("To: Design team", NSFont.systemFont(ofSize: 34), StoreArt.grey, at: NSPoint(x: c.minX + 44, y: c.minY + 40))
        let img = NSRect(x: c.minX + 44, y: c.minY + 120, width: c.width - 88, height: 520)
        NSGraphicsContext.saveGraphicsState()
        NSBezierPath(roundedRect: img, xRadius: 28, yRadius: 28).addClip()
        NSGradient(starting: NSColor(srgbRed: 0.55, green: 0.78, blue: 0.98, alpha: 1), ending: NSColor(srgbRed: 0.98, green: 0.85, blue: 0.70, alpha: 1))?.draw(in: img, angle: -90)
        NSColor(srgbRed: 1, green: 0.85, blue: 0.3, alpha: 1).setFill()
        NSBezierPath(ovalIn: NSRect(x: img.maxX - 210, y: img.minY + 70, width: 110, height: 110)).fill()
        let m = NSBezierPath(); m.move(to: NSPoint(x: img.minX, y: img.maxY)); m.line(to: NSPoint(x: img.minX + 230, y: img.minY + 220))
        m.line(to: NSPoint(x: img.minX + 400, y: img.minY + 380)); m.line(to: NSPoint(x: img.minX + 600, y: img.minY + 160))
        m.line(to: NSPoint(x: img.maxX, y: img.maxY)); m.close()
        NSColor(srgbRed: 0.18, green: 0.45, blue: 0.42, alpha: 1).setFill(); m.fill()
        NSGraphicsContext.restoreGraphicsState()
        StoreArt.text("Screenshot 2026-11-12 at 10.42.png", NSFont.systemFont(ofSize: 28), StoreArt.grey, at: NSPoint(x: c.minX + 44, y: c.minY + 670))
        textMenu(["Paste", "Select all"], at: NSPoint(x: c.minX + 30, y: s.minY + 300), emphasise: "Paste")
        sourceChip(s, y: 1300, "Screenshot from your Mac")
    }

    private static let label = NSFont.systemFont(ofSize: 28, weight: .semibold)
    private static let body = NSFont.systemFont(ofSize: 33)

    static func mainScreen(_ s: NSRect) {
        var y = StoreArt.header(in: s) - s.minY
        let c = StoreArt.card(in: s, y: y, height: 470)
        StoreArt.text("RECENT CLIPS", label, StoreArt.grey, at: NSPoint(x: c.minX + 44, y: c.minY + 40))
        StoreArt.text("See all ›", NSFont.systemFont(ofSize: 30, weight: .semibold), BrandIcon.indigo, at: NSPoint(x: c.maxX - 170, y: c.minY + 38))
        let rows: [(StoreArt.Kind, String, String)] = [
            (.toMac, "https://github.com/Princevasir/clipbridge", "now"),
            (.toMac, "Flat 4B, Rose Court, MG Road", "5 min"),
            (.toMac, "Meeting moved to 4:30 — see you there", "1 h"),
        ]
        for (i, r) in rows.enumerated() {
            StoreArt.clipRow(r.0, r.1, r.2, in: NSRect(x: c.minX + 24, y: c.minY + 100 + CGFloat(i) * 118, width: c.width - 48, height: 110))
        }
        y += 470 + 30
        StoreArt.section(in: s, y: y, title: "THIS PHONE", rows: [
            (.history, "Clipboard history", "On · last 50 clips, 7 days"),
            (.panel, "Notification panel buttons", "Both added"),
            (.link, "Background connection", "On — your Mac is linked"),
        ])
    }

    static func historyScreen(_ s: NSRect) {
        StoreArt.text("‹", NSFont.systemFont(ofSize: 64, weight: .medium), StoreArt.ink, at: NSPoint(x: s.minX + 44, y: s.minY + 96))
        StoreArt.text("Clipboard history", NSFont.systemFont(ofSize: 46, weight: .semibold), StoreArt.ink, at: NSPoint(x: s.minX + 104, y: s.minY + 110))
        StoreArt.text("⋮", NSFont.systemFont(ofSize: 48, weight: .bold), StoreArt.ink, at: NSPoint(x: s.maxX - 84, y: s.minY + 106))
        let search = NSRect(x: s.minX + 44, y: s.minY + 210, width: s.width - 88, height: 110)
        NSColor.white.setFill(); NSBezierPath(roundedRect: search, xRadius: 55, yRadius: 55).fill()
        StoreArt.text("🔍  rose", NSFont.systemFont(ofSize: 34), StoreArt.ink, at: NSPoint(x: search.minX + 40, y: search.minY + 32))
        var y: CGFloat = 360
        func section(_ name: String) { StoreArt.text(name, label, StoreArt.grey, at: NSPoint(x: s.minX + 60, y: s.minY + y)); y += 56 }
        func entry(_ k: StoreArt.Kind, _ t: String, _ meta: String) {
            let r = StoreArt.card(in: s, y: y, height: 136)
            StoreArt.clipRow(k, t, "", in: NSRect(x: r.minX + 10, y: r.minY + 8, width: r.width - 20, height: 120), meta: meta)
            y += 156
        }
        section("TODAY")
        entry(.toMac, "Flat 4B, Rose Court, MG Road, Pune", "To Mac · 5 min ago")
        entry(.toMac, "Rose Court visitor parking: gate 2", "To Mac · 2 h ago")
        section("YESTERDAY")
        entry(.file, "Rose-garden-plan.pdf · 1.2 MB", "From Mac · opened here · Yesterday")
        entry(.toMac, "rose.courtsociety@example.com", "To Mac · Yesterday")
        section("EARLIER")
        entry(.toMac, "Rose Court maintenance — paid ✓", "To Mac · 4 days ago")
    }

    static func filesScreen(_ s: NSRect) {
        let top = StoreArt.header(in: s) - s.minY
        let c = StoreArt.card(in: s, y: top, height: 560)
        StoreArt.text("FROM YOUR MAC", label, StoreArt.grey, at: NSPoint(x: c.minX + 44, y: c.minY + 40))
        StoreArt.text("3 files", NSFont.systemFont(ofSize: 48, weight: .bold), StoreArt.ink, at: NSPoint(x: c.minX + 44, y: c.minY + 90))
        NSAttributedString(string: "Quarterly report.pdf, Invoice.pdf, Floor plan.pdf · 4.2 MB",
                           attributes: [.font: body, .foregroundColor: StoreArt.grey])
            .draw(with: NSRect(x: c.minX + 44, y: c.minY + 160, width: c.width - 88, height: 100), options: [.usesLineFragmentOrigin])
        let w = (c.width - 88 - 24) / 2
        StoreArt.button("Save all", in: NSRect(x: c.minX + 44, y: c.minY + 290, width: w, height: 104), primary: true)
        StoreArt.button("Share all…", in: NSRect(x: c.minX + 68 + w, y: c.minY + 290, width: w, height: 104), primary: false)
        StoreArt.text("Nothing is downloaded until you do.", NSFont.systemFont(ofSize: 28), StoreArt.grey, at: NSPoint(x: c.minX + 44, y: c.minY + 440))
        let m = StoreArt.card(in: s, y: top + 590, height: 500)
        StoreArt.text("New email", NSFont.systemFont(ofSize: 38, weight: .bold), StoreArt.ink, at: NSPoint(x: m.minX + 44, y: m.minY + 40))
        for (i, name) in ["Quarterly report.pdf", "Invoice.pdf", "Floor plan.pdf"].enumerated() {
            let r = NSRect(x: m.minX + 44, y: m.minY + 120 + CGFloat(i) * 120, width: m.width - 88, height: 98)
            StoreArt.screenBg.setFill(); NSBezierPath(roundedRect: r, xRadius: 24, yRadius: 24).fill()
            NSColor(srgbRed: 0xDC / 255, green: 0x26 / 255, blue: 0x26 / 255, alpha: 1).setFill()
            NSBezierPath(roundedRect: NSRect(x: r.minX + 24, y: r.minY + 20, width: 58, height: 58), xRadius: 10, yRadius: 10).fill()
            StoreArt.text("PDF", NSFont.systemFont(ofSize: 19, weight: .bold), .white, at: NSPoint(x: r.minX + 33, y: r.minY + 36))
            StoreArt.text(name, body, StoreArt.ink, at: NSPoint(x: r.minX + 106, y: r.minY + 28))
        }
    }

    static func bluetoothScreen(_ s: NSRect) {
        let y = StoreArt.header(in: s, detail: "Bluetooth · end-to-end encrypted") - s.minY
        let g = NSPoint(x: s.midX, y: s.minY + y + 260)
        BrandIcon.indigo.setFill()
        NSBezierPath(ovalIn: NSRect(x: g.x - 80, y: g.y - 80, width: 160, height: 160)).fill()
        let u: CGFloat = 16, p = NSBezierPath()
        p.move(to: NSPoint(x: g.x - 2 * u, y: g.y - 2 * u)); p.line(to: NSPoint(x: g.x + 2 * u, y: g.y + 2 * u))
        p.line(to: NSPoint(x: g.x, y: g.y + 4 * u)); p.line(to: NSPoint(x: g.x, y: g.y - 4 * u))
        p.line(to: NSPoint(x: g.x + 2 * u, y: g.y - 2 * u)); p.line(to: NSPoint(x: g.x - 2 * u, y: g.y + 2 * u))
        p.lineWidth = 0.9 * u; p.lineJoinStyle = .round; p.lineCapStyle = .round
        NSColor.white.setStroke(); p.stroke()
        let line = "Wi-Fi first. Bluetooth when it can't reach."
        let f = NSFont.systemFont(ofSize: 33, weight: .medium)
        let w = (line as NSString).size(withAttributes: [.font: f]).width
        StoreArt.text(line, f, StoreArt.grey, at: NSPoint(x: s.midX - w / 2, y: g.y + 120))
    }

    static func privacyScreen(_ s: NSRect) {
        let top = StoreArt.header(in: s, send: false) - s.minY
        let items = ["No account or sign-in", "No cloud — phone to Mac directly", "End-to-end encrypted (AES-256)",
                     "History stays on your phone, encrypted", "Passwords are never sent", "No ads, no tracking · open source"]
        let c = StoreArt.card(in: s, y: top, height: CGFloat(items.count) * 124 + 70)
        for (i, item) in items.enumerated() {
            let y = c.minY + 46 + CGFloat(i) * 124
            StoreArt.green.setFill()
            NSBezierPath(ovalIn: NSRect(x: c.minX + 44, y: y + 8, width: 62, height: 62)).fill()
            let tick = NSBezierPath()
            tick.move(to: NSPoint(x: c.minX + 60, y: y + 40)); tick.line(to: NSPoint(x: c.minX + 71, y: y + 52))
            tick.line(to: NSPoint(x: c.minX + 91, y: y + 28))
            tick.lineWidth = 8; tick.lineCapStyle = .round; tick.lineJoinStyle = .round
            NSColor.white.setStroke(); tick.stroke()
            StoreArt.text(item, NSFont.systemFont(ofSize: 34, weight: .medium), StoreArt.ink, at: NSPoint(x: c.minX + 132, y: y + 18))
        }
    }
}
