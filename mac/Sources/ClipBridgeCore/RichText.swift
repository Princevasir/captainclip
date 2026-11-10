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
    public static func write(text: String, html: String, to pasteboard: NSPasteboard) {
        pasteboard.clearContents()
        pasteboard.setString(text, forType: .string)
        pasteboard.setString(html, forType: .html)
        if let data = html.data(using: .utf8),
           let attributed = try? NSAttributedString(data: data, options: [.documentType: NSAttributedString.DocumentType.html,
                                                                          .characterEncoding: String.Encoding.utf8.rawValue],
                                                    documentAttributes: nil),
           let rtf = try? attributed.data(from: NSRange(location: 0, length: attributed.length),
                                          documentAttributes: [.documentType: NSAttributedString.DocumentType.rtf]) {
            pasteboard.setData(rtf, forType: .rtf)
        }
    }

    /// The HTML to send with `text`, or nil if there is none or text + HTML would exceed the text cap.
    public static func fitting(text: String, html: String?) -> String? {
        guard let html, !html.isEmpty else { return nil }
        return text.utf8.count + html.utf8.count <= TextLimit.maxBytes ? html : nil
    }
}
