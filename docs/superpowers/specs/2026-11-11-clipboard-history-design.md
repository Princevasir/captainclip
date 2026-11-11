# ClipBridge — clipboard history on the phone (design)

## Why
People reach for something they moved between Mac and phone a little while ago ("that link I sent to the
phone this morning", "the address I pasted on the Mac yesterday") and today it's gone the moment the next
copy replaces it. Competing apps (Rayclip: "Every clip, one search away") show a searchable history; it's also
a strong store-screenshot feature for the Play launch.

## Goals
1. A **History** screen on the **phone** (both ClipBridge and ClipBridge Pro): a search box and a stack of
   recent clips, newest first.
2. **Only what crossed** between the devices through ClipBridge is recorded — never other phone copies,
   never the Mac's own clipboard.
3. A **window**: entries drop off by **count** and by **age**, whichever limit is hit first.
4. Reuse: tap a clip to put it back on the phone's clipboard; send a clip to the Mac again; delete entries;
   clear all.
5. Stored **encrypted, only on the phone**.

## Non-goals
History on the Mac. Syncing history between devices. Keeping file or image *contents*. Unlimited history.
Recording copies that never crossed (Android can't read them in the background anyway).

## Anti-goals
- History leaking: readable by other apps, backups, or a rooted file browser; sensitive copies (passwords)
  ever being stored.
- Re-sending loops: putting a history clip back on the clipboard must not bounce it to the Mac by itself
  (Pro's automatic copy) unless the user taps Send to Mac.
- Breaking the privacy promise: nothing leaves the phone; Play's "No data collected" stays true.

## Approaches (picked: A)
- **A. One encrypted file in the app's private storage (chosen by the user).** The whole stack (≤ 100
  entries) is a small JSON document, encrypted with AES-GCM using a key kept in the Android Keystore (never
  exported). Loaded into memory; search filters in memory. No new dependencies.
- B. Same file without encryption — simplest, but the history sits on disk in the clear (sandboxed only).
- C. A Room/SQLite database — built for far more rows than we keep; a new dependency and more code.

## What is recorded (decided)
"Crossed" means ClipBridge actually transferred it:

| Event | Entry |
|---|---|
| Text (with or without formatting) arrives from the Mac and is put on the phone clipboard | text clip, direction **from Mac**, text + HTML kept |
| Text is sent to the Mac successfully (Send to Mac tile, in-app button, Share, or Pro's automatic copy) | text clip, direction **to Mac**, text + HTML kept |
| A Mac file/image is actually fetched (pasted into an app, Save, or Share) | file entry, **from Mac**: name, type, size — no content |
| A phone file/image is uploaded to the Mac successfully (Share → Send to Mac, copied image) | file entry, **to Mac**: name, type, size — no content |

- Mac file *offers* that are never fetched are not recorded (they didn't cross).
- Sensitive copies are never sent today, so they can never reach history; the recorder still refuses any
  clip flagged sensitive, as a second guard.
- **Duplicates:** if the new text equals the newest entry's text (same direction or not), that entry moves to
  the top with the new time instead of adding a copy. Older identical entries further down are kept.
- A history clip re-sent to the Mac creates a normal "to Mac" entry (it crossed again).

## The window (decided by the user: by time or count; values are my call)
- Defaults: **50 entries** and **7 days**. Settings offer **25 / 50 / 100** entries and **1 day / 7 days /
  30 days**. Pruning happens on every write and on opening the screen.
- **Clear history** removes everything (with a confirm). Uninstalling removes the file and the key.
- Turning history **off** (a switch, default on) stops recording and clears what's stored.

## The screen (my call)
Reached from a **"Clipboard history"** row on the main screen (above "Mac app setup guide") and, when there
are entries, a one-line "Last: …" preview on that row.

```
 ←  Clipboard history                      ⋮ (Settings, Clear history)
 ┌──────────────────────────────────────┐
 │ 🔍 Search                             │
 └──────────────────────────────────────┘
 From Mac · 2 min ago
 https://example.com/order/1234
 ─────────────────────────────────────────
 To Mac · 1 h ago
 Flat 4B, Rose Court, MG Road, Pune 411001 …
 ─────────────────────────────────────────
 From Mac · Yesterday · 📄 Invoice.pdf · 420 KB
```
- Each text entry: direction, relative time, up to 3 lines of preview (formatting shown as plain text).
- Tap a text entry → copied to the phone clipboard (with its formatting), toast "Copied". This write is marked
  as ClipBridge's own (OwnWrites) so Pro's automatic copy doesn't send it to the Mac.
- Long-press (or a ⋮ on the entry) → **Copy**, **Send to Mac** (only while connected), **Delete**.
- File entries are informational (no content kept): Delete only.
- Search: case-insensitive "contains" over text and file names; live as you type.
- Empty states: "Nothing yet — clips you send between your Mac and phone appear here." / "No matches."

## Storage and privacy (decided: A)
- File `files/history.bin` in app-private storage; `allowBackup=false` already set.
- AES-256-GCM, random 12-byte IV per write, key alias `clipbridge.history` in AndroidKeyStore; written
  atomically (temp file + rename). If the key or file can't be read (e.g. key invalidated), the history is
  dropped and starts empty — never crash.
- `PRIVACY.md` and the Play pack: replace "nothing stored" with "a short, encrypted history of what you sent
  between your devices, kept only on your phone, up to your chosen limit; you can turn it off or clear it".
  Data safety answers stay "No data collected" (on-device only).

## Constraints
- Kotlin, minSdk 29, target 36; no new dependencies.
- All logic (recording rules, dedupe, window pruning, search, serialization) is plain Kotlin unit-tested with
  a fake cipher and clock; the Keystore cipher is a thin adapter verified on the device.
- Same behaviour in ClipBridge and ClipBridge Pro.

## Decisions from the builder check (my calls)
1. **Send to Mac from history always sends**, even when the text equals the last clip received from or sent to
   the Mac. The echo guard (LoopGuard) exists to stop *automatic* bounces; an explicit tap is intent. This is a
   separate "user-initiated" send path; automatic copy and the tile keep the guard unchanged (tested both ways).
2. **File entries: one per offer/transfer id.** Recorded when that id first crosses: the first successful
   `SyncLoop.fetch` for a Mac offer id (paste, Save or Share — whichever comes first), or a successful upload
   for a phone file. Cached re-reads, provider probes and refused previews add nothing. A multi-file Share/Save
   gives one entry per file. Entries with the same id are never duplicated.
3. **Dedupe takes the latest crossing:** when equal text moves to the top it takes the new time **and the new
   direction** (the label shows how it crossed most recently).
4. **Size:** text is stored in full (the wire limit already caps text + HTML at 256 KB). HTML is kept only when
   it is ≤ 64 KB; otherwise the entry keeps the plain text only (still copyable). Typical history stays well
   under 1 MB, so rewriting the encrypted file on each change is fine.
5. **"Sent" means accepted by the live connection** (`send` returned true on an authenticated session). Both
   transports are reliable streams; a connection that drops before delivery is rare and acceptable here.
6. **One process:** the app runs in a single process, so one in-memory store (singleton) with a lock owns the
   history; every writer goes through it, and it persists after each change.

## Left to the builder
Exact layout/spacing in the existing card style, toast wording, the settings UI form (dialog vs. small
screen), file format details (JSON field names, version number), and where exactly the recording calls sit in
SyncService/SyncLoop as long as they fire only on successful transfers.
