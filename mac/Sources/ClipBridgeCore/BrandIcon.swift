import AppKit

/// The ClipBridge logo (same geometry as the Android adaptive icon): a clipboard whose content is a bridge
/// joining two devices, on a teal → indigo gradient. Drawn in code so it stays sharp at every size.
public enum BrandIcon {
    static let teal = NSColor(srgbRed: 0x0E / 255, green: 0xA5 / 255, blue: 0xA4 / 255, alpha: 1)
    static let indigo = NSColor(srgbRed: 0x4F / 255, green: 0x46 / 255, blue: 0xE5 / 255, alpha: 1)
    static let lavender = NSColor(srgbRed: 0xC7 / 255, green: 0xD2 / 255, blue: 0xFE / 255, alpha: 1)

    /// 18×18 pt template image for the menu bar (outline style; macOS tints it).
    public static func menuBar() -> NSImage {
        let image = NSImage(size: NSSize(width: 18, height: 18), flipped: true) { rect in
            // Map the 108-unit Android canvas region 28…80 onto the 18 pt square.
            let scale = rect.width / 52
            let t = NSAffineTransform()
            t.scale(by: scale)
            t.translateX(by: -28, yBy: -27)
            t.concat()
            NSColor.black.set()
            let board = NSBezierPath(roundedRect: NSRect(x: 37, y: 36, width: 34, height: 40), xRadius: 3, yRadius: 3)
            board.lineWidth = 4
            board.stroke()
            // A wide clip overlapping the board's top edge, so it reads as a clipboard (not a battery).
            NSBezierPath(roundedRect: NSRect(x: 42, y: 30, width: 24, height: 11), xRadius: 3.5, yRadius: 3.5).fill()
            stroke(arch(), width: 4.5)
            stroke(deck(), width: 3.5)
            return true
        }
        image.isTemplate = true
        return image
    }

    /// Full-colour app icon tile (macOS grid: rounded square inset ~10%).
    public static func app(side: CGFloat) -> NSImage {
        NSImage(size: NSSize(width: side, height: side), flipped: true) { rect in
            let inset = rect.width * 0.098
            let tile = rect.insetBy(dx: inset, dy: inset)
            let radius = tile.width * 0.225
            let shape = NSBezierPath(roundedRect: tile, xRadius: radius, yRadius: radius)
            NSGradient(starting: teal, ending: indigo)?.draw(in: shape, angle: 45)
            // Foreground: same shapes as Android, centred in the tile (108-unit canvas → tile).
            drawForeground(in: tile)
            return true
        }
    }

    /// The white clipboard with the bridge, drawn into `box` (the 108-unit Android canvas mapped onto it).
    /// Call inside a flipped drawing context.
    private static func drawForeground(in box: NSRect) {
        NSGraphicsContext.saveGraphicsState()
        defer { NSGraphicsContext.restoreGraphicsState() }
        let t = NSAffineTransform()
        t.translateX(by: box.minX, yBy: box.minY)
        t.scale(by: box.width / 108)
        t.concat()
        NSColor.white.set()
        NSBezierPath(roundedRect: NSRect(x: 35, y: 34, width: 38, height: 44), xRadius: 4, yRadius: 4).fill()
        lavender.set()
        NSBezierPath(roundedRect: NSRect(x: 45, y: 29, width: 18, height: 9), xRadius: 3, yRadius: 3).fill()
        teal.set(); stroke(deck(), width: 3)
        indigo.set(); stroke(arch(), width: 4)
        dot(at: NSPoint(x: 43, y: 66))
        dot(at: NSPoint(x: 65, y: 66))
    }

