import AppKit
import ClipBridgeCore

/// Simple macOS-style pictures for the phone's "Set up your Mac" guide (drawn, so no real screen, apps or
/// pairing code ever appears). 1080 × 640 JPEGs (about 80 KB each) for the app's res/drawable-nodpi.
enum GuideArt {
    static let w = 1080, h = 640
    static let yellow = NSColor(srgbRed: 0xFA / 255, green: 0xCC / 255, blue: 0x15 / 255, alpha: 1)
    static let blue = NSColor(srgbRed: 0x0A / 255, green: 0x84 / 255, blue: 0xFF / 255, alpha: 1)
    static let ink = NSColor(srgbRed: 0x1D / 255, green: 0x1D / 255, blue: 0x1F / 255, alpha: 1)
    static let grey = NSColor(srgbRed: 0x6E / 255, green: 0x6E / 255, blue: 0x73 / 255, alpha: 1)
    static let line = NSColor(white: 0.88, alpha: 1)

    static func writeAll(to folder: URL) throws {
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let pictures: [(String, (NSRect) -> Void)] = [
            ("guide_mac_1", download), ("guide_mac_2", install), ("guide_mac_3", openAnyway),
            ("guide_mac_4", allow), ("guide_mac_5", pair),
        ]
        for (name, draw) in pictures {
            let png = folder.appendingPathComponent(name + ".png")
            try BrandIcon.png(width: w, height: h, alpha: false, to: png) { r in
                desktop(r); draw(r)
            }
            guard let rep = NSBitmapImageRep(data: try Data(contentsOf: png)),
                  let jpeg = rep.representation(using: .jpeg, properties: [.compressionFactor: 0.88])
            else { throw CocoaError(.fileWriteUnknown) }
            try jpeg.write(to: folder.appendingPathComponent(name + ".jpg"))
            try FileManager.default.removeItem(at: png)
        }
    }

    // MARK: building blocks

    static func desktop(_ r: NSRect) {
        NSGradient(starting: BrandIcon.teal.blended(withFraction: 0.55, of: .white)!,
                   ending: BrandIcon.indigo.blended(withFraction: 0.55, of: .white)!)?.draw(in: r, angle: 30)
    }

    static func text(_ s: String, _ size: CGFloat, _ color: NSColor = ink, weight: NSFont.Weight = .regular, at p: NSPoint) {
        (s as NSString).draw(at: p, withAttributes: [.font: NSFont.systemFont(ofSize: size, weight: weight), .foregroundColor: color])
    }

    static func width(_ s: String, _ size: CGFloat, weight: NSFont.Weight = .regular) -> CGFloat {
        (s as NSString).size(withAttributes: [.font: NSFont.systemFont(ofSize: size, weight: weight)]).width
    }

    /// Window with title bar and traffic lights; returns the content rect.
    @discardableResult
    static func window(_ r: NSRect, title: String) -> NSRect {
        let shadow = NSShadow()
        shadow.shadowColor = NSColor(white: 0, alpha: 0.25); shadow.shadowBlurRadius = 24; shadow.shadowOffset = NSSize(width: 0, height: -8)
        NSGraphicsContext.saveGraphicsState(); shadow.set()
        NSColor.white.setFill(); NSBezierPath(roundedRect: r, xRadius: 18, yRadius: 18).fill()
        NSGraphicsContext.restoreGraphicsState()
        let bar = NSRect(x: r.minX, y: r.minY, width: r.width, height: 56)
        NSGraphicsContext.saveGraphicsState()
        NSBezierPath(roundedRect: r, xRadius: 18, yRadius: 18).addClip()
        NSColor(white: 0.95, alpha: 1).setFill(); bar.fill()
        line.setFill(); NSRect(x: r.minX, y: bar.maxY - 1, width: r.width, height: 1).fill()
        NSGraphicsContext.restoreGraphicsState()
        for (i, c) in [NSColor.systemRed, .systemYellow, .systemGreen].enumerated() {
            c.setFill(); NSBezierPath(ovalIn: NSRect(x: r.minX + 22 + CGFloat(i) * 30, y: r.minY + 19, width: 18, height: 18)).fill()
        }
        let tw = width(title, 22, weight: .semibold)
        text(title, 22, weight: .semibold, at: NSPoint(x: r.midX - tw / 2, y: r.minY + 14))
        return NSRect(x: r.minX, y: bar.maxY, width: r.width, height: r.height - 56)
    }

