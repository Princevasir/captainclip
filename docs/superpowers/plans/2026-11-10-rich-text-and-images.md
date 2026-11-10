# Formatted Text + Clipboard Images Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Formatted (HTML) text syncs both ways with plain-text fallback; clipboard images go Mac → phone as paste-to-fetch offers and phone → Mac straight onto the Mac clipboard.

**Architecture:** Optional `html` on the existing `clip` message; optional `purpose` on `file-start` (`clipboard` = put on receiver's clipboard, not Downloads). Mac images reuse OfferStore/FileSender; phone images reuse SyncLoop.sendFile.

**Tech Stack:** as v2 (Swift/SwiftPM selftest; Kotlin/JUnit). No new dependencies.

**Spec:** `docs/superpowers/specs/2026-11-10-rich-text-and-images-design.md`

## Global Constraints
- `TextLimit.fits` applies to text + html combined (UTF-8 ≤ 262_144); if html would exceed it, send text only.
- Images ≤ 25 MB (`FileLimit.maxBytes`); Mac stores clipboard PNGs only in `~/Library/Caches/ClipBridge/clipboard-images/`.
- Unknown JSON keys stay ignored; old messages without `html`/`purpose` keep working.
- Concealed Mac items and Android sensitive clips are never sent (text, html or image).
- Everything local; deploy = install on the user's phone (USB) + rebuild/relaunch Mac app; no pushing.
- All existing tests stay green.

## Wire additions
```
{"t":"clip","id":S,"text":S,"ts":N,"html":S?}
{"t":"file-start",…,"purpose":"file"|"clipboard"?}     (absent = "file")
```

## Review Focus
1. **Huge HTML** (a 300 KB web page copy) → plain text still syncs, html dropped. (Tasks 3, 5)
2. **Echo of a received image/HTML** → Mac writing phone image/HTML must not trigger an offer/clip back. (Tasks 3, 7)
3. **Copying a file in Finder whose pasteboard also carries an icon image** → still a file offer, not an image offer. (Task 6)
4. **ClipBridge's own image placeholder on the phone clipboard** → never uploaded back to the Mac. (Task 8)
5. **Password-manager copies** → no html, no image sent. (Tasks 3, 6, 8)

---

### Task 1: Mac protocol fields
**Files:** `mac/Sources/ClipBridgeCore/Message.swift`; Test `mac/Sources/selftest/RichMessageTests.swift`
**Produces:** `Message.clip(id:text:ts:html: String? = nil)`; `Message.fileStart(id:name:mime:size:sha256:purpose: String = "file")`. Encoding omits `html` when nil and `purpose` when `"file"`.
- [ ] Tests: clip with html roundtrips and JSON has `html`; clip without html has no `html` key; old JSON without html decodes (html nil); file-start purpose `clipboard` roundtrips; missing purpose decodes as `file`.
- [ ] RED → GREEN (`swift run selftest`), update all `.clip(`/`.fileStart(` patterns. Commit.

### Task 2: Android protocol fields
**Files:** `android/.../core/Message.kt`; Test `core/RichMessageTest.kt`
**Produces:** `Message.Clip(id, text, ts, html: String? = null)`; `Message.FileStart(…, purpose: String = "file")`.
- [ ] Tests mirror Task 1 plus `parsesMacRichClip` with Mac sorted-key JSON.
- [ ] RED → GREEN (`./gradlew testDebugUnitTest`). Commit.

### Task 3: Mac formatted text
**Files:** Create `mac/Sources/ClipBridgeCore/RichText.swift`; Modify `PasteboardWatcher.swift`, `Bridge.swift`, `Server.swift`, `Client.swift`; Test `RichTextTests.swift`
**Produces:** `enum RichText { static func html(from: NSPasteboard) -> String?` (public.html, else RTF→HTML); `static func write(text:html:to:)` (string + html + rtf); `static func fitting(text:html:) -> String?` (html or nil if combined > cap) }`; `PasteboardWatcher.currentHTML() -> String?`, `write(_ text:, html:)`; `Server.onClip: (String, String?) -> Void`, `Server.send(text:html:)`; `Client.send(text:html:)`, `Client.onClip` delivers `(text, html)` via new `onRichClip`.
- [ ] Tests: pasteboard with html → `html(from:)` returns it; RTF-only pasteboard → HTML containing `<b>`/`font-weight` for bold; `write` puts all three types; `fitting` drops html over cap; Bridge: Mac copy with HTML → client receives clip with html; client sends clip with html → Mac pasteboard has `.html` and `.rtf`, no clip echoed back; concealed html copy sends nothing.
- [ ] RED → GREEN. Commit.

### Task 4: Android formatted text
**Files:** Modify `sync/Connection.kt` (`send(text, html)`, `Listener.onClip(text, html)`), `sync/SyncLoop.kt` (`send(text, html)`, `Callbacks.onClip(text, html)`), `SyncService.kt` (newHtmlText), `GhostActivity.kt`, `MainActivity.kt`, `ShareReceiverActivity.kt`; Tests in `ConnectionTest`, `SyncLoopTest`, new `core/RichClipTest.kt`
**Produces:** `RichClip.fitting(text, html): String?` (same rule as Mac).
- [ ] Tests: Connection sends html to FakeMac; html from FakeMac reaches listener; oversized html dropped but text sent; SyncLoop passes html through; echo guard still keyed on text.
- [ ] RED → GREEN; `assembleDebug lintDebug`. Commit.

### Task 5: (merged into 3/4 — size rule tested on both sides)

### Task 6: Mac clipboard images → offers
**Files:** Create `ClipboardImage.swift`; Modify `PasteboardWatcher.swift` (`onImage: (Data) -> Void`), `Bridge.swift`; Test `ClipboardImageTests.swift`
**Produces:** `enum ClipboardImage { static func png(from: NSPasteboard) -> Data?` (png, else tiff→png; nil if file URLs present or concealed); `static func save(_ png: Data, in dir: URL, now: Date) throws -> URL` (name `Screenshot YYYY-MM-DD at HH.mm.ss.png`, deletes older files in dir) }`.
- [ ] Tests: PNG pasteboard → onImage, no onText; TIFF → PNG data (starts with PNG signature); Finder file copy with icon → onFile only; concealed image ignored; image > 25 MB ignored; Bridge: image copy → offer `image/png` named `Screenshot …png`; client fetch → identical PNG; older cached image deleted on new one.
- [ ] RED → GREEN. Commit.

### Task 7: Mac receives phone images onto the clipboard
**Files:** Modify `Bridge.swift`, `PasteboardWatcher.swift` (`writeImage(png:)`); Test `ClipboardImageTests.swift`
- [ ] Tests: client `sendFile(purpose: "clipboard")` of a PNG → Mac pasteboard has `.png` data equal to it; nothing in Downloads; no offer/clip sent back; `purpose` file still goes to Downloads.
- [ ] RED → GREEN. Commit.

### Task 8: Android sends copied images
**Files:** Create `capture/ClipImagePolicy.kt`; Modify `CopyDetect.kt` ("copy image"), `GhostActivity.kt`, `MainActivity.kt` (Send clipboard), `SyncLoop.sendFile(…, purpose)`, `SyncService` (`sendClipboardImage`); Tests `ClipImagePolicyTest`, `CopyDetectTest`, `SyncLoopFileTest`
**Produces:** `ClipImagePolicy.accepts(scheme, authority, mime, sensitive, size): Boolean`.
- [ ] Tests: image/png from other app accepted; our own authority refused; non-image refused; sensitive refused; > 25 MB refused; `CopyDetect.matches("Copy image")` true; `sendFile(purpose="clipboard")` → MultiFakeMac receives FileStart with purpose clipboard.
- [ ] RED → GREEN; `assembleDebug lintDebug`. Commit.

### Task 9: Final review, deploy, device test
- [ ] Fresh reviewer on the branch diff; fix Critical/Important with RED→GREEN.
- [ ] Build Mac app + debug APK; install on phone via USB (no force-stop); relaunch Mac app.
- [ ] Device checks: Safari bold text → Gmail on phone formatted; Gmail formatted text on phone → Notes on Mac formatted; ⌃⇧⌘4 screenshot → paste in WhatsApp/Gmail on phone; Samsung screenshot "Copy" → ⌘V in Notes on Mac.
- [ ] README updated. Commit.
