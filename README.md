# ClipBridge

**Copy on your Mac, paste on your Android phone — and back.** Text and small files (PDFs, docs, images)
move between a Mac and an Android phone on the same Wi-Fi — or over Bluetooth when there's no shared Wi-Fi —
directly and end-to-end encrypted. No account, no cloud, no server.

| | |
|---|---|
| 🖥 **Mac app** | [**Download ClipBridge for Mac**](https://github.com/Princevasir/clipbridge/releases/latest/download/ClipBridge-mac.zip) · macOS 13 Ventura or later · Apple silicon and Intel |
| 📱 **Android app** | [**Download ClipBridge for Android**](https://github.com/Princevasir/clipbridge/releases/latest/download/ClipBridge-android.apk) · Android 10 or later · installs normally |
| 📱 **Android app — Pro** | [**Download ClipBridge Pro**](https://github.com/Princevasir/clipbridge/releases/latest/download/ClipBridge-android-pro.apk) · adds automatic copy (see below) |

**ClipBridge or ClipBridge Pro?** Both do everything Mac → phone automatically. **ClipBridge** sends from the
phone when you tap **Send to Mac** (the quick toggle next to Torch, Share, or the button in the app); it asks for
no location and no accessibility access, so it installs without warnings (Bluetooth fallback: Android 12+).
**ClipBridge Pro** also notices when you copy on the phone and sends it by itself — that needs an Android
*accessibility service*, which Google Play Protect blocks for apps installed from a browser in some countries
(see the install steps). Either one installs over the other and keeps your pairing.

All versions and checksums: [Releases](https://github.com/Princevasir/clipbridge/releases).

---

## What it does

- **Text, Mac → phone:** copy on the Mac, paste on the phone. Automatic.
- **Text, phone → Mac:** copy in most Android apps and it's on the Mac clipboard. Where an app copies
  silently, use the **Send to Mac** toggle in your notification panel, **Share → Send to Mac**, or the big
  **Send clipboard to Mac** button on ClipBridge's main screen.
- **Files, Mac → phone (up to 25 MB each, up to 20 at once):** ⌘C one or several files in Finder. The phone only
  learns their names and sizes; the files themselves move **only when you use them**: open ClipBridge and tap
  **Share…** (attaches them in Gmail, WhatsApp, Drive…) or **Save to Downloads**. Images can also simply be pasted.
  Offers expire after 10 minutes or at your next copy.
- **Bluetooth fallback:** when the phone can't reach the Mac over Wi-Fi (café, office guest Wi-Fi, no Wi-Fi), it
  connects over Bluetooth automatically, with the same pairing and encryption, and moves back to Wi-Fi when it can.
  Text, formatting, screenshots and files up to 5 MB work over Bluetooth.
- **Formatted text, both ways:** bold, italics, links and lists survive when the app you copy from includes
  formatting (Chrome, Safari, Docs, Notes, Mail…). Pastes take on the destination's own font and colours.
- **Images and screenshots:** a ⌃⇧⌘4 screenshot or "Copy Image" on the Mac is pasted on the phone like a file
  (fetched only when you paste). An image copied on the phone (Chrome "Copy image", screenshot "Copy") goes
  straight onto the Mac clipboard — ⌘V in Notes, Messages, Slack.
- **Files, phone → Mac:** **Share → Send to Mac** from any app. Files land in your Mac's **Downloads** and on its
  clipboard, ready for ⌘V in Finder or Mail.
- **Clipboard history (phone):** **Recent clips** on the main screen and a searchable **Clipboard history** of
  what crossed between your devices — grouped by day; tap to copy again, long-press to send to the Mac again.
  Kept only on the phone, encrypted; 25/50/100 clips for 1/7/30 days, or off.
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
5. When macOS asks, allow ClipBridge to **find devices on your local network**, to **use Bluetooth** (for the
   fallback), to **use its key in your Keychain** (click **Always Allow**), and to send **notifications** (optional).
6. Optional: menu-bar icon → **Start ClipBridge at login**.

### 2. Android phone

1. On the phone, open **[ClipBridge-android.apk](https://github.com/Princevasir/clipbridge/releases/latest/download/ClipBridge-android.apk)**
   — or **[ClipBridge-android-pro.apk](https://github.com/Princevasir/clipbridge/releases/latest/download/ClipBridge-android-pro.apk)**
   for Pro (download it in Chrome, or send it from the Mac with Quick Share / AirDrop).
2. Tap the downloaded file to install. If Android asks, allow **Install unknown apps** for Chrome or My Files.
   - **Samsung phones:** if installing is blocked, turn off **Settings → Security and privacy → Auto Blocker**
     while you install (you can turn it back on afterwards).
   - **Google Play Protect** may say the developer is unknown → **More details → Install anyway**.
   - **ClipBridge Pro: "This app can request access to sensitive data" with only an OK button** — Play Protect
     blocks apps that use an *accessibility service* (Pro's automatic copy, which only reads the text of
     "Copy"/"Copied" labels) when they're installed from a browser, chat app or file manager. In some countries (e.g. India,
     Singapore, Thailand, Brazil) there's no "Install anyway". Install it from your Mac over USB instead — this
     block doesn't apply there, and later versions from this page install over it normally:
     1. On the phone: **Settings → About phone → Software information**, tap **Build number** 7 times, then
        **Settings → Developer options → USB debugging** on. Connect the phone to the Mac and allow the prompt.
     2. On the Mac, in Terminal (needs [Homebrew](https://brew.sh)):
        `brew install android-platform-tools`, then `adb install ~/Downloads/ClipBridge-android-pro.apk`
     3. Turn **Developer options** off again afterwards (banking apps often refuse to run while it's on).

     Or use the standard **ClipBridge**, which installs normally. Please don't turn Play Protect off to get
     around it — it protects you from other apps too.
3. Open **ClipBridge**.

### 3. Pair (once)

1. On the Mac: menu-bar icon → **Pair phone…** — a QR code appears.
2. On the phone: ClipBridge → **Scan QR code**, allow the camera, and point it at the QR code.
3. The phone shows **Connected to ‹your Mac›** and the Mac menu shows **Connected to ‹your phone›**. Done.

### 4. Turn on automatic copy (ClipBridge Pro only — with ClipBridge, use the Send to Mac toggle)

This lets ClipBridge notice when you tap **Copy** in other apps, so phone → Mac works by itself.

1. In ClipBridge, tap **Open Accessibility settings** (or Settings → **Accessibility → Installed apps**).
2. Turn on **ClipBridge automatic copy** and confirm.
3. **Greyed out?** Android 13+ restricts this for apps installed outside the Play Store. Go to
   **Settings → Apps → ClipBridge → ⋮ (top-right) → Allow restricted settings**, then try again.

ClipBridge only reads the short text of "Copy" buttons and "Copied" messages to know when you copied —
never screen content — and sends only to your paired Mac.

### 5. Optional

- **Bluetooth fallback:** in ClipBridge, tap **Allow Bluetooth** on the setup card and allow **Nearby devices**.
  Without it, ClipBridge simply stays Wi-Fi-only.
- **"Send to Mac" quick toggle** (next to Torch, Auto rotate…): in ClipBridge tap the **Add to Quick Settings**
  tile — a short guide with **Add it now** (Android 13+; tap **Add**). Samsung places new toggles at the end of
  the panel: tap ✏️ and drag **Send to Mac** up into the grid. On Android 10–12 the guide shows how to add it by
  hand. The toggle lights up with **Ready** while your Mac is connected; tap it after copying to send your
  clipboard. Once it's in place, the tile says **In Quick Settings ✓**.
- **Battery:** in ClipBridge → Settings, tap **Run in the background**, so Android doesn't pause it.
- **Notifications** are optional. With them off, everything still works; use the ClipBridge screen for
  **Save to Downloads**.

## Using it

| You want to… | Do this |
|---|---|
| Send text Mac → phone | Copy on the Mac. Paste on the phone. |
| Send text phone → Mac | Copy on the phone, then tap **Send to Mac** in your notification panel (or **Send clipboard to Mac** in the app; ClipBridge Pro sends by itself). Paste on the Mac. |
| Find something you sent earlier | ClipBridge → **Recent clips**, or **Clipboard history** to search. Tap to copy; long-press → **Send to Mac**. |
| Send files Mac → phone | ⌘C one or several files in Finder. On the phone open ClipBridge → **Share…** and pick Gmail, WhatsApp, Drive… **or** **Save to Downloads** (saved in *Download/ClipBridge*). |
| Send a file phone → Mac | **Share → Send to Mac**. It's in **Downloads** on the Mac; ⌘V pastes it. |
| Send a screenshot Mac → phone | ⌃⇧⌘4 (copies to the clipboard), then paste on the phone or **Save to Downloads**. |
| Send an image phone → Mac | Copy it on the phone (Chrome: long-press → **Copy image**), then ⌘V on the Mac. |
| Pause syncing | Mac menu → **Pause syncing**, or the **Pause syncing** tile in ClipBridge on the phone. |
| Clear or turn off history | ClipBridge → **Clipboard history** → ⋮ → **Clear history** / **Settings**. |
| Pair a new phone / revoke the old one | Mac menu → **Reset pairing…**, then scan the new code. |

Note: most Android apps (Gmail included) accept **pasted images** but ignore other pasted files such as PDFs —
use **Share…** in ClipBridge for those. Samsung **My Files** can't paste files from other apps ("Destination folder
is same as source folder"); use **Save to Downloads**.

## Troubleshooting

**Phone stays on "Looking for your Mac…"**
- Both on the **same Wi-Fi**, not a guest network. Guest and office Wi-Fi often block devices from talking to each
  other (*AP / client isolation*) — use your home Wi-Fi or a phone hotspot.
- Mac: **System Settings → Privacy & Security → Local Network → ClipBridge** must be on.
- Mac firewall: allow incoming connections for ClipBridge (TCP port 52001).
- A **VPN** on either device can hide the local network — pause it or allow LAN traffic.
- Is ClipBridge running on the Mac (icon in the menu bar)?

**Bluetooth fallback doesn't connect**
- Phone: ClipBridge needs **Nearby devices** (Settings → Apps → ClipBridge → Permissions) and Bluetooth on.
- Mac: **System Settings → Privacy & Security → Bluetooth → ClipBridge** must be on; the menu shows
  **Bluetooth fallback: ready** when it's working.
- Keep the phone within a few metres of the Mac. After the phone fails to find the Mac over Bluetooth, it waits
  1–10 minutes before looking again (to save battery); turning Wi-Fi off and on makes it look right away.

**"Too big for Bluetooth"** — files over 5 MB need both devices on the same Wi-Fi.

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

- Same Wi-Fi, or Bluetooth within a few metres. Text (with formatting) up to 256 KB per copy; files and images up
  to 25 MB each, 2–20 files and 100 MB per Finder copy (folders aren't sent). Over Bluetooth, files up to 5 MB.
- One phone per Mac.
- The apps aren't in the App Store / Play Store, so first launch needs the steps above.

## Build from source

Requirements: macOS 13+, Xcode Command Line Tools, Homebrew `openjdk@17`, Android SDK (platform 35, build-tools 35).

```bash
# Mac app → mac/build/ClipBridge.app (universal: Apple silicon + Intel)
cd mac && ./build-app.sh

# Android app → android/app/build/outputs/apk/{standard,pro}/debug/app-{standard,pro}-debug.apk
cd android && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleStandardDebug assembleProDebug
```

Tests:

```bash
cd mac && swift run selftest
cd android && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew testStandardDebugUnitTest testProDebugUnitTest
```

Release APKs are signed with a key kept outside the repo (`~/.config/clipbridge-release/keystore.properties`,
or pass `-Pclipbridge.signing=/path/to/keystore.properties`). Without it, `assembleStandardRelease` / `assembleProRelease` produce unsigned APKs. Google Play: `bundleStandardRelease`.

## License

[MIT](LICENSE) © 2026 Balveer Singh Vasir.

Copy-detection approach adapted from [ClipSync](https://github.com/WinShell-Bhanu/Clipsync) (MIT) —
see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). QR scanning by
[zxing-android-embedded](https://github.com/journeyapps/zxing-android-embedded) (Apache 2.0).
