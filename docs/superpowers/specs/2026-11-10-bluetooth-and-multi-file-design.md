# ClipBridge — Bluetooth fallback and several files at once (design)

## Why
ClipBridge only works when the Mac and phone share a Wi-Fi network that lets devices see each other. Away from
home (café, office guest Wi-Fi, no Wi-Fi) it stops. Apple's Universal Clipboard keeps working there. And Finder
copies of several files are ignored today.

## Goals
1. **Bluetooth fallback:** when the phone can't reach the Mac over Wi-Fi, it connects over **Bluetooth Low Energy
   (BLE)** automatically and everything keeps working — text, formatting, images and small files — with the same
   pairing, challenge handshake and encryption. When Wi-Fi works again, it switches back.
2. **Several files at once:** ⌘C on 2–20 files in Finder offers them all; on the phone they paste together into apps
   that accept several files (Gmail, WhatsApp, Drive…) or are saved together with *Save to Downloads*.

## Non-goals
Folders (still ignored), Bluetooth Classic / OS-level Bluetooth pairing, sharing a hotspot automatically,
more than one phone, iPhone support.

## Anti-goals
- Draining the phone battery: no continuous Bluetooth scanning while Wi-Fi works.
- A second security model: Bluetooth carries the exact same encrypted, authenticated session.
- Pastes hanging for minutes because a large file is crawling over Bluetooth.

## Approaches considered
1. **BLE GATT, Mac as peripheral, phone as central, carrying our existing byte stream (chosen).** No OS-level
   pairing (our QR key already authenticates), works with CoreBluetooth on macOS and standard Android APIs.
   Slow (≈20–100 KB/s real world), so files are capped on Bluetooth.
2. Bluetooth Classic RFCOMM — faster (~100–200 KB/s) but needs OS pairing in Settings on both devices and the
   macOS side uses the legacy IOBluetooth API. More setup, more fragile.
3. Wi-Fi Direct / phone hotspot — fast, but Macs don't do Wi-Fi Direct and hotspot switching changes the user's
   network. Out of scope.

## How it works

### One session, two transports
The encrypted session (challenge → hello → messages, frames ≤ 1 MiB) is unchanged. It now runs over a
**transport** that is either TCP (today) or BLE. Both apps get a small transport abstraction; the session code
doesn't know which one it's on.

### BLE link (both sides)
- Mac advertises one **ClipBridge GATT service** (fixed 128-bit UUID) with two characteristics:
  `toMac` (phone writes, *write without response*) and `toPhone` (Mac sends *notifications*).
- The advertisement carries a short **pairing tag** = first 4 bytes of SHA-256(macId) so the phone only connects
  to *its* Mac (not a secret; authentication is still the encrypted handshake).
- The byte stream is cut into packets of (negotiated MTU − 3) bytes; BLE keeps them in order. Flow control:
  Android waits for its write callback / buffer-ready before the next packet; Mac waits for
  `peripheralManagerIsReady(toUpdateSubscribers:)`.
- Phone requests MTU 517 and the 2M PHY where supported.

### When Bluetooth is used (phone decides)
- Normal: Wi-Fi as today.
- If the Wi-Fi attempt fails **2 times in a row** (or the phone has no Wi-Fi), the sync loop tries Bluetooth:
  scan (low-latency mode, max 10 s, filtered by service UUID + pairing tag) → connect → same session.
- While on Bluetooth it retries Wi-Fi every 30 s in the background; on success it moves to Wi-Fi and drops BLE.
- No scanning while connected over Wi-Fi. Scanning stops after 10 s and backs off like reconnects (battery).
- Status shows the path: "Connected via Wi-Fi" / "Connected via Bluetooth".

### What travels over Bluetooth
- Text + formatting: yes (≤ 256 KB as today).
- Images and files: yes **up to 5 MB per file** on Bluetooth. Bigger offers still appear, but pasting/saving them
  over Bluetooth fails quickly with "Too big for Bluetooth — connect both to the same Wi-Fi".
- Paste timeouts scale with size on Bluetooth (first data within 6 s; total ≤ 10 s + size ÷ 30 KB/s).

### Permissions
- Android 12+: *Nearby devices* (BLUETOOTH_SCAN with `neverForLocation`, BLUETOOTH_CONNECT). Asked from a new,
  optional setup step "Allow Bluetooth fallback". Without it ClipBridge just stays Wi-Fi-only.
- Mac: Bluetooth permission prompt on first launch (`NSBluetoothAlwaysUsageDescription`). Menu shows
  "Bluetooth fallback: on/off (permission)".

### Several files
- Mac: Finder ⌘C of **2–20 regular files, each ≤ 25 MB, total ≤ 100 MB** → one **offer set**. Copies with a folder,
  >20 files or too large total stay ignored (and send nothing).
- Protocol: new message `{"t":"offers","items":[{id,name,mime,size}…],"ts":N}` (old phones ignore it). Each item is
  fetched with the existing `fetch` by its id; the offer-set lifetime/changed rules apply per item.
  A single-file copy still sends the existing `offer`.
- Phone: clipboard placeholder = **one ClipData with one item per file** (each a content URI). Apps that accept
  several files take all of them; *Save to Downloads* saves all. The ClipBridge card shows "3 files · 4.2 MB" with
  the names.
- Phone → Mac several files: already supported via Share.

## Constraints
- All existing behaviour and tests stay green; Wi-Fi remains the default path.
- Mac: macOS 13+, Command Line Tools only (CoreBluetooth available). Android: minSdk 29 / target 35.
- The BLE radio code can't be unit-tested on the JVM / selftest: it's kept thin; everything above it (packetising,
  flow control, transport selection, session over packets, offer sets) is unit-tested with in-memory transports.
  The radio path is verified on the user's devices.
- Everything local; no pushing without an explicit request.

## Left to the builder
Exact UUIDs, scan/back-off constants within the stated bounds, notification wording, how the card lists names.
