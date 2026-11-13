import AppKit
import ClipBridgeCore

/// Google Play phone screenshots (1440 × 2560, 9:16): a two-part headline with one highlighted word, a short line, and
/// a phone showing the real app screen (or the app's own cards, drawn with the same text the app shows).
enum StoreArt {
    static let width = 1440, height = 2560  // 9:16 — Play requires 16:9 or 9:16
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
        var spotlight: NSPoint? = nil  // outline around this point, in phone coordinates (phone is 1000 × 2080)
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
        text("CaptainClip", NSFont.systemFont(ofSize: 54, weight: .semibold), .white, at: NSPoint(x: 252, y: 150))
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
        let scale: CGFloat = 0.85
        phoneImage(p).draw(in: NSRect(x: (CGFloat(width) - phoneSize.width * scale) / 2, y: 930,
                                      width: phoneSize.width * scale, height: phoneSize.height * scale),
                           from: .zero, operation: .sourceOver, fraction: 1, respectFlipped: true, hints: nil)
    }

    /// The phone (bezel + screen, plus the optional spotlight), at its native 1000 × 2080 size; scale it freely.
    static let phoneSize = NSSize(width: 1000, height: 2080)
    static func phoneImage(_ p: Panel) -> NSImage {
        NSImage(size: phoneSize, flipped: true) { phone in
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
            return true
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

    static let soft = NSColor(srgbRed: 0xE8 / 255, green: 0xE7 / 255, blue: 0xFD / 255, alpha: 1)

    /// The app's top: logo row, then the colour status card with the white "Send clipboard to Mac" button.
    /// Returns the y (screen coordinates) just below it.
    @discardableResult
    static func header(in s: NSRect, pill: String = "Connected", title: String = "MacBook Air",
                       detail: String = "Same Wi-Fi · end-to-end encrypted", send: Bool = true, pause: Bool = true) -> CGFloat {
        BrandIcon.app(side: 92).draw(in: NSRect(x: s.minX + 44, y: s.minY + 110, width: 92, height: 92))
        text("CaptainClip", NSFont.systemFont(ofSize: 46, weight: .semibold), ink, at: NSPoint(x: s.minX + 150, y: s.minY + 126))
        let h: CGFloat = send ? (pause ? 560 : 470) : 330
        let hero = NSRect(x: s.minX + 40, y: s.minY + 240, width: s.width - 80, height: h)
        NSGradient(starting: BrandIcon.teal, ending: BrandIcon.indigo)?.draw(in: NSBezierPath(roundedRect: hero, xRadius: 56, yRadius: 56), angle: -35)
        // pill
        let pw = (pill as NSString).size(withAttributes: [.font: NSFont.systemFont(ofSize: 28, weight: .semibold)]).width + 80
        let pillR = NSRect(x: hero.minX + 44, y: hero.minY + 44, width: pw, height: 62)
        NSColor(white: 1, alpha: 0.2).setFill(); NSBezierPath(roundedRect: pillR, xRadius: 31, yRadius: 31).fill()
        NSColor(srgbRed: 0x4A / 255, green: 0xDE / 255, blue: 0x80 / 255, alpha: 1).setFill()
        NSBezierPath(ovalIn: NSRect(x: pillR.minX + 24, y: pillR.midY - 10, width: 20, height: 20)).fill()
        text(pill, NSFont.systemFont(ofSize: 28, weight: .semibold), .white, at: NSPoint(x: pillR.minX + 56, y: pillR.minY + 13))
        devicesGlyph(NSRect(x: hero.maxX - 130, y: hero.minY + 36, width: 86, height: 86))
        text(title, NSFont.systemFont(ofSize: 56, weight: .bold), .white, at: NSPoint(x: hero.minX + 44, y: hero.minY + 140))
        text(detail, NSFont.systemFont(ofSize: 31), NSColor(white: 1, alpha: 0.88), at: NSPoint(x: hero.minX + 46, y: hero.minY + 222))
        if send {
            let b = NSRect(x: hero.minX + 44, y: hero.minY + 320, width: hero.width - 88, height: 108)
            NSColor.white.setFill(); NSBezierPath(roundedRect: b, xRadius: 54, yRadius: 54).fill()
            let f = NSFont.systemFont(ofSize: 34, weight: .semibold)
            let label = "↑  Send clipboard to Mac"
            let size = (label as NSString).size(withAttributes: [.font: f])
            text(label, f, BrandIcon.indigo, at: NSPoint(x: b.midX - size.width / 2, y: b.midY - size.height / 2))
            if pause {
                let pf = NSFont.systemFont(ofSize: 31, weight: .semibold), pl = "Pause syncing"
                let pw = (pl as NSString).size(withAttributes: [.font: pf]).width
                text(pl, pf, .white, at: NSPoint(x: hero.midX - pw / 2, y: b.maxY + 26))
            }
        }
        return hero.maxY + 36
    }

    /// Phone + laptop outline, white.
    static func devicesGlyph(_ r: NSRect) {
        let u = r.width / 24
        let p = NSBezierPath()
        p.appendRoundedRect(NSRect(x: r.minX + 2.5 * u, y: r.minY + 7.5 * u, width: 7 * u, height: 12.5 * u), xRadius: u, yRadius: u)
        p.appendRoundedRect(NSRect(x: r.minX + 12.5 * u, y: r.minY + 5 * u, width: 9 * u, height: 10 * u), xRadius: u, yRadius: u)
        p.move(to: NSPoint(x: r.minX + 11 * u, y: r.minY + 18.5 * u)); p.line(to: NSPoint(x: r.minX + 23 * u, y: r.minY + 18.5 * u))
        p.lineWidth = 1.8 * u; p.lineCapStyle = .round
        NSColor(white: 1, alpha: 0.85).setStroke(); p.stroke()
    }

    enum Kind { case fromMac, toMac, file }

    /// Round badge with an arrow (from/to Mac) or a page (file), like the app's history rows.
    static func badge(_ kind: Kind, center c: NSPoint, size: CGFloat) {
        let color: NSColor = kind == .fromMac ? BrandIcon.teal : kind == .toMac ? BrandIcon.indigo : grey
        color.withAlphaComponent(0.16).setFill()
        NSBezierPath(ovalIn: NSRect(x: c.x - size / 2, y: c.y - size / 2, width: size, height: size)).fill()
        let u = size / 40
        let p = NSBezierPath()
        switch kind {
        case .fromMac:
            p.move(to: NSPoint(x: c.x, y: c.y - 9 * u)); p.line(to: NSPoint(x: c.x, y: c.y + 4 * u))
            p.move(to: NSPoint(x: c.x - 6 * u, y: c.y - 2 * u)); p.line(to: NSPoint(x: c.x, y: c.y + 4 * u)); p.line(to: NSPoint(x: c.x + 6 * u, y: c.y - 2 * u))
            p.move(to: NSPoint(x: c.x - 9 * u, y: c.y + 10 * u)); p.line(to: NSPoint(x: c.x + 9 * u, y: c.y + 10 * u))
        case .toMac:
            p.move(to: NSPoint(x: c.x, y: c.y + 4 * u)); p.line(to: NSPoint(x: c.x, y: c.y - 9 * u))
            p.move(to: NSPoint(x: c.x - 6 * u, y: c.y - 3 * u)); p.line(to: NSPoint(x: c.x, y: c.y - 9 * u)); p.line(to: NSPoint(x: c.x + 6 * u, y: c.y - 3 * u))
            p.move(to: NSPoint(x: c.x - 9 * u, y: c.y + 10 * u)); p.line(to: NSPoint(x: c.x + 9 * u, y: c.y + 10 * u))
        case .file:
            p.appendRoundedRect(NSRect(x: c.x - 7 * u, y: c.y - 10 * u, width: 14 * u, height: 20 * u), xRadius: 2 * u, yRadius: 2 * u)
        }
        p.lineWidth = 3.2 * u; p.lineCapStyle = .round; p.lineJoinStyle = .round
        color.setStroke(); p.stroke()
    }

    /// A settings section like the app's: grey heading, then a white card of rows (icon, title, current value).
    /// Returns the y below it.
    @discardableResult
    static func section(in s: NSRect, y: CGFloat, title: String, rows: [(Glyph, String, String)]) -> CGFloat {
        text(title, NSFont.systemFont(ofSize: 28, weight: .semibold), grey, at: NSPoint(x: s.minX + 72, y: s.minY + y))
        let rowH: CGFloat = 132
        let c = card(in: s, y: y + 56, height: CGFloat(rows.count) * rowH + 24)
        for (i, r) in rows.enumerated() {
            let top = c.minY + 12 + CGFloat(i) * rowH
            let center = NSPoint(x: c.minX + 82, y: top + rowH / 2)
            soft.setFill(); NSBezierPath(ovalIn: NSRect(x: center.x - 38, y: center.y - 38, width: 76, height: 76)).fill()
            glyph(r.0, center: center)
            text(r.1, NSFont.systemFont(ofSize: 34, weight: .semibold), ink, at: NSPoint(x: c.minX + 150, y: top + 24))
            text(r.2, NSFont.systemFont(ofSize: 28), grey, at: NSPoint(x: c.minX + 150, y: top + 72))
            text("›", NSFont.systemFont(ofSize: 44), grey, at: NSPoint(x: c.maxX - 62, y: top + 36))
        }
        return y + 56 + c.height + 36
    }

    enum Glyph { case history, panel, link }

    /// Clock (history), grid with plus (notification panel buttons), link (background connection) — line icons like the app's.
    static func glyph(_ g: Glyph, center c: NSPoint) {
        let p = NSBezierPath()
        switch g {
        case .history:
            p.appendOval(in: NSRect(x: c.x - 19, y: c.y - 19, width: 38, height: 38))
            p.move(to: NSPoint(x: c.x, y: c.y - 10)); p.line(to: c); p.line(to: NSPoint(x: c.x + 8, y: c.y + 6))
        case .panel:
            for (dx, dy) in [(-17, -17), (3, -17), (-17, 3)] { p.appendRect(NSRect(x: c.x + CGFloat(dx), y: c.y + CGFloat(dy), width: 14, height: 14)) }
            p.move(to: NSPoint(x: c.x + 10, y: c.y + 3)); p.line(to: NSPoint(x: c.x + 10, y: c.y + 17))
            p.move(to: NSPoint(x: c.x + 3, y: c.y + 10)); p.line(to: NSPoint(x: c.x + 17, y: c.y + 10))
        case .link:
            p.appendRoundedRect(NSRect(x: c.x - 20, y: c.y - 8, width: 22, height: 16), xRadius: 8, yRadius: 8)
            p.appendRoundedRect(NSRect(x: c.x - 2, y: c.y - 8, width: 22, height: 16), xRadius: 8, yRadius: 8)
        }
        p.lineWidth = 4.5; p.lineCapStyle = .round; p.lineJoinStyle = .round
        BrandIcon.indigo.setStroke(); p.stroke()
    }

    /// One history/recent row: badge, text, time.
    static func clipRow(_ kind: Kind, _ title: String, _ time: String, in r: NSRect, meta: String? = nil) {
        badge(kind, center: NSPoint(x: r.minX + 52, y: r.midY), size: 76)
        let f = NSFont.systemFont(ofSize: 33)
        let style = NSMutableParagraphStyle(); style.lineBreakMode = .byTruncatingTail
        if let meta {
            NSAttributedString(string: title, attributes: [.font: f, .foregroundColor: ink, .paragraphStyle: style])
                .draw(with: NSRect(x: r.minX + 112, y: r.minY + 24, width: r.width - 140, height: 44), options: [.usesLineFragmentOrigin])
            text(meta, NSFont.systemFont(ofSize: 27), grey, at: NSPoint(x: r.minX + 112, y: r.minY + 76))
        } else {
            NSAttributedString(string: title, attributes: [.font: f, .foregroundColor: ink, .paragraphStyle: style])
                .draw(with: NSRect(x: r.minX + 112, y: r.midY - 22, width: r.width - 260, height: 44), options: [.usesLineFragmentOrigin])
            let tw = (time as NSString).size(withAttributes: [.font: NSFont.systemFont(ofSize: 28)]).width
            text(time, NSFont.systemFont(ofSize: 28), grey, at: NSPoint(x: r.maxX - 30 - tw, y: r.midY - 18))
        }
    }

}
