# ClipBridge

**Copy on your Mac, paste on your Android phone — and back.** Text and small files (PDFs, docs, images)
move between a Mac and an Android phone on the same Wi-Fi, directly and end-to-end encrypted.
No account, no cloud, no server.

| | |
|---|---|
| 🖥 **Mac app** | [**Download ClipBridge for Mac**](https://github.com/Princevasir/clipbridge/releases/latest/download/ClipBridge-mac.zip) · macOS 13 Ventura or later · Apple silicon and Intel |
| 📱 **Android app** | [**Download ClipBridge for Android**](https://github.com/Princevasir/clipbridge/releases/latest/download/ClipBridge-android.apk) · Android 10 or later |

All versions and checksums: [Releases](https://github.com/Princevasir/clipbridge/releases).

---

## What it does

- **Text, Mac → phone:** copy on the Mac, paste on the phone. Automatic.
- **Text, phone → Mac:** copy in most Android apps and it's on the Mac clipboard. Where an app copies
  silently, use the **Send to Mac** Quick Settings tile, **Share → Send to Mac**, or **Send clipboard to Mac** in the app.
- **Files, Mac → phone (up to 25 MB):** ⌘C a file in Finder. The phone only learns its name and size; the file
  itself moves **only when you paste it** (into Gmail, chat apps, Drive…) or tap **Save to Downloads** in ClipBridge.
  Offers expire after 10 minutes or at your next copy.
- **Formatted text, both ways:** bold, italics, links and lists survive when the app you copy from includes
  formatting (Chrome, Safari, Docs, Notes, Mail…). Pastes take on the destination's own font and colours.
- **Images and screenshots:** a ⌃⇧⌘4 screenshot or "Copy Image" on the Mac is pasted on the phone like a file
  (fetched only when you paste). An image copied on the phone (Chrome "Copy image", screenshot "Copy") goes
  straight onto the Mac clipboard — ⌘V in Notes, Messages, Slack.
- **Files, phone → Mac:** **Share → Send to Mac** from any app. Files land in your Mac's **Downloads** and on its
  clipboard, ready for ⌘V in Finder or Mail.
- **Private by design:** a direct encrypted connection on your own Wi-Fi (AES-256-GCM with a key that only ever
  travels in the pairing QR code). Passwords copied from password managers are never sent.

## Install

### 1. Mac

1. Download **[ClipBridge-mac.zip](https://github.com/Princevasir/clipbridge/releases/latest/download/ClipBridge-mac.zip)**
   and double-click it to unzip.
2. Drag **ClipBridge.app** into your **Applications** folder.
3. Open it. Because ClipBridge isn't from the App Store or notarised by Apple, macOS blocks the first launch:
   - **macOS 15 Sequoia and later:** click **Done** on the warning, then open **System Settings → Privacy &
     Security**, scroll down and click **Open Anyway** next to ClipBridge, and confirm.
   - **macOS 13–14:** right-click (or Control-click) ClipBridge.app → **Open** → **Open**.
   - Or, in Terminal: `xattr -dr com.apple.quarantine /Applications/ClipBridge.app`
4. A clipboard-with-a-bridge icon appears in the **menu bar** (top-right). There's no Dock icon — that's normal.
5. When macOS asks, allow ClipBridge to **find devices on your local network**, to **use its key in your
   Keychain** (click **Always Allow**), and to send **notifications** (optional).
6. Optional: menu-bar icon → **Start ClipBridge at login**.

### 2. Android phone

1. On the phone, open **[ClipBridge-android.apk](https://github.com/Princevasir/clipbridge/releases/latest/download/ClipBridge-android.apk)**
   (download it in Chrome, or send it from the Mac with Quick Share / AirDrop).
2. Tap the downloaded file to install. If Android asks, allow **Install unknown apps** for Chrome or My Files.
   - **Samsung phones:** if installing is blocked, turn off **Settings → Security and privacy → Auto Blocker**
     while you install (you can turn it back on afterwards).
   - **Google Play Protect** may say the developer is unknown → **More details → Install anyway**.
3. Open **ClipBridge**.

### 3. Pair (once)

1. On the Mac: menu-bar icon → **Pair phone…** — a QR code appears.
2. On the phone: ClipBridge → **Scan QR code**, allow the camera, and point it at the QR code.
3. The phone shows **Connected to ‹your Mac›** and the Mac menu shows **Connected to ‹your phone›**. Done.

### 4. Turn on automatic copy (recommended)

This lets ClipBridge notice when you tap **Copy** in other apps, so phone → Mac works by itself.

1. In ClipBridge, tap **Open Accessibility settings** (or Settings → **Accessibility → Installed apps**).
2. Turn on **ClipBridge automatic copy** and confirm.
3. **Greyed out?** Android 13+ restricts this for apps installed outside the Play Store. Go to
   **Settings → Apps → ClipBridge → ⋮ (top-right) → Allow restricted settings**, then try again.

ClipBridge only reads the short text of "Copy" buttons and "Copied" messages to know when you copied —
never screen content — and sends only to your paired Mac.

### 5. Optional

- **Quick Settings tile:** swipe down twice → ✏️ → drag **Send to Mac** into your tiles.
- **Battery:** in ClipBridge, tap **Let ClipBridge run in the background**, so Android doesn't pause it.
- **Notifications** are optional. With them off, everything still works; use the ClipBridge screen for
  **Save to Downloads**.

## Using it

| You want to… | Do this |
|---|---|
| Send text Mac → phone | Copy on the Mac. Paste on the phone. |
| Send text phone → Mac | Copy on the phone (automatic copy on), or tap the **Send to Mac** tile. Paste on the Mac. |
| Send a file Mac → phone | ⌘C the file in Finder. On the phone, paste it in the app (Gmail, chat…) **or** open ClipBridge → **Save to Downloads** (saved in *Download/ClipBridge*). |
| Send a file phone → Mac | **Share → Send to Mac**. It's in **Downloads** on the Mac; ⌘V pastes it. |
| Send a screenshot Mac → phone | ⌃⇧⌘4 (copies to the clipboard), then paste on the phone or **Save to Downloads**. |
| Send an image phone → Mac | Copy it on the phone (Chrome: long-press → **Copy image**), then ⌘V on the Mac. |
| Pause syncing | Mac menu → **Pause syncing**, or ClipBridge → **Pause syncing** on the phone. |
| Pair a new phone / revoke the old one | Mac menu → **Reset pairing…**, then scan the new code. |

Note: Samsung **My Files** can't paste files from other apps ("Destination folder is same as source folder").
Use **Save to Downloads** in ClipBridge for that.

## Troubleshooting

**Phone stays on "Looking for your Mac…"**
- Both on the **same Wi-Fi**, not a guest network. Guest and office Wi-Fi often block devices from talking to each
  other (*AP / client isolation*) — use your home Wi-Fi or a phone hotspot.
- Mac: **System Settings → Privacy & Security → Local Network → ClipBridge** must be on.
- Mac firewall: allow incoming connections for ClipBridge (TCP port 52001).
- A **VPN** on either device can hide the local network — pause it or allow LAN traffic.
- Is ClipBridge running on the Mac (icon in the menu bar)?

**"Your Mac doesn't recognise this phone"** — the Mac's pairing was reset. Scan the QR code again
(Mac menu → Pair phone…).

**A copy on the phone didn't reach the Mac** — some apps copy without showing a "Copy" button or "Copied"
message. Use the **Send to Mac** tile right after copying.

**Pasted text has no formatting** — some apps copy plain text only (e.g. the ChatGPT app). Copy from Chrome,
Docs or Notes to keep formatting.

**Banking app won't open** — many banking apps refuse to run while **Developer options / USB debugging** is on
(only needed for building ClipBridge yourself) or while an accessibility service from outside the Play Store is on.
Turn off Developer options first; if needed, turn off *ClipBridge automatic copy* (use the Send to Mac tile instead).

**"Copied." pop-up on the phone when the Mac sends something** — that's Android's own clipboard confirmation
(shown whenever any app sets the clipboard). Apps can't turn it off.

**Automatic copy stopped working** — Android turns accessibility services off if an app is force-stopped.
Turn **ClipBridge automatic copy** back on in Accessibility settings.

## Limits

- Same Wi-Fi only. Text (with formatting) up to 256 KB per copy; files and images up to 25 MB, one file per Finder copy.
- One phone per Mac.
- The apps aren't in the App Store / Play Store, so first launch needs the steps above.

## Build from source

Requirements: macOS 13+, Xcode Command Line Tools, Homebrew `openjdk@17`, Android SDK (platform 35, build-tools 35).

```bash
# Mac app → mac/build/ClipBridge.app (universal: Apple silicon + Intel)
cd mac && ./build-app.sh

# Android app → android/app/build/outputs/apk/debug/app-debug.apk
cd android && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug
```

Tests:

```bash
cd mac && swift run selftest
cd android && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew testDebugUnitTest
```

Release APKs are signed with a key kept outside the repo (`~/.config/clipbridge-release/keystore.properties`,
or pass `-Pclipbridge.signing=/path/to/keystore.properties`). Without it, `assembleRelease` produces an unsigned APK.

How it works: [`docs/`](docs/) has the design notes and the wire protocol (`docs/test-vector.txt` is the shared
encryption test vector both apps check).

## License

[MIT](LICENSE) © 2026 Balveer Singh Vasir.

Copy-detection approach adapted from [ClipSync](https://github.com/WinShell-Bhanu/Clipsync) (MIT) —
see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). QR scanning by
[zxing-android-embedded](https://github.com/journeyapps/zxing-android-embedded) (Apache 2.0).
