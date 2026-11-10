# ClipBridge v2 — small files (PDFs, docs) between Mac and Android

## Why
The user moves PDFs and documents between their MacBook and Galaxy S25 Ultra (Android 17, One UI 9)
and wants it to work like the clipboard does now, with no separate "send" step — but **nothing they copy
on the Mac may leave the Mac until they paste it on the phone** (Apple Universal Clipboard model:
announce on copy, transfer on paste).

## Decisions made with the user
- **Text stays as in v1**: Mac → phone text is sent on copy (Android can only paste text that is
  already on its clipboard). Only files use paste-to-fetch.
- **Mac → phone files: announce on copy, fetch on paste.** Copying a file in Finder (⌘C) sends only
  an encrypted *offer* (name, type, size). File content moves only when the phone pastes it (or the user
  taps "Save" on the phone).
- **Phone → Mac files: explicit.** Share → *Send to Mac* sends immediately (sharing is the decision).
- **Size limit 25 MB per file** in v2. Transfers are chunked so nothing large is held in memory.
- Quick Share ↔ AirDrop (built into One UI 9) remains the tool for big/ad-hoc files; ClipBridge is for
  "copy here, paste there".
- Everything local; no servers, no pushing code anywhere.

## Goals
1. ⌘C on one file (≤ 25 MB) in Finder → the phone's clipboard holds a placeholder for it. Pasting into an
   app that accepts files (Gmail compose, Drive, chat apps that accept pasted files) inserts the real file.
2. A low-priority phone notification "‹name› is on your Mac — Save" pulls it into Downloads/ClipBridge for apps
   that don't accept pasted files.
3. Share → Send to Mac with one or more files (each ≤ 25 MB) → files appear in the Mac's ~/Downloads and on
   the Mac clipboard, so ⌘V pastes them into Finder/Mail/Slack. Mac notification says it arrived.
4. Clipboard text keeps working, and is never stuck behind a file transfer.
5. Finder copies no longer leak the file *name* to the phone as text (v1 bug).

## Non-goals (v2)
- Files > 25 MB, folders, resuming interrupted transfers, transfer history.
- Image *data* on the Mac clipboard that isn't a file (e.g. screenshot-to-clipboard) — later.
- Copying several files at once in Finder (v2 offers only when exactly one regular file is copied).
- Automatic phone → Mac files (Android has no "copy a file" to a system clipboard).
- A "Paste from Mac" floating button (discussed; not wanted now).

## Anti-goals
- Any file content leaving the Mac before the phone asks for it.
- A phone keyboard's clipboard-history preview silently downloading the file (must be checked on device).
- Serving a file that changed or an offer that expired.
- Crashes or memory spikes from large transfers; clipboard text delayed by file transfers.

## Approaches considered
1. **Same encrypted connection, chunked messages (chosen).** Reuses pairing, challenge handshake,
   encryption, reconnects. Text gets priority over file chunks. Smallest change.
2. Separate connection per transfer — isolates big transfers, but more code for no benefit at 25 MB.
   The message format below is reusable if we move to this for multi-GB files later.
3. Mac HTTP server (LocalSend-style) — second security model, certificates; too much for PDFs.
4. Quick Share/AirDrop — can't be triggered by ⌘C or by paste.

## How it works

### Protocol additions (same v2 session: challenge + direction-bound AAD; same 1 MiB frame cap)
All JSON, sealed like existing messages. `id` = random UUID string. Chunk data is base64.
- Mac → phone `{"t":"offer","id","name","mime","size","ts"}` — a file is on the Mac clipboard.
- Mac → phone `{"t":"offer-clear"}` — the Mac clipboard no longer holds that file (text or another copy).
- Phone → Mac `{"t":"fetch","id"}` — please send offer `id`.
- Either direction `{"t":"file-start","id","name","mime","size","sha256"}` → N × `{"t":"file-chunk","id","seq","data"}`
  (raw chunk 192 KiB → ~256 KiB base64, well under the 1 MiB frame) → `{"t":"file-end","id"}`.
- Either direction `{"t":"file-error","id","reason"}` — `expired`, `changed`, `too-large`, `io`, `cancelled`.
- Receiver writes chunks straight to a temp file, checks `seq` order and total size, verifies SHA-256 at
  `file-end`, then renames into place. Any mismatch → delete temp file, `file-error`.
