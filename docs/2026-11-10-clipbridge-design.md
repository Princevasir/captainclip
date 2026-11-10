# ClipBridge — Mac ↔ Android clipboard sharing (design)

## Why
Copying text between a MacBook and an Android phone on the same Wi-Fi should feel as close to
Apple's Universal Clipboard as Android allows. Existing tools (KDE Connect, AirSync, etc.) were
considered; we're building our own small pair of apps.

## Goals
- Mac → Android: fully automatic. Copy on Mac, paste on phone.
- Android → Mac: automatic in most apps (copy detected via an Accessibility service, as ClipSync/AirSync do),
  with a one-tap fallback (Quick Settings tile, notification button, Share → ClipBridge) where detection misses.
- Same Wi-Fi only. No server, no account, no internet.
- One-time pairing; traffic encrypted.

## Non-goals (v1)
- Images, files (later: +1–2 days).
- Syncing over the internet / different networks.
- Root/ADB/Shizuku clipboard tricks (brittle).
- Clipboard history.

## Anti-goals
- Anything that leaks clipboard contents to the network unencrypted or to a third party.
- Draining the phone battery noticeably.

## Approaches considered
1. **Use an existing app (AirSync / KDE Connect)** — ~1 hour, but licensing/macOS reliability questions; user chose to build.
2. **Own apps, direct Wi-Fi connection (chosen)** — small Mac menu-bar app + Android app; full control.
3. **AirDrop via Quick Share** — share-sheet only, not a clipboard; good for files, not text snippets.

## How it works
**Mac app (Swift, menu-bar only, built with Swift Package Manager — no Xcode needed)**
- Watches the clipboard (checks for changes ~2×/second, cheap).
- Advertises itself on the Wi-Fi via Bonjour and listens for the phone.
- On new copied text: encrypts and sends it to the connected phone.
- On text received from phone: puts it on the Mac clipboard, shows a small notification.
- Menu: "Pair phone" (shows QR code), connection status, pause syncing, quit.

**Android app (Kotlin, Android 10+)**
- Pairing: scan the Mac's QR code once (contains the Mac's ID + a random 256-bit key).
- A background service with a small persistent notification keeps the connection open,
  finds the Mac automatically on Wi-Fi, reconnects when Wi-Fi returns.
- Text from Mac → written straight to the phone clipboard.
- Automatic sending to Mac: an Accessibility service notices a copy (the "Copy" action on a text
  field, or a "Copied" toast), then opens an invisible "ghost" screen for a split second
  (Android only lets the visible app read the clipboard), reads it, sends it, closes.
  User turns this on once in Settings → Accessibility; on Android 13+ a sideloaded app first needs
  App info → ⋮ → "Allow restricted settings".
- Fallback / manual: Quick Settings tile, notification "Send clipboard" button, and Share target,
  all using the same ghost screen.

## Borrowed ideas and code
| From | What | License → how we use it |
|---|---|---|
| ClipSync (github.com/WinShell-Bhanu/Clipsync) | Accessibility copy detection, ghost activity, AES-GCM helper, echo-loop guard, QR pairing | MIT → may copy/adapt code; keep its copyright notice in THIRD_PARTY_NOTICES.md |
| AirSync | Same approach (itself adapted from ClipSync); QR pairing UX | Mixed/commercial terms on Mac app → ideas only |
| KDE Connect | "Send clipboard" tile + notification button, floating-activity read | GPL → ideas only, no code copied |
| Apple Universal Clipboard | One-time trust/pairing, short expiry so sensitive copies don't linger | Ideas only |
| LocalSend | Local-network discovery + encrypted direct transfer pattern | Ideas only |

We do not take ClipSync's Firebase/cloud mode, BLE, OTP-reading or update-installer code.

**Protocol**
- TCP connection, each message: length + AES-256-GCM encrypted JSON `{type, text, id, ts}`.
- Key comes only from the QR code; never sent over the network.
- Loop guard: a device ignores a clipboard change that it just received from the other side.

## Constraints
- Mac: macOS 26 here; Swift 6 command line tools present. Target macOS 13+.
- Android build needs JDK 17 + Android SDK command-line tools (~2 GB) installed via Homebrew,
  or Android Studio. Phone needs USB debugging (or install the APK by file) to try it.

## Estimate
~3–4 working days for text-only v1 with automatic both ways, including testing on real devices
(reusing ClipSync code saves roughly a day; tuning copy detection across apps is the main unknown).

## Left to the builder
- Exact retry/backoff timings, notification wording, icons.
- Which Android foreground-service type satisfies Android 14+ rules.
