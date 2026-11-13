import AppKit
import ClipBridgeCore
import CoreImage.CIFilterBuiltins

/// Pairing window: logo, three short steps, and the QR code for the phone to scan.
final class PairWindow {
    private var window: NSWindow?

    func show(uri: String, address: String) {
        let width: CGFloat = 380
        let content = NSView(frame: NSRect(x: 0, y: 0, width: width, height: 560))

        let logo = NSImageView(image: BrandIcon.app(side: 128))
        let title = label("Pair your phone", size: 22, weight: .semibold)
        let steps = label("1.  Open CaptainClip on your Android phone\n2.  Tap  Scan QR code\n3.  Point the camera at this code",
                          size: 13, weight: .regular, color: .secondaryLabelColor, center: false)

        let qrBackground = NSView()
        qrBackground.wantsLayer = true
        qrBackground.layer?.backgroundColor = NSColor.white.cgColor
        qrBackground.layer?.cornerRadius = 16
        let qr = NSImageView(image: Self.qrImage(uri, size: 240) ?? NSImage())
        qr.imageScaling = .scaleNone

        let footer = label("Same Wi-Fi only · end-to-end encrypted · Mac at \(address)",
                           size: 11, weight: .regular, color: .tertiaryLabelColor)

        let stack = NSStackView(views: [logo, title, steps, qrBackground, footer])
        stack.orientation = .vertical
        stack.alignment = .centerX
        stack.spacing = 14
        stack.setCustomSpacing(6, after: logo)
        stack.setCustomSpacing(22, after: steps)
        stack.translatesAutoresizingMaskIntoConstraints = false
        qrBackground.addSubview(qr)
        qr.translatesAutoresizingMaskIntoConstraints = false
        content.addSubview(stack)
        NSLayoutConstraint.activate([
            logo.widthAnchor.constraint(equalToConstant: 72), logo.heightAnchor.constraint(equalToConstant: 72),
            qrBackground.widthAnchor.constraint(equalToConstant: 264), qrBackground.heightAnchor.constraint(equalToConstant: 264),
            qr.centerXAnchor.constraint(equalTo: qrBackground.centerXAnchor), qr.centerYAnchor.constraint(equalTo: qrBackground.centerYAnchor),
            stack.topAnchor.constraint(equalTo: content.topAnchor, constant: 28),
            stack.centerXAnchor.constraint(equalTo: content.centerXAnchor),
            stack.widthAnchor.constraint(equalToConstant: width - 48),
        ])

        let window = self.window ?? NSWindow(contentRect: content.frame, styleMask: [.titled, .closable, .fullSizeContentView],
                                             backing: .buffered, defer: false)
        window.titlebarAppearsTransparent = true
        window.titleVisibility = .hidden
        window.contentView = content
        window.isReleasedWhenClosed = false
        window.center()
        self.window = window
        NSApp.activate(ignoringOtherApps: true)
        window.makeKeyAndOrderFront(nil)
    }

    func close() { window?.close() }

    private func label(_ text: String, size: CGFloat, weight: NSFont.Weight, color: NSColor = .labelColor,
                       center: Bool = true) -> NSTextField {
        let field = NSTextField(wrappingLabelWithString: text)
        field.font = .systemFont(ofSize: size, weight: weight)
        field.textColor = color
        field.alignment = center ? .center : .left
        field.isSelectable = false
        return field
    }

    static func qrImage(_ text: String, size: CGFloat) -> NSImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let scale = (size / output.extent.width).rounded(.down)
        let scaled = output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let rep = NSCIImageRep(ciImage: scaled)
        let image = NSImage(size: rep.size)
        image.addRepresentation(rep)
        return image
    }
}
