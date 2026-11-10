import AppKit
import ClipBridgeCore

// Usage: iconmaker <out.iconset> [preview.png]
let args = CommandLine.arguments
guard args.count >= 2 else { print("usage: iconmaker <out.iconset> [preview.png]"); exit(2) }
try BrandIcon.writeIconset(to: URL(fileURLWithPath: args[1]))
if args.count >= 3 {
    // Preview: app icon + menu-bar icon on light and dark bars, for a visual check.
    let canvas = NSImage(size: NSSize(width: 560, height: 280), flipped: false) { _ in
        NSColor(white: 0.93, alpha: 1).setFill(); NSRect(x: 0, y: 0, width: 560, height: 280).fill()
        BrandIcon.app(side: 256).draw(in: NSRect(x: 12, y: 12, width: 256, height: 256))
        for (i, dark) in [false, true].enumerated() {
            let bar = NSRect(x: 290, y: 160 - CGFloat(i) * 110, width: 250, height: 90)
            (dark ? NSColor(white: 0.12, alpha: 1) : NSColor(white: 0.98, alpha: 1)).setFill(); bar.fill()
            let icon = BrandIcon.menuBar()
            let tinted = NSImage(size: icon.size, flipped: false) { r in
                icon.draw(in: r); (dark ? NSColor.white : NSColor.black).set(); r.fill(using: .sourceAtop); return true
            }
            tinted.draw(in: NSRect(x: bar.minX + 20, y: bar.minY + 9, width: 72, height: 72))
            tinted.draw(in: NSRect(x: bar.minX + 120, y: bar.minY + 36, width: 18, height: 18))
        }
        return true
    }
    let rep = NSBitmapImageRep(data: canvas.tiffRepresentation!)!
    try rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: args[2]))
}
