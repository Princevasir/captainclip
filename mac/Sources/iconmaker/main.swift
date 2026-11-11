import AppKit
import ClipBridgeCore

// Usage: iconmaker <out.iconset> [preview.png]   |   iconmaker --play <folder>  (Google Play icon + feature graphic)
let args = CommandLine.arguments
if args.count == 3, args[1] == "--play" {
    try BrandIcon.writePlayGraphics(to: URL(fileURLWithPath: args[2]))
    exit(0)
}
// iconmaker --store <screenshots folder with 1-connected.png, 2-quick-toggle.png> <out folder>
// iconmaker --trailer <screenshots folder> <out.mp4> [stills folder]
if args.count >= 4, args[1] == "--trailer" {
    let panels = StorePanels.all(screenshots: URL(fileURLWithPath: args[2]))
    if args.count == 5 {
        try Trailer.stills(panels: panels, times: [1.6, 4.4, 5.6, 9.8, 13.8, 18.2, 22.4, 25.0], to: URL(fileURLWithPath: args[4]))
    }
    try Trailer.render(panels: panels, to: URL(fileURLWithPath: args[3]))
    print(String(format: "trailer: %.1f s", Trailer.duration(panels)))
    exit(0)
}
if args.count == 4, args[1] == "--store" {
    let shots = URL(fileURLWithPath: args[2])
    try StoreArt.write(panels: StorePanels.all(screenshots: shots), to: URL(fileURLWithPath: args[3]))
    exit(0)
}
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
