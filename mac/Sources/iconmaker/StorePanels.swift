import AppKit
import ClipBridgeCore

/// The Play Store panels. The Quick Settings panel is a real screenshot; the app screens are drawn with the
/// app's own layout and wording and made-up sample clips (so no real copies ever appear).
enum StorePanels {
    static func all(screenshots: URL) -> [StoreArt.Panel] {
        [
            .init(file: "store-1-copy-paste.png", lead: "Copy on Mac.", plain: "Paste on", highlight: "Android",
                  sub: "Text, links and formatting arrive on your phone the moment you copy.",
                  screen: .cards(mainScreen)),
            .init(file: "store-2-one-tap.png", lead: "One tap.", plain: "Sent to", highlight: "Mac",
                  sub: "Add Send to Mac next to Torch in Quick Settings. Copy, tap, paste on your Mac.",
                  screen: .image(screenshots.appendingPathComponent("2-quick-toggle.png")),
                  spotlight: NSPoint(x: 797, y: 1630)),  // the Send to Mac toggle in that screenshot
            .init(file: "store-3-history.png", lead: "Copied earlier?", plain: "Find it", highlight: "again",
                  sub: "Search recent clips you moved between your Mac and phone. Tap to copy, or send it again.",
                  screen: .cards(historyScreen)),
            .init(file: "store-4-files.png", lead: "Copy files.", plain: "Share", highlight: "anywhere",
                  sub: "⌘C up to 20 files on your Mac, then share them to Gmail, WhatsApp or Drive.",
                  screen: .cards(filesScreen)),
            .init(file: "store-5-bluetooth.png", lead: "No Wi-Fi?", plain: "Still", highlight: "connected",
                  sub: "Away from home? ClipBridge switches to Bluetooth by itself, and back to Wi-Fi.",
                  screen: .cards(bluetoothScreen)),
            .init(file: "store-6-private.png", lead: "Your clipboard.", plain: "Only", highlight: "yours",
                  sub: "Straight from your phone to your Mac, end-to-end encrypted. No account, no cloud.",
                  screen: .cards(privacyScreen)),
        ]
    }

    private static let label = NSFont.systemFont(ofSize: 28, weight: .semibold)
    private static let body = NSFont.systemFont(ofSize: 33)

    static func mainScreen(_ s: NSRect) {
        var y = StoreArt.header(in: s) - s.minY
        let c = StoreArt.card(in: s, y: y, height: 470)
        StoreArt.text("RECENT CLIPS", label, StoreArt.grey, at: NSPoint(x: c.minX + 44, y: c.minY + 40))
        StoreArt.text("See all ›", NSFont.systemFont(ofSize: 30, weight: .semibold), BrandIcon.indigo, at: NSPoint(x: c.maxX - 170, y: c.minY + 38))
        let rows: [(StoreArt.Kind, String, String)] = [
            (.fromMac, "https://github.com/Princevasir/clipbridge", "now"),
            (.toMac, "Flat 4B, Rose Court, MG Road", "5 min"),
            (.fromMac, "Meeting moved to 4:30 — see you there", "1 h"),
        ]
        for (i, r) in rows.enumerated() {
            StoreArt.clipRow(r.0, r.1, r.2, in: NSRect(x: c.minX + 24, y: c.minY + 100 + CGFloat(i) * 118, width: c.width - 48, height: 110))
        }
        y += 470 + 30
        StoreArt.tiles(in: s, y: y)
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
        entry(.fromMac, "Rose Court visitor parking: gate 2", "From Mac · 2 h ago")
        section("YESTERDAY")
        entry(.file, "Rose-garden-plan.pdf · 1.2 MB", "From Mac · Yesterday")
        entry(.fromMac, "rose.courtsociety@example.com", "From Mac · Yesterday")
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
        StoreArt.tiles(in: s, y: y)
        let g = NSPoint(x: s.midX, y: s.minY + y + 420)
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
