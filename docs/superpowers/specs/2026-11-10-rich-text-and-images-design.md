# ClipBridge — formatted text and clipboard images (design)

## Why
Copies currently lose formatting, and images on the clipboard (screenshots, "Copy image") don't transfer.
The user wants both, keeping the existing rule: **Mac files/images move only on paste**.

## Decisions (approved by the user)
1. **Formatted text, both ways, sent on copy (like text today).** If the copy has an HTML version, it travels with
   the plain text in the same `clip` message. Receiver puts both on the clipboard; apps pick what they support.
   Combined size (text + html, UTF-8) ≤ 256 KB, else only plain text is sent.
2. **Mac → phone images: like files.** An image on the Mac clipboard (⌃⇧⌘4, "Copy Image") becomes an offer
   ("Screenshot 2026-11-10 at 19.40.12.png", image/png, size). Transferred only on paste or *Save to Downloads*.
   10-minute expiry, 25 MB cap.
3. **Phone → Mac images: sent on copy** (automatic copy, or the Send to Mac tile). On the Mac the image goes **onto
   the clipboard as an image** (not saved to Downloads).
4. Passwords/secure content skipped as before (concealed Mac items, Android EXTRA_IS_SENSITIVE).

## Protocol (backwards-compatible additions; unknown keys are ignored by both apps)
- `clip` gains optional `"html": String`.
- `file-start` gains optional `"purpose": "file" | "clipboard"` (default `file`). `clipboard` = put on the
  receiver's clipboard instead of Downloads (used phone → Mac for images).

## Mac
- **Reading formatted copies:** `public.html` if present, else `public.rtf` converted to HTML (Notes, Pages, TextEdit
  only put RTF). **Writing:** plain text + HTML + RTF (converted from HTML) so Pages/Notes/Mail/Word all paste formatted.
- **Images:** pasteboard with image data (PNG or TIFF) and no file URLs → PNG written to
  `~/Library/Caches/ClipBridge/clipboard-images/` → offered like a file (name "Screenshot <date>.png").
  Older cached images are deleted when a new one is offered.
- **Phone images (purpose clipboard):** received to the cache folder, verified, then written to the pasteboard as
  PNG + TIFF without triggering an offer back; never placed in Downloads.

## Android
- **Writing:** `ClipData.newHtmlText(label, text, html)` when html is present.
- **Reading:** `item.htmlText` sent along with the text (ghost screen, tile, in-app button, Share of text).
- **Images:** a clip whose item is a `content://` URI with an `image/*` type (not ClipBridge's own authority, not
  sensitive, ≤ 25 MB) is read while the ghost screen holds the clip grant, copied to cache, and sent with
  purpose `clipboard`. Copy detection also recognises "Copy image" labels.

## Non-goals
Images inside formatted text (inline images in HTML), animated GIF fidelity, video, image formats other than
PNG/TIFF on the Mac side (others are converted to PNG), clipboard history.

## Approaches considered
1. **Extend existing messages (chosen):** optional `html` on clip, `purpose` on file-start; reuse file transfer.
2. Separate message types for rich text and images — more code for the same result.
3. Send formatting as RTF instead of HTML — Android has no RTF clipboard support; HTML is the common format.
