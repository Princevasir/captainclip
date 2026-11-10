# ClipBridge

Share your clipboard between a Mac and an Android phone on the same Wi-Fi.

- **Mac → phone:** automatic. Copy on the Mac, paste on the phone.
- **Phone → Mac:** automatic in most apps (via an accessibility service), with one-tap fallbacks:
  the **Send to Mac** Quick Settings tile, the button on the ClipBridge notification, or **Share → Send to Mac**.
- Direct, encrypted (AES-256-GCM) connection on your local network. No server, no account.
- Text only (v1). Passwords copied from Mac password managers are never sent.

## Build

Requirements: macOS 13+, Xcode Command Line Tools, Homebrew `openjdk@17`, Android SDK (platform 35).

```bash
# Mac app → mac/build/ClipBridge.app
cd mac && ./build-app.sh

# Android app → android/app/build/outputs/apk/debug/app-debug.apk
cd android && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug
```

## Set up

1. **Mac:** open `mac/build/ClipBridge.app` (drag it to /Applications to keep it).
   A clipboard icon appears in the menu bar. Allow "find devices on your local network" if asked.
2. **Phone:** install the APK (USB debugging: `adb install -r app-debug.apk`, or send the APK to the
   phone and open it — allow "install unknown apps" for that one app).
3. Open **ClipBridge** on the phone and follow the steps on screen:
   1. **Scan Mac QR code** — on the Mac click the menu-bar icon → **Pair phone…**.
   2. **Allow notifications** (keeps the connection alive in the background).
   3. **Turn on automatic copy** — Settings → Accessibility → *ClipBridge automatic copy*.
      On Android 13+ the switch is greyed out for sideloaded apps: go to
      *Settings → Apps → ClipBridge → ⋮ → Allow restricted settings*, then try again.
4. Optional but recommended: **Battery → don't restrict ClipBridge**, and add the
   **Send to Mac** tile to Quick Settings.

The Mac menu shows *Connected: <your phone>* when it works.

## Tests

```bash
cd mac && swift run selftest                                    # Mac: 93 checks
cd android && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew testDebugUnitTest   # Android unit tests
```

Both sides check the same AES-GCM test vector (`docs/test-vector.txt`).
`mac/.build/debug/probe "<pairing link>" [send "<text>"]` acts like a phone for testing the Mac app.

## Troubleshooting (phone stays on "Looking for your Mac…")

These are the usual culprits for any local-network sharing app (LocalSend's docs list the same ones):

- **Same network?** Both on the same Wi-Fi — not a guest network. Guest/office Wi-Fi often has
  *AP / client isolation*, which blocks device-to-device traffic. Turn it off in the router, or use a hotspot.
- **macOS Local Network permission:** System Settings → Privacy & Security → Local Network → ClipBridge on.
- **Mac firewall:** allow incoming connections for ClipBridge (TCP port 52001).
- **VPN** on either device can hide the local network — allow LAN traffic or pause it.
- Phone shows **"Mac rejected this phone"** → the Mac's pairing was reset; scan the QR code again.

## Known limits

- Same Wi-Fi only.
- Text up to 256 KB per copy; bigger copies are skipped. For files and images, the free
  open-source [LocalSend](https://localsend.org) app pairs well with ClipBridge.
- Automatic phone → Mac only sees copies that show a "Copy" button or a "Copied" message.
  For anything else use the tile or notification button.
- Passwords are never sent: Mac password-manager copies and Android clips marked sensitive are skipped.
- One phone per Mac. **Reset pairing** in the Mac menu creates a new key (the old phone stops working).

## Credits

Copy-detection approach adapted from [ClipSync](https://github.com/WinShell-Bhanu/Clipsync) (MIT) —
see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