    static func highlight(_ r: NSRect, radius: CGFloat = 16) {
        let p = NSBezierPath(roundedRect: r.insetBy(dx: -8, dy: -8), xRadius: radius + 6, yRadius: radius + 6)
        p.lineWidth = 7; yellow.setStroke(); p.stroke()
    }

    static func button(_ title: String, _ r: NSRect, primary: Bool) {
        (primary ? blue : NSColor(white: 0.9, alpha: 1)).setFill()
        NSBezierPath(roundedRect: r, xRadius: 10, yRadius: 10).fill()
        let tw = width(title, 22, weight: .medium)
        text(title, 22, primary ? .white : ink, weight: .medium, at: NSPoint(x: r.midX - tw / 2, y: r.midY - 14))
    }

    /// Mouse pointer with its tip at p.
    static func cursor(at p: NSPoint) {
        let a = NSBezierPath()
        a.move(to: p); a.line(to: NSPoint(x: p.x, y: p.y + 44)); a.line(to: NSPoint(x: p.x + 11, y: p.y + 34))
        a.line(to: NSPoint(x: p.x + 19, y: p.y + 52)); a.line(to: NSPoint(x: p.x + 27, y: p.y + 48))
        a.line(to: NSPoint(x: p.x + 19, y: p.y + 31)); a.line(to: NSPoint(x: p.x + 33, y: p.y + 31)); a.close()
        NSColor.black.setFill(); a.fill(); a.lineWidth = 3; NSColor.white.setStroke(); a.stroke()
    }

    static func arrow(from a: NSPoint, to b: NSPoint) {
        let p = NSBezierPath(); p.move(to: a); p.line(to: b); p.lineWidth = 8; p.lineCapStyle = .round
        BrandIcon.indigo.setStroke(); p.stroke()
        let angle = atan2(b.y - a.y, b.x - a.x), s: CGFloat = 26
        let head = NSBezierPath(); head.move(to: b)
        head.line(to: NSPoint(x: b.x - s * cos(angle - .pi / 6), y: b.y - s * sin(angle - .pi / 6)))
        head.line(to: NSPoint(x: b.x - s * cos(angle + .pi / 6), y: b.y - s * sin(angle + .pi / 6))); head.close()
        BrandIcon.indigo.setFill(); head.fill()
    }

    static func zipIcon(_ r: NSRect) {
        NSColor.white.setFill(); line.setStroke()
        let doc = NSBezierPath(roundedRect: r, xRadius: 10, yRadius: 10); doc.fill(); doc.lineWidth = 3; doc.stroke()
        grey.setFill()
        for i in 0..<6 { NSRect(x: r.midX - 6, y: r.minY + 10 + CGFloat(i) * 14, width: 12, height: 8).fill() }
        text("ZIP", 18, grey, weight: .bold, at: NSPoint(x: r.midX - 17, y: r.maxY - 36))
    }

    static func folderIcon(_ r: NSRect) {
        let c = NSColor(srgbRed: 0x5A / 255, green: 0xB4 / 255, blue: 0xF0 / 255, alpha: 1)
        c.blended(withFraction: 0.2, of: .black)!.setFill()
        NSBezierPath(roundedRect: NSRect(x: r.minX, y: r.minY, width: r.width * 0.45, height: r.height * 0.3), xRadius: 8, yRadius: 8).fill()
        c.setFill(); NSBezierPath(roundedRect: NSRect(x: r.minX, y: r.minY + r.height * 0.15, width: r.width, height: r.height * 0.85), xRadius: 12, yRadius: 12).fill()
        // "A" glyph for Applications
        text("A", r.height * 0.42, NSColor(white: 1, alpha: 0.85), weight: .heavy, at: NSPoint(x: r.midX - r.height * 0.14, y: r.minY + r.height * 0.28))
    }

