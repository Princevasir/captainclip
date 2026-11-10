# ClipBridge v2 (small files) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ⌘C a file (≤ 25 MB) on the Mac → paste it on the phone, with content moving only on paste/Save; Share → Send to Mac → file in ~/Downloads and on the Mac clipboard.

**Architecture:** New JSON messages on the existing encrypted v2 session (offer / offer-clear / fetch / file-start / file-chunk / file-end / file-error). Shared per-platform `FileSender`/`FileReceiver` stream files in 192 KiB chunks with SHA-256 verification. Senders prioritise control/text over chunks. Android serves offers through a `FileOfferProvider` content URI placed on the clipboard; the provider fetches + verifies before handing bytes to the pasting app.

**Tech Stack:** as v1 (Swift 6/SwiftPM/Network.framework/CryptoKit; Kotlin 2.0.21/AGP 8.7.3/JUnit 4). Adds UniformTypeIdentifiers (Mac), MediaStore + FileProvider-style ContentProvider (Android). No new third-party deps.

**Spec:** `docs/superpowers/specs/2026-11-10-clipbridge-v2-files-design.md`

## Global Constraints
- File cap **25 MB = 26_214_400 bytes**; raw chunk **196_608 bytes** (192 KiB, base64 ≈ 256 KiB); frame cap stays 1 MiB; text cap stays 256 KB.
- Offer lifetime **10 minutes**; Mac serves `fetch` only for the current offer, unchanged size+mtime.
- SHA-256 as 64 lowercase hex chars; chunk `data` = standard base64 with padding.
- Unknown message `t` values are **ignored** (never close / never AUTH_FAILED).
- One transfer per direction at a time; duplicate fetches of the same id share one transfer.
- Mac: Command Line Tools only, tests via `swift run selftest`. Android: minSdk 29/target 35, JVM tests + lint.
- Everything local: no remotes, no pushes.
- All v1 tests stay green.

## Wire messages (both sides must match exactly)
```
{"t":"offer","id":S,"name":S,"mime":S,"size":N,"ts":N}          Mac→phone
{"t":"offer-clear"}                                               Mac→phone
{"t":"fetch","id":S}                                              phone→Mac
{"t":"file-start","id":S,"name":S,"mime":S,"size":N,"sha256":S}   either
{"t":"file-chunk","id":S,"seq":N,"data":S}                        either (seq from 0)
{"t":"file-end","id":S}                                           either
{"t":"file-error","id":S,"reason":"expired|changed|too-large|io|cancelled|bad-data"}  either
```

## Review Focus
1. **Finder copy of a folder / several files / a 30 MB file** → nothing offered, *and* the file names are not sent as text. (Task 6 tests.)
2. **File edited on the Mac after ⌘C, then pasted on phone** → `file-error changed`, phone paste fails cleanly. (Task 6/7 tests.)
3. **Connection drops mid-transfer** → receiver deletes the partial temp file, provider read fails, no half file in Downloads. (Task 3/4/9 tests.)
4. **Clipboard text copied during a 25 MB transfer** → text arrives before the transfer ends. (Task 5/8 tests.)
5. **Copy detection seeing ClipBridge's own `content://` placeholder** → never sent to the Mac as text. (Task 11 test.)

---

### Task 1: Mac messages + tolerant decode
**Files:** Modify `mac/Sources/ClipBridgeCore/Message.swift`; Test `mac/Sources/selftest/FileMessageTests.swift`
**Produces:** new `Message` cases `offer(id:name:mime:size:ts:)`, `offerClear`, `fetch(id:)`, `fileStart(id:name:mime:size:sha256:)`, `fileChunk(id:seq:data: Data)` (base64 on the wire), `fileEnd(id:)`, `fileError(id:reason:)`, `unknown(type: String)`.
- [ ] Tests: each new case encodes with the exact `t` and keys above and roundtrips; `{"t":"future-thing"}` decodes to `.unknown("future-thing")` (no throw); `file-chunk` with invalid base64 throws; missing required key throws.
- [ ] RED → implement → GREEN (`swift run selftest` ALL PASS). Server/Client ignore `.unknown`.
- [ ] Commit.

### Task 2: Android messages + tolerant parse
**Files:** Modify `android/.../core/Message.kt`; Test `android/.../core/FileMessageTest.kt`
**Produces:** `Message.Offer(id,name,mime,size,ts)`, `OfferClear`, `Fetch(id)`, `FileStart(id,name,mime,size,sha256)`, `FileChunk(id,seq,data: ByteArray)` (equals by content), `FileEnd(id)`, `FileError(id,reason)`, `Unknown(type)`.
- [ ] Tests mirror Task 1, plus `parsesMacFileChunk`: Mac-encoded JSON `{"data":"AAEC","id":"x","seq":0,"t":"file-chunk"}` → data `[0,1,2]`.
- [ ] `Connection` treats `Unknown` as no-op (test: FakeMac sends `{"t":"future"}` then a clip → clip delivered, not closed).
- [ ] RED → GREEN (`./gradlew testDebugUnitTest`). Commit.

