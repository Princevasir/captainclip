import AppKit
import AVFoundation
import ClipBridgeCore

/// Play Store trailer (1920 × 1080, 30 fps, silent): intro → the five store panels → outro.
/// Each scene fades in from the brand gradient, animates its headline and slides its phone up, then fades out.
enum Trailer {
    static let width = 1920, height = 1080
    static let fps: Int32 = 30
    static let introSeconds = 3.0, sceneSeconds = 4.2, outroSeconds = 3.8, fade = 0.35

    enum Scene { case intro, panel(StoreArt.Panel, CGImage?), outro }

    static func scenes(_ panels: [StoreArt.Panel]) -> [(Scene, Double)] {
        [(.intro, introSeconds)] + panels.map { (.panel($0, phoneRaster($0)), sceneSeconds) } + [(.outro, outroSeconds)]
    }

    static func duration(_ panels: [StoreArt.Panel]) -> Double { introSeconds + Double(panels.count) * sceneSeconds + outroSeconds }

    // MARK: rendering

    static func render(panels: [StoreArt.Panel], to url: URL) throws {
        try? FileManager.default.removeItem(at: url)
        let writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: width, AVVideoHeightKey: height,
            AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey: 1_600_000, AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel],
        ])
        input.expectsMediaDataInRealTime = false
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: width, kCVPixelBufferHeightKey as String: height,
        ])
        writer.add(input)
        guard writer.startWriting() else { throw writer.error ?? CocoaError(.fileWriteUnknown) }
        writer.startSession(atSourceTime: .zero)
        let timeline = scenes(panels)
        let frames = Int((duration(panels) * Double(fps)).rounded())
        for i in 0..<frames {
            while !input.isReadyForMoreMediaData { usleep(2_000) }
            guard let pool = adaptor.pixelBufferPool else { throw CocoaError(.fileWriteUnknown) }
            var buffer: CVPixelBuffer?
            CVPixelBufferPoolCreatePixelBuffer(nil, pool, &buffer)
            guard let pb = buffer else { throw CocoaError(.fileWriteUnknown) }
            CVPixelBufferLockBaseAddress(pb, [])
            let ctx = CGContext(data: CVPixelBufferGetBaseAddress(pb), width: width, height: height, bitsPerComponent: 8,
                                bytesPerRow: CVPixelBufferGetBytesPerRow(pb), space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue)!
            draw(at: Double(i) / Double(fps), timeline: timeline, in: ctx)
            CVPixelBufferUnlockBaseAddress(pb, [])
            adaptor.append(pb, withPresentationTime: CMTime(value: CMTimeValue(i), timescale: fps))
        }
        input.markAsFinished()
        let done = DispatchSemaphore(value: 0)
        writer.finishWriting { done.signal() }
        done.wait()
        if writer.status != .completed { throw writer.error ?? CocoaError(.fileWriteUnknown) }
    }

    /// PNG stills at given times, for checking the look without watching the video.
    static func stills(panels: [StoreArt.Panel], times: [Double], to folder: URL) throws {
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let timeline = scenes(panels)
        for t in times {
            try BrandIcon.png(width: width, height: height, alpha: false,
                              to: folder.appendingPathComponent(String(format: "still-%05.2f.png", t))) { _ in
                draw(at: t, timeline: timeline, in: NSGraphicsContext.current!.cgContext, flipped: true)
            }
        }
    }

    static func draw(at t: Double, timeline: [(Scene, Double)], in cg: CGContext, flipped: Bool = false) {
        NSGraphicsContext.saveGraphicsState()
        if !flipped {
            cg.translateBy(x: 0, y: CGFloat(height))
            cg.scaleBy(x: 1, y: -1)
        }
        NSGraphicsContext.current = NSGraphicsContext(cgContext: cg, flipped: true)
        let canvas = NSRect(x: 0, y: 0, width: width, height: height)
        NSGradient(starting: BrandIcon.teal, ending: BrandIcon.indigo)?.draw(in: canvas, angle: 35)
        var start = 0.0
        for (scene, length) in timeline {
            if t < start + length {
                let local = t - start
                let alpha = min(1, local / fade, (length - local) / fade)
                cg.setAlpha(CGFloat(max(0, alpha)))
                cg.beginTransparencyLayer(auxiliaryInfo: nil)
                switch scene {
                case .intro: intro(local)
                case .panel(let p, let phone): panel(p, phone: phone, at: local, cg: cg)
                case .outro: outro(local)
                }
                cg.endTransparencyLayer()
                break
            }
            start += length
        }
        NSGraphicsContext.restoreGraphicsState()
    }

    // MARK: scenes

    /// 0 → 1 between a and b, eased out.
    private static func ease(_ t: Double, _ a: Double, _ b: Double) -> CGFloat {
        let x = min(1, max(0, (t - a) / (b - a)))
        return CGFloat(1 - pow(1 - x, 3))
    }

    private static func centered(_ s: String, _ font: NSFont, _ color: NSColor, y: CGFloat) {
        let w = (s as NSString).size(withAttributes: [.font: font]).width
        StoreArt.text(s, font, color, at: NSPoint(x: (CGFloat(width) - w) / 2, y: y))
    }

    private static func intro(_ t: Double) {
        let k = ease(t, 0.1, 0.9)
        let side = 220 * (0.8 + 0.2 * k)
        BrandIcon.app(side: side).draw(in: NSRect(x: (CGFloat(width) - side) / 2, y: 300 - (side - 220) / 2, width: side, height: side),
                                       from: .zero, operation: .sourceOver, fraction: k, respectFlipped: true, hints: nil)
        centered("CaptainClip", NSFont.systemFont(ofSize: 120, weight: .heavy), NSColor(white: 1, alpha: ease(t, 0.4, 1.1)), y: 560)
        centered("Copy on your Mac. Paste on your phone.", NSFont.systemFont(ofSize: 52, weight: .medium),
                 NSColor(white: 1, alpha: 0.92 * ease(t, 0.9, 1.6)), y: 730)
    }

    private static func outro(_ t: Double) {
        let k = ease(t, 0.05, 0.6)
        BrandIcon.app(side: 180).draw(in: NSRect(x: (CGFloat(width) - 180) / 2, y: 230, width: 180, height: 180),
                                      from: .zero, operation: .sourceOver, fraction: k, respectFlipped: true, hints: nil)
        centered("CaptainClip", NSFont.systemFont(ofSize: 110, weight: .heavy), NSColor(white: 1, alpha: k), y: 440)
        centered("Free on Google Play", NSFont.systemFont(ofSize: 56, weight: .semibold), NSColor(white: 1, alpha: ease(t, 0.5, 1.1)), y: 610)
        centered("Mac app: github.com/Princevasir/clipbridge", NSFont.systemFont(ofSize: 40, weight: .medium),
                 NSColor(white: 1, alpha: 0.85 * ease(t, 0.9, 1.5)), y: 700)
        centered("No account · No cloud · End-to-end encrypted", NSFont.systemFont(ofSize: 36, weight: .medium),
                 NSColor(white: 1, alpha: 0.75 * ease(t, 1.2, 1.8)), y: 790)
    }

    private static func panel(_ p: StoreArt.Panel, phone: CGImage?, at t: Double, cg: CGContext) {
        // Brand row
        BrandIcon.app(side: 84).draw(in: NSRect(x: 140, y: 110, width: 84, height: 84))
        StoreArt.text("CaptainClip", NSFont.systemFont(ofSize: 40, weight: .semibold), .white, at: NSPoint(x: 240, y: 126))
        // Headline: line 1, then line 2 with the highlighted word popping in
        let big = NSFont.systemFont(ofSize: 112, weight: .heavy)
        let a1 = ease(t, 0.25, 0.75), a2 = ease(t, 0.5, 1.0), a3 = ease(t, 0.8, 1.3), a4 = ease(t, 1.1, 1.7)
        StoreArt.text(p.lead, big, NSColor(white: 1, alpha: a1), at: NSPoint(x: 140 - 40 * (1 - a1), y: 300))
        let plainWidth = p.plain.isEmpty ? 0 : (p.plain as NSString).size(withAttributes: [.font: big]).width + 52
        if !p.plain.isEmpty {
            StoreArt.text(p.plain, big, NSColor(white: 1, alpha: a2), at: NSPoint(x: 140 - 40 * (1 - a2), y: 440))
        }
        let hw = (p.highlight as NSString).size(withAttributes: [.font: big]).width
        let pill = NSRect(x: 140 + plainWidth - 20, y: 444, width: hw + 40, height: 138)
        cg.saveGState()
        cg.translateBy(x: pill.midX, y: pill.midY)
        cg.scaleBy(x: 0.85 + 0.15 * a3, y: 0.85 + 0.15 * a3)
        cg.translateBy(x: -pill.midX, y: -pill.midY)
        NSColor(white: 1, alpha: a3).setFill()
        NSBezierPath(roundedRect: pill, xRadius: 30, yRadius: 30).fill()
        StoreArt.text(p.highlight, big, BrandIcon.indigo.withAlphaComponent(a3), at: NSPoint(x: 140 + plainWidth, y: 440))
        cg.restoreGState()
        NSAttributedString(string: p.sub, attributes: [.font: NSFont.systemFont(ofSize: 44, weight: .medium),
                                                        .foregroundColor: NSColor(white: 1, alpha: 0.9 * a4)])
            .draw(with: NSRect(x: 144, y: 640, width: 920, height: 200), options: [.usesLineFragmentOrigin])
        // Phone slides up from below and keeps drifting gently
        guard let phone else { return }
        let w: CGFloat = 520, h = w * StoreArt.phoneSize.height / StoreArt.phoneSize.width
        let rise = ease(t, 0.15, 0.95)
        let y = 130 + (1 - rise) * 900 - CGFloat(t) * 6
        cg.saveGState()
        cg.translateBy(x: 0, y: y + h)
        cg.scaleBy(x: 1, y: -1)  // CGImage draws bottom-up
        cg.draw(phone, in: CGRect(x: 1240, y: 0, width: w, height: h))
        cg.restoreGState()
    }

    /// The phone drawn once per scene at video resolution (fast to composite every frame).
    private static func phoneRaster(_ p: StoreArt.Panel) -> CGImage? {
        let w = 520, h = Int(CGFloat(w) * StoreArt.phoneSize.height / StoreArt.phoneSize.width)
        guard let cg = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0,
                                 space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
        else { return nil }
        cg.translateBy(x: 0, y: CGFloat(h))
        cg.scaleBy(x: 1, y: -1)
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = NSGraphicsContext(cgContext: cg, flipped: true)
        StoreArt.phoneImage(p).draw(in: NSRect(x: 0, y: 0, width: w, height: h), from: .zero, operation: .sourceOver,
                                    fraction: 1, respectFlipped: true, hints: nil)
        NSGraphicsContext.restoreGraphicsState()
        return cg.makeImage()
    }
}