    static func menuBar(_ r: NSRect, iconHighlighted: Bool) -> NSRect {
        let bar = NSRect(x: 0, y: 0, width: r.width, height: 48)
        NSColor(white: 1, alpha: 0.75).setFill(); bar.fill()
        text("Finder    File    Edit    View    Go    Window    Help", 21, ink, weight: .medium, at: NSPoint(x: 28, y: 11))
        text("Wed 9:41", 21, ink, weight: .medium, at: NSPoint(x: r.maxX - 120, y: 11))
        // our icon
        let icon = NSRect(x: r.maxX - 190, y: 9, width: 30, height: 30)
        let img = BrandIcon.menuBar()
        let tinted = NSImage(size: img.size, flipped: false) { rr in img.draw(in: rr); NSColor.black.set(); rr.fill(using: .sourceAtop); return true }
        if iconHighlighted { NSColor(white: 0, alpha: 0.12).setFill(); NSBezierPath(roundedRect: icon.insetBy(dx: -8, dy: -4), xRadius: 8, yRadius: 8).fill() }
        tinted.draw(in: icon)
        return icon
    }

    // MARK: the five pictures

    static func download(_ r: NSRect) {
        let c = window(NSRect(x: 60, y: 40, width: 960, height: 560), title: "CaptainClip releases")
        NSColor(white: 0.95, alpha: 1).setFill()
        NSBezierPath(roundedRect: NSRect(x: c.minX + 30, y: c.minY + 20, width: c.width - 60, height: 46), xRadius: 23, yRadius: 23).fill()
        text("github.com/Princevasir/clipbridge/releases/latest", 21, grey, at: NSPoint(x: c.minX + 56, y: c.minY + 30))
        text("CaptainClip 0.4.0", 34, weight: .bold, at: NSPoint(x: c.minX + 40, y: c.minY + 92))
        text("Assets", 22, grey, weight: .semibold, at: NSPoint(x: c.minX + 40, y: c.minY + 156))
        let names = ["CaptainClip-android.apk", "CaptainClip-android-pro.apk", "CaptainClip-mac.zip", "SHA256SUMS.txt"]
        for (i, n) in names.enumerated() {
            let row = NSRect(x: c.minX + 40, y: c.minY + 196 + CGFloat(i) * 70, width: c.width - 80, height: 58)
            line.setFill(); NSRect(x: row.minX, y: row.maxY + 6, width: row.width, height: 1).fill()
            text("⬇︎  " + n, 24, n.hasSuffix("mac.zip") ? blue : ink, weight: n.hasSuffix("mac.zip") ? .semibold : .regular,
                 at: NSPoint(x: row.minX + 8, y: row.minY + 14))
            if n.hasSuffix("mac.zip") { highlight(NSRect(x: row.minX, y: row.minY + 4, width: 330, height: 50)); cursor(at: NSPoint(x: row.minX + 300, y: row.minY + 34)) }
        }
    }

    static func install(_ r: NSRect) {
        let c = window(NSRect(x: 60, y: 60, width: 960, height: 520), title: "Downloads")
        let y = c.minY + 110
        zipIcon(NSRect(x: c.minX + 90, y: y, width: 110, height: 140))
        text("CaptainClip-mac.zip", 20, at: NSPoint(x: c.minX + 60, y: y + 160))
        arrow(from: NSPoint(x: c.minX + 230, y: y + 70), to: NSPoint(x: c.minX + 320, y: y + 70))
        BrandIcon.app(side: 170).draw(in: NSRect(x: c.minX + 345, y: y - 15, width: 170, height: 170))
        text("CaptainClip", 20, at: NSPoint(x: c.minX + 382, y: y + 160))
        arrow(from: NSPoint(x: c.minX + 545, y: y + 70), to: NSPoint(x: c.minX + 690, y: y + 70))
        folderIcon(NSRect(x: c.minX + 720, y: y, width: 160, height: 135))
        text("Applications", 20, at: NSPoint(x: c.minX + 742, y: y + 160))
        highlight(NSRect(x: c.minX + 705, y: y - 10, width: 190, height: 205))
        text("Double-click the zip, then drag CaptainClip into Applications.", 22, grey, at: NSPoint(x: c.minX + 160, y: c.maxY - 70))
    }