- **Priority:** each side's sender keeps two queues — control/text first, file chunks second — so a
  clipboard text or ping always goes out before the next chunk. A file sender thread may block waiting for
  room in the chunk queue (never Android's main thread).

### Mac
- **PasteboardWatcher**: on change, if the pasteboard holds file URLs → if exactly one regular file
  ≤ 25 MB and not concealed → `onFile(url)`; otherwise (folder, many files, too big) → nothing is offered and
  nothing is sent as text. Plain text behaves as v1. (Finder's accompanying file-name text is ignored.)
- **OfferStore**: one current offer {id, url, size, mtime, createdAt}. Replaced by the next copy; cleared on
  text copy. `fetch` is served only if id is current, < 10 min old, and the file's size+mtime are unchanged;
  otherwise `file-error` (`expired`/`changed`).
- On phone connect: re-send the current offer (if still valid), like v1 re-sends current text.
- **Incoming files**: written to `~/Downloads/<name>.clipbridge-part`, verified, renamed to a unique name
  (`report.pdf`, `report (1).pdf`…). The file URL is put on the pasteboard (without triggering an offer back).
  Notification "‹name› from phone — in Downloads, ⌘V to paste" (name is fine; it's the user's own share).

### Android
- **FileOfferProvider** (`content://app.clipbridge.files/offer/<id>/<name>`, not exported, grants via clip):
  `getType`/`query` (name, size) answer from the offer's metadata — no network. `openFile` fetches the whole
  file into app cache, verifies it, then returns a descriptor on the cached file (see "Builder's calls").
  Expired/unavailable → the read fails with an error; a toast says why.
- On `offer`: phone clipboard = ClipData(mime, uri) labelled with the name; `OwnWrites.markNow()`;
  show the "Save" notification. On `offer-clear`: if our offer is still on the clipboard, leave it (pasting will
  just fail with "expired") but remove the notification.
- **Copy detection fix:** GhostActivity must never send our own `content://app.clipbridge.files/...` (or any URI
  clip) as text to the Mac.
- **Save**: notification action fetches into `Downloads/ClipBridge/` via MediaStore, with a progress notification.
- **Share → Send to Mac**: ShareReceiverActivity accepts `ACTION_SEND`/`ACTION_SEND_MULTIPLE` with `EXTRA_STREAM`
  (any type) as well as text. It hands the URIs to SyncService (read-URI grant carried on the intent); SyncService
  streams each file (size checked first, ≤ 25 MB) with a progress notification. Toast: "Sending 2 files to Mac" /
  "Not connected to your Mac" / "Too large (max 25 MB)".

### Clipboard previews (user's decision: pull-only fallback)
First, measure on the user's S25 Ultra whether Samsung Keyboard / clipboard history (or any other app) opens
the placeholder without a paste (log every provider call with caller package and method).
- **If nothing opens it without a paste:** ship as designed.
- **If something does:** keep the placeholder on the clipboard, but the provider serves file *content* only to a
  genuine paste target. It refuses: `openTypedAssetFile`/thumbnail-style requests (`image/*` previews, `android:query-arg-size`),
  callers that are the keyboard / system clipboard UI (the default IME package, `com.android.systemui`,
  Samsung clipboard packages, identified on device), and any open within 1 s of the offer arriving.
  Refused previews just show the name (from `query`). Normal paste into Gmail/Drive/chat stays automatic.

### Builder's calls (from the design review)
- **Verify before serving.** The phone fetches the whole file into app cache, checks size + SHA-256, and only
  then hands bytes to the pasting app (`openFile` blocks until verified, then returns a file descriptor on the
  cached file — seekable, known size). ~1–3 s for 25 MB on home Wi-Fi. A truncated or changed file is never
  pasted.
- **Both apps are always updated together** (single user, both installed by us) — no mixed-version support.
  But unknown message types are now *ignored* on both sides instead of closing the connection / counting as a
  key failure, so future additions can't masquerade as a broken pairing.
- **Phone cache follows the offer.** The cached copy may be served only while the offer is current on the phone
  (not replaced, not cleared, < 10 min old). Deleted on `offer-clear`, on the next offer, after 10 minutes, and on
  app start. Saved copies in Downloads are the user's and are never deleted.
- **One transfer per direction at a time.** Duplicate fetches of the same `id` (paste + Save, second paste) share
  one transfer. A new offer cancels an in-flight fetch of the old one. Phone → Mac shares are queued one file
  after another. Mac → phone and phone → Mac transfers may run at the same time.

## Constraints
- Keep v1 behaviour and all v1 tests green; protocol stays one connection with the v2 challenge handshake.
- Android minSdk 29, targetSdk 35; Mac macOS 13+, Command Line Tools only (selftest).
- Mac app may need macOS permission for ~/Downloads (TCC prompt on first incoming file).
- Text cap stays 256 KB; file cap 25 MB; frame cap 1 MiB.

## Left to the builder
- Exact chunk queue sizes, notification wording/icons, temp-file naming.
- Whether the cached fetched file is reused for "Save" (nice, not required).
- MIME detection details (UTType on Mac; ContentResolver.getType on Android).
