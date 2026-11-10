import AppKit
import CoreImage.CIFilterBuiltins

/// Small window showing the pairing QR code for the phone to scan.
final class PairWindow {
    private var window: NSWindow?

    func show(uri: String, subtitle: String) {
        let size: CGFloat = 280
        let imageView = NSImageView(frame: NSRect(x: 20, y: 60, width: size, height: size))
        imageView.image = Self.qrImage(uri, size: size)
        let title = NSTextField(labelWithString: "Scan with ClipBridge on your phone")
        title.frame = NSRect(x: 20, y: 30, width: size, height: 20)
        title.alignment = .center
        let detail = NSTextField(labelWithString: subtitle)
        detail.frame = NSRect(x: 20, y: 10, width: size, height: 18)
        detail.alignment = .center
        detail.textColor = .secondaryLabelColor

        let content = NSView(frame: NSRect(x: 0, y: 0, width: size + 40, height: size + 80))
        [imageView, title, detail].forEach(content.addSubview)

        let window = self.window ?? NSWindow(contentRect: content.frame, styleMask: [.titled, .closable],
                                             backing: .buffered, defer: false)
        window.title = "Pair phone"
        window.contentView = content
        window.isReleasedWhenClosed = false
        window.center()
        self.window = window
        NSApp.activate(ignoringOtherApps: true)
        window.makeKeyAndOrderFront(nil)
    }

    func close() { window?.close() }

    static func qrImage(_ text: String, size: CGFloat) -> NSImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let scale = size / output.extent.width
        let scaled = output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let rep = NSCIImageRep(ciImage: scaled)
        let image = NSImage(size: rep.size)
        image.addRepresentation(rep)
        return image
    }
}
