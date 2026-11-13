# CaptainClip privacy policy

_Effective 13 November 2026 · applies to CaptainClip and CaptainClip Pro for Android, and CaptainClip for Mac._

**Short version: CaptainClip has no servers, no accounts, no analytics and no ads. What you copy goes only
from your phone to your own paired Mac (and back), encrypted, and nowhere else. The developer never receives
any of your data.**

## What CaptainClip handles

| Data | When | Where it goes |
|---|---|---|
| Text you copy (and its formatting) | When you copy on the Mac, or when you send it from the phone (Send to Mac toggle, Share, the in-app button — or automatically in CaptainClip Pro) | Only to your paired device |
| Images and files | Only when you paste, share or save them | Only to your paired device |
| Your devices' names (e.g. "Pixel 9", "MacBook Air") | While connected | Only to your paired device, to show "Connected to …" |
| Clipboard history (phone) | What you sent from the phone, and Mac files you opened on it (Mac text only if you turn that on, or if you turn off putting Mac copies on the phone's clipboard): text, and the names and sizes of files (never file contents) | Kept **only on your phone**, encrypted (see below) |

- Everything travels **directly between your two devices** over your local Wi-Fi or Bluetooth, encrypted
  end-to-end with AES-256-GCM. The encryption key is created on your Mac and reaches your phone only through
  the pairing QR code; it never leaves your two devices.
- Nothing is uploaded to the internet, to the developer, or to any third party.
- Copies marked as sensitive (for example passwords from a password manager) are never sent.
- **One-time codes** (a copy that is just 4–8 digits, optionally split once by a space or dash) still go to your
  paired device. On the receiving device they are hidden (Android marks the clip sensitive; the Mac marks it
  concealed so clipboard managers skip it), are never kept in Clipboard history, and are cleared after 1 minute.
  On the Mac that happens unless something newer was copied; on the phone, Android doesn't let CaptainClip check
  the clipboard, so something else you copy there within that minute and don't send may be cleared too. The device where you copied the code is not changed. Older Mac apps keep
  working but don't hide or clear codes.
- **Tracking removed from links:** when a copy is a single web link, tracking tags (`utm_*`, `fbclid`, `gclid`
  and similar) are removed on the phone, when sending and when receiving. Formatted (HTML) copies are left alone.
  You can turn this off in **This phone → Privacy → Remove tracking from links** (on by default).
- **Mac copies on the phone's clipboard:** by default text copied on the Mac is placed on the phone's clipboard.
  Turn off **This phone → Privacy → Put Mac copies on this phone's clipboard** and text from your Mac waits in
  Clipboard history on the phone instead (if history is on); pictures and files still arrive as before; one-time
  codes aren't kept.
- Files from your Mac are kept on the phone only temporarily (at most 10 minutes, or until your next copy),
  unless you save or share them yourself. Files you send to the Mac are saved in its Downloads folder.
- If you send something to your Mac while it isn't connected or syncing is paused, it waits on this phone only: the
  latest copied text or picture, plus up to 20 shared files (200 MB). It is sent only to your paired Mac, when it's back.
  It's deleted after 1 hour, when you tap **Cancel**, or when you pair a different Mac. Text waits in the app's memory;
  pictures and files wait in the app's private storage, which other apps can't read.

## Clipboard history (Android)

- The phone keeps a short history of what you send from the phone to your Mac, and of Mac files you open on the
  phone, so you can find and reuse them.
- Text copied on your Mac is **not** kept by default, because the phone can't tell whether you ever used it there.
  You can include it with **Clipboard history → ⋮ → Settings → Include clips from your Mac**; turning that off
  again deletes those entries.
- It's stored only on your phone, encrypted with AES-256-GCM using a key held in Android's secure keystore.
  It is never uploaded, never sent anywhere else and not included in backups.
- Only the last 25, 50 or 100 clips (your choice; 50 by default) are kept, and nothing older than 1, 7 or
  30 days (7 by default).
- Sensitive copies (for example passwords) are never stored. File contents are never stored, only names and sizes.
- You can delete single clips, clear everything, or turn history off (which deletes it) in **Clipboard history → ⋮**.

## Connection check (Android)

- The connection report you can copy from **Check connection** contains app and Android versions, check results and
  connect/disconnect times. It never contains clipboard text, file names or network addresses, and nothing is sent
  automatically; you choose where to paste it.
- The phone remembers the Mac's last network address **on the phone only**, so it can reconnect quickly. It is never sent anywhere else.

## What CaptainClip does not do

- No account, sign-in, advertising ID, analytics, crash reporting or tracking of any kind.
- No access to your contacts, messages, call logs, photos library or location.
- It does not read your screen. (CaptainClip Pro's "automatic copy" uses an Android accessibility service only to
  notice the text of "Copy" buttons and "Copied" messages, so it knows when you copied something. It is off
  until you turn it on, and the standard CaptainClip doesn't include it at all.)

## Permissions (Android)

| Permission | Why |
|---|---|
| Internet, network state, Wi-Fi multicast | Find and connect to your Mac on your local network |
| Nearby devices (Bluetooth) — optional | Connect to your Mac over Bluetooth when there's no shared Wi-Fi. Never used for location |
| Camera | Only to scan the pairing QR code; no image is stored or sent |
| Notifications, foreground service, start at boot | Keep the connection to your Mac running in the background |
| Accessibility service — CaptainClip Pro only, off until you enable it | Notice when you tap "Copy" (see above) |
| Location — CaptainClip Pro only, Android 10–11 only | Android requires it for Bluetooth scanning on those versions; your location is never read |

## Your control

- Pause syncing at any time (in the app on either device), or unpair with **Reset pairing** on the Mac.
- Clear or turn off the clipboard history at any time on the phone (Clipboard history → ⋮).
- Uninstalling the app removes everything it stored on that device, including the history and its key.
  There is nothing stored elsewhere to delete.

## Children

CaptainClip is a general-purpose utility and is not directed at children.

## Changes and contact

If this policy changes, the new version will be published here with a new date.
Questions: open an issue at <https://github.com/Princevasir/captainclip/issues>.
