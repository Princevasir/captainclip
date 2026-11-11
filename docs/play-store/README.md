# Google Play submission pack — ClipBridge (standard)

Everything here is for the **standard ClipBridge** (package `app.clipbridge`, no accessibility service, no
location). ClipBridge Pro stays on GitHub only: Google Play allows accessibility services only for apps that
help people with disabilities, so Pro would very likely be rejected.

## What's ready

| Item | Where |
|---|---|
| App Bundle (upload this) | `cd android && ./gradlew bundleStandardRelease` → `android/app/build/outputs/bundle/standardRelease/app-standard-release.aab` (signed with your release key = your Play **upload key**) |
| Privacy policy URL | `https://github.com/Princevasir/clipbridge/blob/main/PRIVACY.md` (live once pushed) |
| App icon 512 × 512 | `docs/play-store/icon-512.png` |
| Feature graphic 1024 × 500 | `docs/play-store/feature-graphic.png` |
| Phone screenshots — upload these 5 (1440 × 2880) | `docs/play-store/store-screenshots/store-1…5-*.png` — headline + phone. Regenerate: `cd mac && swift run iconmaker --store ../docs/play-store/screenshots ../docs/play-store/store-screenshots` |
| Raw app screenshots (used inside the panels) | `docs/play-store/screenshots/` |
| Store text, Data safety, declarations | below |

## Your steps in Play Console (only you can do these)

1. **Create the developer account** at <https://play.google.com/console> — one-time fee (about US $25), identity
   verification with an ID document. A personal account is fine.
2. **Create app** → name `ClipBridge: Mac Clipboard`, default language English, *App*, *Free*.
3. **Set up your app** (dashboard checklist) — answers below: privacy policy, app access, ads, content rating,
   target audience, data safety, government apps (no), financial features (none), health (no).
4. **Store listing** — paste the text below, upload icon, feature graphic, screenshots. Category: *Productivity*.
5. **Closed testing** (required for new personal accounts): create a closed track, upload the `.aab`, add
   **at least 12 testers** (their Google account emails) and keep it running **14 days** with them opted in.
6. **Apply for production** from the dashboard after the 14 days, answer Google's short questionnaire about the
   test, then roll out. Review usually takes a few days.

> Play App Signing: on first upload, accept *Google-generated app signing key*. Your release key becomes the
> upload key. Consequence: the Play version can't update a ClipBridge installed from GitHub (different
> signature) — users switching uninstall once and pair again.

> Target API: Play requires a recent `targetSdk` (new apps: within about a year of the newest Android). The
> app targets API 35. If Play Console says a higher level is required, the build needs the newer Android SDK
> platform installed — ask and it's a small change.

---

## Store listing

**App name (≤ 30):** `ClipBridge: Mac Clipboard`

**Short description (≤ 80):**
`Copy on your Mac, paste on Android — and back. Private, local and encrypted.`

**Full description:**

```
ClipBridge links your Android phone and your Mac so copy and paste just works between them — like Apple's Universal Clipboard, for Android.

• Copy text on your Mac, paste it on your phone. Formatting (bold, links, lists) comes along where apps support it.
• Copy on your phone and tap “Send to Mac” — from the quick-settings toggle next to Torch, from Share, or in the app.
• Screenshots and images: take a screenshot on the Mac and paste it on the phone.
• Files: copy up to 20 files in Finder, then share them to Gmail, WhatsApp or Drive, or save them to Downloads — nothing is downloaded until you do.
• Phone to Mac files: Share → Send to Mac from any app; they land in the Mac's Downloads.
• Works on the same Wi-Fi, and over Bluetooth when there's no shared Wi-Fi (Android 12+).

Private by design
• No account, no cloud, no ads, no tracking.
• Your two devices talk directly; everything is end-to-end encrypted (AES-256-GCM) with a key that only travels in the pairing QR code.
• Passwords copied from password managers are never sent.

Getting started
1. Install ClipBridge for Mac (free, open source): github.com/Princevasir/clipbridge
2. Open it from the menu bar → Pair phone… and scan the QR code with this app.

ClipBridge is open source (MIT). Requires macOS 13 or later on the Mac.
```

---

## App content answers

**Privacy policy:** `https://github.com/Princevasir/clipbridge/blob/main/PRIVACY.md`

**App access:** *All or some functionality is restricted* → add instructions:
> ClipBridge works together with ClipBridge for Mac (free, open source). To test: download
> ClipBridge-mac.zip from https://github.com/Princevasir/clipbridge/releases/latest, open it on a Mac (macOS 13+),
> click the menu-bar icon → Pair phone…, and scan the QR code in the app. Without a Mac the app shows the
> pairing screen. No login or credentials are needed.

**Ads:** No.

**Content rating questionnaire:** category *Utility, Productivity, Communication or Other*. Answer **No** to
violence, sexual content, language, controlled substances, gambling. "Users can interact or exchange content?" →
**No** (content only moves between the user's own devices; no other users). Expected rating: Everyone / 3+.

**Target audience:** 18 and over (avoids the Families policy; it's a general utility, not for children).

**News app:** No. **Government app:** No. **Financial features:** None. **Health:** No.

### Data safety

Basis: nothing is sent to the developer or any server. Google's definition: data is *collected* only if it's
transmitted off the device **to you or a third party**; data sent end-to-end encrypted between the user's own
devices, unreadable by the developer, isn't collection. (Re-read the hints next to each Play Console question
when you fill it in — wording changes.)

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Is all of the user data collected by your app encrypted in transit? | (not asked when "No") — if asked: **Yes** |
| Do you provide a way for users to request that their data is deleted? | (not asked when "No") |

Resulting label: *No data collected · No data shared with third parties*.

### Permission and feature declarations

- **Foreground service (connectedDevice)** — Play Console → App content → *Foreground service permissions*:
  > Type: Connected device. ClipBridge keeps an encrypted connection to the user's paired Mac (over local Wi-Fi
  > or Bluetooth) so clipboard text, images and files the user copies can be exchanged immediately. The
  > connection runs only while sync is on; the user can pause it in the app at any time.
  Play asks for a **short video** link (e.g. an unlisted YouTube video, ~30 s): show pairing, copying on the Mac,
  pasting on the phone, and the ongoing "ClipBridge" notification.
- **Bluetooth scan** is declared `neverForLocation` — no location declaration needed.
- **Camera** — used only for the QR scanner; no declaration form, covered by the privacy policy.

---

## Before each Play update

- Bump `versionCode` in `android/app/build.gradle.kts` (Play rejects a repeated code).
- `./gradlew testStandardDebugUnitTest lintStandardRelease bundleStandardRelease`, upload the new `.aab`.