    /// Google Play store graphics: `icon-512.png` (full-bleed square; Play rounds it) and
    /// `feature-graphic.png` (1024×500, opaque).
    public static func writePlayGraphics(to folder: URL) throws {
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try png(width: 512, height: 512, alpha: true, to: folder.appendingPathComponent("icon-512.png")) { rect in
            NSGradient(starting: teal, ending: indigo)?.draw(in: rect, angle: 45)
            drawForeground(in: rect)
        }
        try png(width: 1024, height: 500, alpha: false, to: folder.appendingPathComponent("feature-graphic.png")) { rect in
            NSGradient(starting: teal, ending: indigo)?.draw(in: rect, angle: 20)
            // Icon tile on the left, title and tagline on the right.
            let tile = NSRect(x: 96, y: 110, width: 280, height: 280)
            NSColor(white: 1, alpha: 0.14).setFill()
            NSBezierPath(roundedRect: tile, xRadius: 64, yRadius: 64).fill()
            drawForeground(in: tile)
            let title: [NSAttributedString.Key: Any] = [.font: NSFont.systemFont(ofSize: 92, weight: .bold), .foregroundColor: NSColor.white]
            let line: [NSAttributedString.Key: Any] = [.font: NSFont.systemFont(ofSize: 38, weight: .medium),
                                                       .foregroundColor: NSColor(white: 1, alpha: 0.92)]
            ("ClipBridge" as NSString).draw(at: NSPoint(x: 430, y: 150), withAttributes: title)
            ("Copy on your Mac." as NSString).draw(at: NSPoint(x: 434, y: 268), withAttributes: line)
            ("Paste on your phone." as NSString).draw(at: NSPoint(x: 434, y: 316), withAttributes: line)
        }
    }

    /// Renders a flipped drawing into an exact-pixel PNG (`alpha: false` → no alpha channel at all).
    private static func png(width: Int, height: Int, alpha: Bool, to url: URL, draw: (NSRect) -> Void) throws {
        let info = alpha ? CGImageAlphaInfo.premultipliedLast : CGImageAlphaInfo.noneSkipLast
        guard let cg = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                                 space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: info.rawValue)
        else { throw CocoaError(.fileWriteUnknown) }
        cg.translateBy(x: 0, y: CGFloat(height))
        cg.scaleBy(x: 1, y: -1)
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = NSGraphicsContext(cgContext: cg, flipped: true)
        draw(NSRect(x: 0, y: 0, width: width, height: height))
        NSGraphicsContext.restoreGraphicsState()
        guard let image = cg.makeImage(),
              let data = NSBitmapImageRep(cgImage: image).representation(using: .png, properties: [:])
        else { throw CocoaError(.fileWriteUnknown) }
        try data.write(to: url)
    }

    private static func arch() -> NSBezierPath {
        let p = NSBezierPath()
        p.move(to: NSPoint(x: 43, y: 66))
        // Quadratic Q(54,45) expressed as a cubic.
        p.curve(to: NSPoint(x: 65, y: 66),
                controlPoint1: NSPoint(x: 43 + 2.0 / 3 * (54 - 43), y: 66 + 2.0 / 3 * (45 - 66)),
                controlPoint2: NSPoint(x: 65 + 2.0 / 3 * (54 - 65), y: 66 + 2.0 / 3 * (45 - 66)))
        return p
    }

    private static func deck() -> NSBezierPath {
        let p = NSBezierPath()
        p.move(to: NSPoint(x: 42, y: 66))
        p.line(to: NSPoint(x: 66, y: 66))
        return p
    }

    private static func stroke(_ path: NSBezierPath, width: CGFloat) {
        path.lineWidth = width
        path.lineCapStyle = .round
        path.stroke()
    }

    private static func dot(at center: NSPoint) {
        indigo.set()
        NSBezierPath(ovalIn: NSRect(x: center.x - 3.5, y: center.y - 3.5, width: 7, height: 7)).fill()
    }

    /// Writes a macOS .iconset folder (all required sizes) for `iconutil -c icns`.
    public static func writeIconset(to folder: URL) throws {
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        for base in [16, 32, 128, 256, 512] {
            for scale in [1, 2] {
                let px = base * scale
                let image = app(side: CGFloat(px))
                guard let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: px, pixelsHigh: px, bitsPerSample: 8,
                                                 samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                                                 colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)
                else { continue }
                NSGraphicsContext.saveGraphicsState()
                NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
                image.draw(in: NSRect(x: 0, y: 0, width: px, height: px))
                NSGraphicsContext.restoreGraphicsState()
                let name = scale == 1 ? "icon_\(base)x\(base).png" : "icon_\(base)x\(base)@2x.png"
                try rep.representation(using: .png, properties: [:])?.write(to: folder.appendingPathComponent(name))
            }
        }
    }
}