### Task 3: Mac FileSender / FileReceiver
**Files:** Create `mac/Sources/ClipBridgeCore/FileTransfer.swift`; Test `mac/Sources/selftest/FileTransferTests.swift`
**Produces:**
- `enum FileLimit { static let maxBytes = 26_214_400; static let chunkBytes = 196_608 }`
- `struct FileSender { init(id: String, url: URL, name: String, mime: String) throws; var start: Message; func chunks() -> AnySequence<Message>; let end: Message }` — reads lazily; throws `tooLarge` > cap; sha256 computed by streaming before start.
- `final class FileReceiver { init(start: Message, directory: URL) throws; func accept(_ message: Message) throws -> URL?` (returns final URL on verified `file-end`); `func abort()` (deletes temp). Temp name `<name>.clipbridge-part`; final name unique (`a.pdf`, `a (1).pdf`); sanitises `/` and leading `.` in names.
- [ ] Tests: roundtrip a 600 KB random file through sender→receiver (3+ chunks) → identical bytes & name; wrong seq → throws + temp removed; sha mismatch → throws + temp removed; total > declared size → throws; abort removes temp; existing `a.pdf` → `a (1).pdf`; name `../../evil` → stays inside directory; 26 MB file → `tooLarge`.
- [ ] RED → GREEN. Commit.

### Task 4: Android FileSender / FileReceiver
**Files:** Create `android/.../core/FileTransfer.kt`; Test `android/.../core/FileTransferTest.kt`
**Produces:** `object FileLimit { MAX_BYTES = 26_214_400; CHUNK_BYTES = 196_608 }`; `class FileSender(id, input: () -> InputStream, name, mime, size)` with `start(): Message.FileStart` (hashes by streaming once), `chunks(): Sequence<Message.FileChunk>`, `end()`; `class FileReceiver(start: Message.FileStart, dir: File)` with `accept(m): File?`, `abort()`.
- [ ] Tests mirror Task 3, plus `shaMatchesMac`: sha256 of bytes `00..ff` (256 bytes) == `40aff2e9d2d8922e47afd4648e6967497158785fbd1da870e7110266bf944880`.
- [ ] RED → GREEN. Commit.

### Task 5: Mac priority sending
**Files:** Modify `Session.swift` (chunk queue paced by `contentProcessed`, control messages bypass it), `Client.swift` (selftest/probe client gains `onOffer`, `fetch(id:into:completion:)`, `sendFile(url:)`, `onFile`)
**Produces:** `Session.sendChunked(_ messages: AnySequence<Message>, completion: (Bool) -> Void)`; at most 2 chunks in flight.
- [ ] Test: Server sends a 20 MB file stream to a Client while the test calls `server.send(text: "urgent")` after the first chunk → Client receives "urgent" before `file-end`.
- [ ] RED → GREEN. Commit.

### Task 6: Mac file copies → offers
**Files:** Modify `PasteboardWatcher.swift`; Create `OfferStore.swift`; Test `OfferTests.swift`
**Produces:** `PasteboardWatcher.onFile: ((URL) -> Void)?`; `PasteboardWatcher.onIgnoredFiles: (() -> Void)?` (folder/multi/too big); text path unchanged for non-file copies. `final class OfferStore { init(now: () -> Date); func make(url: URL) throws -> Message /*offer*/; func clear(); func lookup(id: String) -> Result<URL, OfferError> /* .expired, .changed */; var current: Message? }`
- [ ] Tests: private pasteboard with one file URL → `onFile` once and **no** `onText`; two file URLs → nothing + `onIgnoredFiles`; folder → nothing; 30 MB file → nothing; offer lookup after 10 min (injected clock) → `.expired`; after file modified (size/mtime) → `.changed`; after `clear()` / new offer → `.expired` for the old id.
- [ ] RED → GREEN. Commit.

### Task 7: Mac Bridge/Server file wiring
**Files:** Modify `Server.swift`, `Bridge.swift`; Test `BridgeFileTests.swift`
**Produces:** `Server.send(message:)`, `Server.onMessage` for file messages; `Bridge(server:watcher:offers:downloads: URL)`; `Bridge.onFileReceived: ((URL) -> Void)?`.
Behaviour: file copy → offer to phone (none sent if not connected; re-sent on connect if valid); text copy → `offer-clear` then text; `fetch` → stream via FileSender or `file-error`; phone `file-start…end` → FileReceiver in `downloads` → pasteboard holds the file URL (watcher must not re-offer it) → `onFileReceived`.
- [ ] Tests with Client: copy file → client gets offer (and no clip text); client fetch → bytes identical; modify file then fetch → `file-error changed`; text copy after file → `offer-clear` then clip; client `sendFile` → file in temp downloads dir, pasteboard has its URL, no offer echoed back; client disconnect mid-upload → no file and no `.clipbridge-part` left.
- [ ] RED → GREEN. Commit.

