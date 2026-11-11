import AppKit
import ClipBridgeCore

/// The five Play Store panels. Screens are either real screenshots or the app's own cards with the app's own text.
enum StorePanels {
    static func all(screenshots: URL) -> [StoreArt.Panel] {
        [
            .init(file: "store-1-copy-paste.png", lead: "Copy on Mac.", plain: "Paste on", highlight: "Android",
                  sub: "Text, links and formatting arrive on your phone the moment you copy.",
                  screen: .image(screenshots.appendingPathComponent("1-connected.png"))),
            .init(file: "store-2-one-tap.png", lead: "One tap.", plain: "Sent to", highlight: "Mac",
                  sub: "Add Send to Mac next to Torch in Quick Settings. Copy, tap, paste on your Mac.",
                  screen: .image(screenshots.appendingPathComponent("2-quick-toggle.png")),
                  spotlight: NSPoint(x: 1017, y: 2590)),  // the Send to Mac toggle in that screenshot
            .init(file: "store-3-files.png", lead: "Copy files.", plain: "Share", highlight: "anywhere",
                  sub: "⌘C up to 20 files on your Mac, then share them to Gmail, WhatsApp or Drive.",
                  screen: .cards(filesScreen)),
            .init(file: "store-4-bluetooth.png", lead: "No Wi-Fi?", plain: "Still", highlight: "connected",
                  sub: "Away from home? ClipBridge switches to Bluetooth by itself, and back to Wi-Fi.",
                  screen: .cards(bluetoothScreen)),
            .init(file: "store-5-private.png", lead: "Your clipboard.", plain: "Only", highlight: "yours",
                  sub: "Straight from your phone to your Mac, end-to-end encrypted. No account, no cloud.",
                  screen: .cards(privacyScreen)),
        ]
    }

    private static let label = NSFont.systemFont(ofSize: 30, weight: .semibold)
    private static let body = NSFont.systemFont(ofSize: 34)

    static func filesScreen(_ s: NSRect) {
        StoreArt.header(in: s, detail: "Same Wi-Fi · end-to-end encrypted")
        let c = StoreArt.card(in: s, y: 530, height: 700)
        StoreArt.text("FROM YOUR MAC", label, StoreArt.grey, at: NSPoint(x: c.minX + 48, y: c.minY + 48))
        StoreArt.text("3 files", NSFont.systemFont(ofSize: 52, weight: .bold), StoreArt.ink, at: NSPoint(x: c.minX + 48, y: c.minY + 100))
        NSAttributedString(string: "Quarterly report.pdf, Invoice.pdf, Floor plan.pdf · 4.2 MB · Available for 9 more minutes",
                           attributes: [.font: body, .foregroundColor: StoreArt.grey])
            .draw(with: NSRect(x: c.minX + 48, y: c.minY + 180, width: c.width - 96, height: 140), options: [.usesLineFragmentOrigin])
        NSAttributedString(string: "Share it to Gmail, WhatsApp or Drive, or save a copy here. Nothing is downloaded until you do.",
                           attributes: [.font: NSFont.systemFont(ofSize: 30), .foregroundColor: StoreArt.grey])
            .draw(with: NSRect(x: c.minX + 48, y: c.minY + 340, width: c.width - 96, height: 140), options: [.usesLineFragmentOrigin])
        let w = (c.width - 96 - 24) / 2
        StoreArt.button("Save all", in: NSRect(x: c.minX + 48, y: c.minY + 520, width: w, height: 112), primary: true)
        StoreArt.button("Share all…", in: NSRect(x: c.minX + 72 + w, y: c.minY + 520, width: w, height: 112), primary: false)
        // The share result: three attachments in a new email.
        let m = StoreArt.card(in: s, y: 1280, height: 560)
        StoreArt.text("New email", NSFont.systemFont(ofSize: 40, weight: .bold), StoreArt.ink, at: NSPoint(x: m.minX + 48, y: m.minY + 44))
        for (i, name) in ["Quarterly report.pdf", "Invoice.pdf", "Floor plan.pdf"].enumerated() {
            let r = NSRect(x: m.minX + 48, y: m.minY + 130 + CGFloat(i) * 130, width: m.width - 96, height: 104)
            StoreArt.screenBg.setFill(); NSBezierPath(roundedRect: r, xRadius: 24, yRadius: 24).fill()
            NSColor(srgbRed: 0xDC / 255, green: 0x26 / 255, blue: 0x26 / 255, alpha: 1).setFill()
            NSBezierPath(roundedRect: NSRect(x: r.minX + 26, y: r.minY + 22, width: 60, height: 60), xRadius: 10, yRadius: 10).fill()
            StoreArt.text("PDF", NSFont.systemFont(ofSize: 20, weight: .bold), .white, at: NSPoint(x: r.minX + 36, y: r.minY + 38))
            StoreArt.text(name, body, StoreArt.ink, at: NSPoint(x: r.minX + 112, y: r.minY + 30))
        }
    }