    static func openAnyway(_ r: NSRect) {
        let c = window(NSRect(x: 60, y: 40, width: 960, height: 560), title: "Privacy & Security")
        let side = NSRect(x: c.minX, y: c.minY, width: 250, height: c.height)
        NSColor(white: 0.96, alpha: 1).setFill(); side.fill()
        for (i, item) in ["Wi-Fi", "Bluetooth", "Network", "General", "Privacy & Security", "Desktop & Dock"].enumerated() {
            let rr = NSRect(x: side.minX + 14, y: side.minY + 22 + CGFloat(i) * 56, width: side.width - 28, height: 44)
            if item == "Privacy & Security" { blue.setFill(); NSBezierPath(roundedRect: rr, xRadius: 8, yRadius: 8).fill() }
            text(item, 21, item == "Privacy & Security" ? .white : ink, at: NSPoint(x: rr.minX + 14, y: rr.minY + 9))
        }
        let x = side.maxX + 40
        text("Security", 28, weight: .bold, at: NSPoint(x: x, y: c.minY + 40))
        text("Allow applications from", 21, grey, at: NSPoint(x: x, y: c.minY + 100))
        text("App Store & Known Developers", 21, at: NSPoint(x: x, y: c.minY + 132))
        line.setFill(); NSRect(x: x, y: c.minY + 182, width: c.maxX - x - 40, height: 1).fill()
        NSAttributedString(string: "“CaptainClip” was blocked to protect your Mac.", attributes: [.font: NSFont.systemFont(ofSize: 23), .foregroundColor: ink])
            .draw(with: NSRect(x: x, y: c.minY + 212, width: 400, height: 90), options: [.usesLineFragmentOrigin])
        let b = NSRect(x: c.maxX - 240, y: c.minY + 220, width: 190, height: 52)
        button("Open Anyway", b, primary: false)
        highlight(b, radius: 10)
        cursor(at: NSPoint(x: b.midX + 30, y: b.midY + 4))
    }

    static func allow(_ r: NSRect) {
        let icon = menuBar(r, iconHighlighted: false)
        highlight(icon.insetBy(dx: -6, dy: -4), radius: 8)
        text("New: the CaptainClip icon in the menu bar", 22, ink, weight: .medium, at: NSPoint(x: icon.minX - 470, y: 70))
        arrow(from: NSPoint(x: icon.minX - 40, y: 82), to: NSPoint(x: icon.minX - 6, y: 58))
        // Permission alert
        let a = NSRect(x: 290, y: 150, width: 500, height: 440)
        let shadow = NSShadow(); shadow.shadowColor = NSColor(white: 0, alpha: 0.25); shadow.shadowBlurRadius = 24
        NSGraphicsContext.saveGraphicsState(); shadow.set()
        NSColor(white: 0.97, alpha: 1).setFill(); NSBezierPath(roundedRect: a, xRadius: 22, yRadius: 22).fill()
        NSGraphicsContext.restoreGraphicsState()
        BrandIcon.app(side: 110).draw(in: NSRect(x: a.midX - 55, y: a.minY + 30, width: 110, height: 110))
        NSAttributedString(string: "“CaptainClip” would like to find and connect to devices on your local network.",
                           attributes: [.font: NSFont.systemFont(ofSize: 23, weight: .semibold), .foregroundColor: ink,
                                        .paragraphStyle: { let p = NSMutableParagraphStyle(); p.alignment = .center; return p }()])
            .draw(with: NSRect(x: a.minX + 40, y: a.minY + 160, width: a.width - 80, height: 120), options: [.usesLineFragmentOrigin])
        button("Don’t Allow", NSRect(x: a.minX + 40, y: a.maxY - 160, width: a.width - 80, height: 50), primary: false)
        let allow = NSRect(x: a.minX + 40, y: a.maxY - 96, width: a.width - 80, height: 50)
        button("Allow", allow, primary: true)
        highlight(allow, radius: 10)
    }