### Task 8: Android Connection file support
**Files:** Modify `sync/Connection.kt`; Test `sync/ConnectionFileTest.kt` (FakeMac extended with offer/serve/accept-file)
**Produces:** `Listener.onMessage(m: Message)` for file messages (default no-op so existing listeners compile); `Connection.sendControl(m: Message): Boolean` (priority queue); `Connection.sendChunk(m: Message.FileChunk)` (blocking put, bounded 4, never from main thread); writer drains control before chunks.
- [ ] Tests: offer from FakeMac reaches `onMessage`; `sendControl(Fetch)` reaches FakeMac; uploading 5 MB of chunks while sending text → FakeMac sees the clip before the last chunk.
- [ ] RED → GREEN. Commit.

### Task 9: Android SyncLoop file API
**Files:** Modify `sync/SyncLoop.kt`; Test `sync/SyncLoopFileTest.kt` (MultiFakeMac extended)
**Produces:** `Callbacks.onOffer(offer: Message.Offer?)` (null = cleared) default no-op; `fun fetch(id: String, dir: File, timeoutMs: Long = 60_000): File` (blocking, verified; throws `FileFetchException(reason)`; concurrent calls for same id share one transfer); `fun sendFile(name, mime, size, input: () -> InputStream): Boolean` (blocking, serial queue).
- [ ] Tests: offer → callback; fetch returns verified file; two concurrent fetches of same id → one `fetch` on the wire, both get the file; Mac answers `file-error expired` → exception reason `expired`; connection drops mid-fetch → exception `io`, temp removed; sendFile → MultiFakeMac has identical bytes; sendFile of 30 MB → false.
- [ ] RED → GREEN. Commit.

### Task 10: Android OfferCache + PreviewGuard
**Files:** Create `files/OfferCache.kt`, `files/PreviewGuard.kt`; Tests
**Produces:** `class OfferCache(dir: File, now: () -> Long)` — `set(offer)`, `clear()`, `current(): Message.Offer?` (null after 10 min), `cachedFile(id): File?` (only for current offer), `store(id, file)`, deletes files on clear/replace/expiry, `purgeAll()`. `object PreviewGuard { fun allow(method: Method /*OPEN_FILE, OPEN_TYPED_ASSET*/, caller: String?, blockedCallers: Set<String>, msSinceOffer: Long, sizeHint: Boolean): Boolean }` — false for typed-asset/thumbnail, blocked callers, < 1000 ms, size-hint queries.
- [ ] Tests for each rule and each deletion trigger.
- [ ] RED → GREEN. Commit.

### Task 11: Android provider, clipboard placeholder, Save, Share files
**Files:** Create `files/FileOfferProvider.kt`, `files/SaveFileActivity.kt` (notification target); Modify `SyncService.kt`, `GhostActivity.kt`, `ShareReceiverActivity.kt`, `capture/ClipPolicy.kt`, manifest.
- `ClipPolicy.shouldSend(text, sensitive, hasUri)` → false when the clip carries any URI. Test first (`ClipPolicyTest.uriClipsAreNeverSentAsText`).
- Provider: authority `app.clipbridge.files`, `grantUriPermissions=true`, not exported; logs every call (method, `callingPackage`) under tag `ClipBridgeFiles` for the preview measurement; applies `PreviewGuard` with blocked set = {default IME package, `com.android.systemui`, Samsung clipboard packages found on device}.
- On offer: `ClipData(ClipDescription(name, [mime]), Item(uri))` + `OwnWrites.markNow()` + notification "‹name› is on your Mac — Save". Save → fetch → MediaStore Downloads/ClipBridge, progress notification.
- Share: `ACTION_SEND`/`SEND_MULTIPLE` with `EXTRA_STREAM` (`*/*`) → SyncService `ACTION_SEND_FILES` carrying URIs + `FLAG_GRANT_READ_URI_PERMISSION` → sendFile each; toasts per spec.
- [ ] ClipPolicy test RED → GREEN; `assembleDebug testDebugUnitTest lintDebug` pass. Commit.

### Task 12: Mac app UI for files
**Files:** Modify `mac/Sources/ClipBridge/AppDelegate.swift`
- Downloads dir = `~/Downloads`; notification "‹name› from phone — in Downloads, ⌘V to paste"; menu status shows "Offering: ‹name›" while an offer is current.
- [ ] `./build-app.sh` builds; selftest green. Commit.

### Task 13: End-to-end on real devices
- [ ] Kotlin `MacInteropTest` extended (opt-in): offer received after `pbcopy`-style file copy (osascript `set the clipboard to POSIX file`), fetch identical bytes; sendFile lands in ~/Downloads.
- [ ] Install on phone; measure provider calls after a Mac file copy with no paste (keyboard open, clipboard history open) → decide preview guard set.
- [ ] Paste a PDF into Gmail compose; Save from notification; Share 2 PDFs to Mac; ⌘V in Finder.
- [ ] README updated. Commit.