    static func bluetoothScreen(_ s: NSRect) {
        StoreArt.header(in: s, detail: "Via Bluetooth · end-to-end encrypted")
        let c = StoreArt.card(in: s, y: 530, height: 560)
        StoreArt.text("QUICK ACTIONS", label, StoreArt.grey, at: NSPoint(x: c.minX + 48, y: c.minY + 48))
        StoreArt.button("Send clipboard to Mac", in: NSRect(x: c.minX + 48, y: c.minY + 120, width: c.width - 96, height: 112), primary: false)
        StoreArt.button("Add Send to Mac to Quick Settings", in: NSRect(x: c.minX + 48, y: c.minY + 256, width: c.width - 96, height: 112), primary: false)
        StoreArt.button("Pause syncing", in: NSRect(x: c.minX + 48, y: c.minY + 392, width: c.width - 96, height: 112), primary: false)
        // Path illustration: phone ··· Bluetooth ··· Mac
        let row = s.minY + 1260
        bluetoothGlyph(center: NSPoint(x: s.midX, y: row + 90), size: 150)
        StoreArt.text("Wi-Fi first. Bluetooth when it can't reach.", NSFont.systemFont(ofSize: 34, weight: .medium), StoreArt.grey,
                      at: NSPoint(x: s.minX + 100, y: row + 220))
    }

    private static func bluetoothGlyph(center c: NSPoint, size: CGFloat) {
        BrandIcon.indigo.setFill()
        NSBezierPath(ovalIn: NSRect(x: c.x - size / 2, y: c.y - size / 2, width: size, height: size)).fill()
        let p = NSBezierPath()
        let u = size / 10
        p.move(to: NSPoint(x: c.x - 2 * u, y: c.y - 2 * u))
        p.line(to: NSPoint(x: c.x + 2 * u, y: c.y + 2 * u))
        p.line(to: NSPoint(x: c.x, y: c.y + 4 * u))
        p.line(to: NSPoint(x: c.x, y: c.y - 4 * u))
        p.line(to: NSPoint(x: c.x + 2 * u, y: c.y - 2 * u))
        p.line(to: NSPoint(x: c.x - 2 * u, y: c.y + 2 * u))
        p.lineWidth = u * 0.9; p.lineJoinStyle = .round; p.lineCapStyle = .round
        NSColor.white.setStroke(); p.stroke()
    }

    static func privacyScreen(_ s: NSRect) {
        StoreArt.header(in: s, detail: "Same Wi-Fi · end-to-end encrypted")
        let items = ["No account or sign-in", "No cloud — phone to Mac directly", "End-to-end encrypted (AES-256)",
                     "Passwords are never sent", "No ads, no tracking", "Open source"]
        let c = StoreArt.card(in: s, y: 530, height: CGFloat(items.count) * 130 + 80)
        for (i, item) in items.enumerated() {
            let y = c.minY + 50 + CGFloat(i) * 130
            StoreArt.green.setFill()
            NSBezierPath(ovalIn: NSRect(x: c.minX + 48, y: y + 10, width: 64, height: 64)).fill()
            let tick = NSBezierPath()
            tick.move(to: NSPoint(x: c.minX + 64, y: y + 43)); tick.line(to: NSPoint(x: c.minX + 76, y: y + 55))
            tick.line(to: NSPoint(x: c.minX + 97, y: y + 30))
            tick.lineWidth = 8; tick.lineCapStyle = .round; tick.lineJoinStyle = .round
            NSColor.white.setStroke(); tick.stroke()
            StoreArt.text(item, NSFont.systemFont(ofSize: 38, weight: .medium), StoreArt.ink, at: NSPoint(x: c.minX + 140, y: y + 18))
        }
    }
}
