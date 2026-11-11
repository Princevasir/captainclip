import AppKit
import ClipBridgeCore

private func opaquePixels(_ image: NSImage, side: Int) -> Int {
    guard let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: side, pixelsHigh: side, bitsPerSample: 8,
                                     samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB,
                                     bytesPerRow: 0, bitsPerPixel: 0) else { return 0 }
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    image.draw(in: NSRect(x: 0, y: 0, width: side, height: side))
    NSGraphicsContext.restoreGraphicsState()
    var count = 0
    for x in 0..<side { for y in 0..<side where (rep.colorAt(x: x, y: y)?.alphaComponent ?? 0) > 0.5 { count += 1 } }
    return count
}

func brandIconTests() {
    let menu = BrandIcon.menuBar()
    check(menu.size == NSSize(width: 18, height: 18), "menu-bar icon is 18×18 pt")
    check(menu.isTemplate, "menu-bar icon is a template (macOS tints it for light/dark menu bars)")
    let ink = opaquePixels(menu, side: 36)
    check(ink > 80 && ink < 36 * 36 / 2, "menu-bar icon draws a visible outline, not a solid block (\(ink) px)")

    let app = BrandIcon.app(side: 256)
    check(app.size == NSSize(width: 256, height: 256), "app icon renders at the requested size")
    check(!app.isTemplate, "app icon is full colour")
    let filled = opaquePixels(app, side: 256)
    check(filled > 256 * 256 / 2 && filled < 256 * 256, "app icon is a rounded tile with transparent corners (\(filled) px)")

    // Google Play graphics: fully opaque (Play rejects transparency in the feature graphic and masks the icon itself).
    let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("cb-play-\(UUID().uuidString)")
    defer { try? FileManager.default.removeItem(at: tmp) }
    do {
        try BrandIcon.writePlayGraphics(to: tmp)
        let icon = NSBitmapImageRep(data: (try? Data(contentsOf: tmp.appendingPathComponent("icon-512.png"))) ?? Data())
        check(icon?.pixelsWide == 512 && icon?.pixelsHigh == 512, "Play icon is 512×512 px")
        check((icon?.colorAt(x: 0, y: 0)?.alphaComponent ?? 0) > 0.99, "Play icon is full-bleed (opaque corners)")
        let feature = NSBitmapImageRep(data: (try? Data(contentsOf: tmp.appendingPathComponent("feature-graphic.png"))) ?? Data())
        check(feature?.pixelsWide == 1024 && feature?.pixelsHigh == 500, "Play feature graphic is 1024×500 px")
        check(!(feature?.hasAlpha ?? true), "Play feature graphic has no transparency")
    } catch {
        check(false, "Play graphics written: \(error)")
    }
}
