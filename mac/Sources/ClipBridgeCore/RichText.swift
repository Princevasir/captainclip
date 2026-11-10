import AppKit

/// Formatted text on the Mac pasteboard. HTML is the format both platforms share; Mac apps that only put RTF
/// (Notes, Pages, TextEdit) are converted to HTML, and incoming HTML is also written as RTF for those apps.
public enum RichText {
    /// The copy's HTML: `public.html` if present, else RTF converted to HTML. Nil for plain copies.
    public static func html(from pasteboard: NSPasteboard) -> String? {
        if let html = pasteboard.string(forType: .html), !html.isEmpty { return html }
        guard let rtf = pasteboard.data(forType: .rtf),
              let attributed = NSAttributedString(rtf: rtf, documentAttributes: nil),
              attributed.length > 0,
              let data = try? attributed.data(from: NSRange(location: 0, length: attributed.length),
                                              documentAttributes: [.documentType: NSAttributedString.DocumentType.html]),
              let html = String(data: data, encoding: .utf8)
        else { return nil }
        return html
    }

    /// Puts plain text + HTML + RTF (from the HTML) on the pasteboard. Call on the main thread.
    /// The RTF is built first (from HTML with remote resources removed), then all three are written together.
    public static func write(text: String, html: String, to pasteboard: NSPasteboard) {
        var rtf: Data?
        if let data = normalizedWeights(importable(html)).data(using: .utf8),
           let attributed = try? NSAttributedString(data: data, options: [.documentType: NSAttributedString.DocumentType.html,
                                                                          .characterEncoding: String.Encoding.utf8.rawValue],
                                                    documentAttributes: nil) {
            let clean = cleaned(attributed)
            rtf = try? clean.data(from: NSRange(location: 0, length: clean.length),
                                  documentAttributes: [.documentType: NSAttributedString.DocumentType.rtf])
        }
        pasteboard.clearContents()
        pasteboard.setString(text, forType: .string)
        pasteboard.setString(html, forType: .html)
        if let rtf { pasteboard.setData(rtf, forType: .rtf) }
    }

    /// Keeps what reads as formatting in Notes/Mail/Pages — bold, italic, underline, strikethrough, links, size —
    /// and drops the source page's colours, background boxes and web fonts (which look wrong, esp. in dark mode).
    static func cleaned(_ source: NSAttributedString) -> NSAttributedString {
        let out = NSMutableAttributedString(attributedString: source)
        let all = NSRange(location: 0, length: out.length)
        out.removeAttribute(.backgroundColor, range: all)
        out.enumerateAttribute(.link, in: all) { link, range, _ in
            if link == nil { out.removeAttribute(.foregroundColor, range: range) }
        }
        out.enumerateAttribute(.font, in: all) { value, range, _ in
            guard let font = value as? NSFont else { return }
            let traits = font.fontDescriptor.symbolicTraits
            let weight = (font.fontDescriptor.object(forKey: .traits) as? [NSFontDescriptor.TraitKey: Any])?[.weight] as? CGFloat ?? 0
            var replacement = NSFont.systemFont(ofSize: font.pointSize,
                                                weight: (traits.contains(.bold) || weight >= NSFont.Weight.semibold.rawValue) ? .bold : .regular)
            if traits.contains(.italic) {
                replacement = NSFontManager.shared.convert(replacement, toHaveTrait: .italicFontMask)
            }
            out.addAttribute(.font, value: replacement, range: range)
        }
        return out
    }

    /// Web pages name fonts the importer can't embolden (e.g. the Apple system-font stack at weight 600), which
    /// silently turns bold into regular. Drop font-family declarations and map weights ≥ 600 to plain bold.
    static func normalizedWeights(_ html: String) -> String {
        var out = html
        let rules: [(String, String)] = [
            (#"font-family\s*:\s*(?:[^;"]|&quot;)*;?"#, ""),
            (#"font-weight\s*:\s*(?:[6-9]00|bold|bolder)\b"#, "font-weight: bold"),
        ]
        for (pattern, template) in rules {
            guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else { continue }
            out = regex.stringByReplacingMatches(in: out, range: NSRange(out.startIndex..., in: out), withTemplate: template)
        }
        return out
    }

    /// HTML safe to hand to the WebKit importer: no remote images, stylesheets, scripts, frames or media,
    /// so converting never makes network requests (e.g. tracking pixels) or runs code.
    public static func importable(_ html: String) -> String {
        var out = html
        let patterns = [
            #"<(script|style|iframe|object|video|audio|noscript)\b[^>]*>.*?</\1\s*>"#,
            #"<(img|link|embed|source|iframe|object|video|audio|meta|base)\b[^>]*/?>"#,
            #"@import[^;]*;?"#,
            #"url\([^)]*\)"#,
        ]
        for pattern in patterns {
            guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive, .dotMatchesLineSeparators]) else { continue }
            out = regex.stringByReplacingMatches(in: out, range: NSRange(out.startIndex..., in: out), withTemplate: "")
        }
        return out
    }

    /// The HTML to send with `text`, or nil if there is none or text + HTML would exceed the text cap.
    public static func fitting(text: String, html: String?) -> String? {
        guard let html, !html.isEmpty else { return nil }
        return text.utf8.count + html.utf8.count <= TextLimit.maxBytes ? html : nil
    }
}