    static func pair(_ r: NSRect) {
        let icon = menuBar(r, iconHighlighted: true)
        // Dropdown menu under the icon
        let m = NSRect(x: icon.midX - 330, y: 54, width: 360, height: 300)
        let shadow = NSShadow(); shadow.shadowColor = NSColor(white: 0, alpha: 0.2); shadow.shadowBlurRadius = 18
        NSGraphicsContext.saveGraphicsState(); shadow.set()
        NSColor(white: 0.97, alpha: 1).setFill(); NSBezierPath(roundedRect: m, xRadius: 14, yRadius: 14).fill()
        NSGraphicsContext.restoreGraphicsState()
        NSColor.systemOrange.setFill(); NSBezierPath(ovalIn: NSRect(x: m.minX + 22, y: m.minY + 26, width: 14, height: 14)).fill()
        text("Waiting for your phone…", 20, grey, at: NSPoint(x: m.minX + 46, y: m.minY + 18))
        text("Bluetooth fallback: ready", 19, grey, at: NSPoint(x: m.minX + 22, y: m.minY + 58))
        line.setFill(); NSRect(x: m.minX + 14, y: m.minY + 98, width: m.width - 28, height: 1).fill()
        let sel = NSRect(x: m.minX + 10, y: m.minY + 108, width: m.width - 20, height: 44)
        blue.setFill(); NSBezierPath(roundedRect: sel, xRadius: 8, yRadius: 8).fill()
        text("Pair phone…", 21, .white, weight: .medium, at: NSPoint(x: sel.minX + 12, y: sel.minY + 9))
        text("⌘P", 21, .white, at: NSPoint(x: sel.maxX - 52, y: sel.minY + 9))
        for (i, s) in ["Pause syncing", "Open Downloads", "Start CaptainClip at login"].enumerated() {
            text(s, 21, ink, at: NSPoint(x: m.minX + 22, y: m.minY + 166 + CGFloat(i) * 42))
        }
        highlight(sel, radius: 8)
        // Pairing window with a SAMPLE (not real) QR code
        let wnd = NSRect(x: 60, y: 200, width: 400, height: 420)
        let c = window(wnd, title: "Pair your phone")
        let q = NSRect(x: c.midX - 120, y: c.minY + 24, width: 240, height: 240)
        sampleQR(q)
        text("Scan with CaptainClip on your phone", 19, grey, at: NSPoint(x: c.minX + 44, y: c.maxY - 64))
        text("(sample code)", 16, grey, at: NSPoint(x: c.midX - 50, y: c.maxY - 36))
    }

    /// A QR-looking pattern that encodes nothing (fixed pseudo-random cells + the three finder squares).
    static func sampleQR(_ r: NSRect) {
        NSColor.white.setFill(); r.fill()
        let n = 25, cell = r.width / CGFloat(n)
        var seed: UInt32 = 0x5EED
        NSColor.black.setFill()
        for y in 0..<n { for x in 0..<n {
            seed = seed &* 1_103_515_245 &+ 12_345
            let inFinder = (x < 8 && y < 8) || (x >= n - 8 && y < 8) || (x < 8 && y >= n - 8)
            if !inFinder && (seed >> 16) & 1 == 1 { NSRect(x: r.minX + CGFloat(x) * cell, y: r.minY + CGFloat(y) * cell, width: cell, height: cell).fill() }
        } }
        for (fx, fy) in [(0, 0), (n - 7, 0), (0, n - 7)] {
            let o = NSRect(x: r.minX + CGFloat(fx) * cell, y: r.minY + CGFloat(fy) * cell, width: 7 * cell, height: 7 * cell)
            NSColor.black.setFill(); o.fill()
            NSColor.white.setFill(); o.insetBy(dx: cell, dy: cell).fill()
            NSColor.black.setFill(); o.insetBy(dx: 2 * cell, dy: 2 * cell).fill()
        }
    }
}
