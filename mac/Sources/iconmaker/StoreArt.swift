import AppKit
import ClipBridgeCore

/// Google Play phone screenshots (1440 × 2880): a two-part headline with one highlighted word, a short line, and
/// a phone showing the real app screen (or the app's own cards, drawn with the same text the app shows).
enum StoreArt {
    static let width = 1440, height = 2880
    static let ink = NSColor(srgbRed: 0x1F / 255, green: 0x29 / 255, blue: 0x37 / 255, alpha: 1)
    static let grey = NSColor(srgbRed: 0x6B / 255, green: 0x72 / 255, blue: 0x80 / 255, alpha: 1)
    static let screenBg = NSColor(srgbRed: 0xF3 / 255, green: 0xF4 / 255, blue: 0xF8 / 255, alpha: 1)
    static let green = NSColor(srgbRed: 0x16 / 255, green: 0xA3 / 255, blue: 0x4A / 255, alpha: 1)

    enum Screen {
        case image(URL)                      // a real screenshot (1440 × 2880)
        case cards((NSRect) -> Void)         // app cards drawn into the phone screen
    }

    struct Panel {
        let file: String
        let lead: String       // first line, plain
        let plain: String      // second line before the highlighted word
        let highlight: String  // highlighted word (white pill)
        let sub: String
        let screen: Screen
        var spotlight: NSPoint? = nil  // circle this point (canvas coordinates)
    }

    static func write(panels: [Panel], to folder: URL) throws {
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        for panel in panels {
            try BrandIcon.png(width: width, height: height, alpha: false, to: folder.appendingPathComponent(panel.file)) { rect in
                draw(panel, in: rect)
            }
        }
    }

    private static func draw(_ p: Panel, in rect: NSRect) {
        NSGradient(starting: BrandIcon.teal, ending: BrandIcon.indigo)?.draw(in: rect, angle: 65)
        // Brand row
        BrandIcon.app(side: 132).draw(in: NSRect(x: 110, y: 120, width: 132, height: 132))
        text("ClipBridge", NSFont.systemFont(ofSize: 54, weight: .semibold), .white, at: NSPoint(x: 252, y: 150))
        // Headline
        let big = NSFont.systemFont(ofSize: 132, weight: .heavy)
        text(p.lead, big, .white, at: NSPoint(x: 120, y: 330))
        let plainWidth = p.plain.isEmpty ? 0 : (p.plain as NSString).size(withAttributes: [.font: big]).width + 62
        if !p.plain.isEmpty { text(p.plain, big, .white, at: NSPoint(x: 120, y: 490)) }
        let hw = (p.highlight as NSString).size(withAttributes: [.font: big]).width
        let pill = NSRect(x: 120 + plainWidth - 22, y: 492, width: hw + 44, height: 162)
        NSColor.white.setFill()
        NSBezierPath(roundedRect: pill, xRadius: 34, yRadius: 34).fill()
        text(p.highlight, big, BrandIcon.indigo, at: NSPoint(x: 120 + plainWidth, y: 490))
        // Sub line (wraps)
        let sub = NSAttributedString(string: p.sub, attributes: [.font: NSFont.systemFont(ofSize: 52, weight: .medium),
                                                                  .foregroundColor: NSColor(white: 1, alpha: 0.9)])
        sub.draw(with: NSRect(x: 124, y: 700, width: 1200, height: 200), options: [.usesLineFragmentOrigin])
        // Phone (runs off the bottom edge)
        let phone = NSRect(x: 220, y: 960, width: 1000, height: 2080)
        NSColor(white: 0.08, alpha: 1).setFill()
        NSBezierPath(roundedRect: phone, xRadius: 120, yRadius: 120).fill()
        let screen = phone.insetBy(dx: 26, dy: 26)
        NSGraphicsContext.saveGraphicsState()
        NSBezierPath(roundedRect: screen, xRadius: 96, yRadius: 96).addClip()
        switch p.screen {
        case .image(let url):
            if let img = NSImage(contentsOf: url) {
                let h = screen.width * img.size.height / img.size.width
                img.draw(in: NSRect(x: screen.minX, y: screen.minY, width: screen.width, height: h),
                         from: .zero, operation: .sourceOver, fraction: 1, respectFlipped: true, hints: nil)
            }
        case .cards(let drawCards):
            screenBg.setFill(); screen.fill()
            drawCards(screen)
        }
        NSGraphicsContext.restoreGraphicsState()
        if let c = p.spotlight {
            let ring = NSBezierPath(roundedRect: NSRect(x: c.x - 120, y: c.y - 140, width: 240, height: 290), xRadius: 56, yRadius: 56)
            ring.lineWidth = 14
            NSColor(srgbRed: 0xFA / 255, green: 0xCC / 255, blue: 0x15 / 255, alpha: 1).setStroke()
            ring.stroke()
        }
    }

    static func text(_ s: String, _ font: NSFont, _ color: NSColor, at p: NSPoint) {
        (s as NSString).draw(at: p, withAttributes: [.font: font, .foregroundColor: color])
    }

    /// A white rounded card like the app's, returning its rect.
    @discardableResult
    static func card(in screen: NSRect, y: CGFloat, height: CGFloat) -> NSRect {
        let r = NSRect(x: screen.minX + 48, y: screen.minY + y, width: screen.width - 96, height: height)
        NSColor.white.setFill()
        NSBezierPath(roundedRect: r, xRadius: 44, yRadius: 44).fill()
        return r
    }

    static func button(_ title: String, in r: NSRect, primary: Bool) {
        (primary ? BrandIcon.indigo : NSColor(srgbRed: 0xE8 / 255, green: 0xE7 / 255, blue: 0xFD / 255, alpha: 1)).setFill()
        NSBezierPath(roundedRect: r, xRadius: r.height / 2, yRadius: r.height / 2).fill()
        let f = NSFont.systemFont(ofSize: 34, weight: .semibold)
        let size = (title as NSString).size(withAttributes: [.font: f])
        text(title, f, primary ? .white : BrandIcon.indigo, at: NSPoint(x: r.midX - size.width / 2, y: r.midY - size.height / 2))
    }

    /// App header + connection card, as in the app.
    static func header(in s: NSRect, detail: String) {
        BrandIcon.app(side: 120).draw(in: NSRect(x: s.minX + 40, y: s.minY + 120, width: 120, height: 120))
        text("ClipBridge", NSFont.systemFont(ofSize: 54, weight: .bold), ink, at: NSPoint(x: s.minX + 180, y: s.minY + 128))
        text("Mac ↔ phone clipboard", NSFont.systemFont(ofSize: 32), grey, at: NSPoint(x: s.minX + 182, y: s.minY + 196))
        let c = card(in: s, y: 290, height: 200)
        green.setFill()
        NSBezierPath(ovalIn: NSRect(x: c.minX + 44, y: c.minY + 62, width: 30, height: 30)).fill()
        text("Connected to MacBook Air", NSFont.systemFont(ofSize: 42, weight: .semibold), ink, at: NSPoint(x: c.minX + 100, y: c.minY + 48))
        text(detail, NSFont.systemFont(ofSize: 32), grey, at: NSPoint(x: c.minX + 100, y: c.minY + 112))
    }
}
