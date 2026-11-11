# ClipBridge privacy policy

_Effective 11 November 2026 · applies to ClipBridge and ClipBridge Pro for Android, and ClipBridge for Mac._

**Short version: ClipBridge has no servers, no accounts, no analytics and no ads. What you copy goes only
from your phone to your own paired Mac (and back), encrypted, and nowhere else. The developer never receives
any of your data.**

## What ClipBridge handles

| Data | When | Where it goes |
|---|---|---|
| Text you copy (and its formatting) | When you copy on the Mac, or when you send it from the phone (Send to Mac toggle, Share, the in-app button — or automatically in ClipBridge Pro) | Only to your paired device |
| Images and files | Only when you paste, share or save them | Only to your paired device |
| Your devices' names (e.g. "Pixel 9", "MacBook Air") | While connected | Only to your paired device, to show "Connected to …" |

- Everything travels **directly between your two devices** over your local Wi-Fi or Bluetooth, encrypted
  end-to-end with AES-256-GCM. The encryption key is created on your Mac and reaches your phone only through
  the pairing QR code; it never leaves your two devices.
- Nothing is uploaded to the internet, to the developer, or to any third party.
- Copies marked as sensitive (for example passwords from a password manager) are never sent.
- Files from your Mac are kept on the phone only temporarily (at most 10 minutes, or until your next copy),
  unless you save or share them yourself. Files you send to the Mac are saved in its Downloads folder.

## What ClipBridge does not do

- No account, sign-in, advertising ID, analytics, crash reporting or tracking of any kind.
- No access to your contacts, messages, call logs, photos library or location.
- It does not read your screen. (ClipBridge Pro's "automatic copy" uses an Android accessibility service only to
  notice the text of "Copy" buttons and "Copied" messages, so it knows when you copied something. It is off
  until you turn it on, and the standard ClipBridge doesn't include it at all.)

## Permissions (Android)

| Permission | Why |
|---|---|
| Internet, network state, Wi-Fi multicast | Find and connect to your Mac on your local network |
| Nearby devices (Bluetooth) — optional | Connect to your Mac over Bluetooth when there's no shared Wi-Fi. Never used for location |
| Camera | Only to scan the pairing QR code; no image is stored or sent |
| Notifications, foreground service, start at boot | Keep the connection to your Mac running in the background |
| Accessibility service — ClipBridge Pro only, off until you enable it | Notice when you tap "Copy" (see above) |
| Location — ClipBridge Pro only, Android 10–11 only | Android requires it for Bluetooth scanning on those versions; your location is never read |

## Your control

- Pause syncing at any time (in the app on either device), or unpair with **Reset pairing** on the Mac.
- Uninstalling the app removes everything it stored on that device. There is nothing stored elsewhere to delete.

## Children

ClipBridge is a general-purpose utility and is not directed at children.

## Changes and contact

If this policy changes, the new version will be published here with a new date.
Questions: open an issue at <https://github.com/Princevasir/clipbridge/issues>.
