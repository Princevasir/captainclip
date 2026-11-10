# ClipBridge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Text clipboard sharing between a Mac and an Android phone on the same Wi-Fi: automatic Mac → Android, automatic-where-detectable Android → Mac with one-tap fallbacks.

**Architecture:** Mac menu-bar app is the server: listens on TCP, advertises via Bonjour, watches NSPasteboard. Android app runs a foreground service that finds the Mac (NSD, fallback to IP from QR), keeps one encrypted TCP connection, writes incoming text to the clipboard, and sends local copies detected by an Accessibility service (pattern adapted from ClipSync, MIT) via an invisible "ghost" activity.

**Tech Stack:** Mac: Swift 6 / SwiftPM, AppKit, Network.framework, CryptoKit, CoreImage (QR), Security (Keychain). Android: Kotlin 2.0.21, AGP 8.7.3, Gradle 8.11.1, minSdk 29 / target+compile 35, androidx.core, zxing-android-embedded 4.3.0, JUnit 4.

**Spec:** `docs/2026-11-10-clipbridge-design.md`

## Global Constraints
- Same Wi-Fi only; no server, no account, no internet calls.
- Text only in v1. One paired phone per Mac; re-pairing replaces the key.
- All traffic AES-256-GCM with a 32-byte key that exists only in the QR code + device storage.
- Mac target macOS 13+; build with Command Line Tools only (no Xcode, no XCTest/swift-testing — Mac tests are a `selftest` executable that exits non-zero on failure).
- Android minSdk 29, targetSdk 35.
- Code copied/adapted from ClipSync keeps its MIT notice in `THIRD_PARTY_NOTICES.md`. No code from KDE Connect (GPL) or AirSync.

## Wire protocol (both sides must match exactly)
- Bonjour/NSD service type `_clipbridge._tcp`, instance name `ClipBridge-<first 6 of macId>`.
- QR text: `clipbridge://pair?v=1&id=<macId:16 lowercase hex>&key=<base64url, no padding, 32 bytes>&host=<IPv4>&port=<int>`
- Frame: 4-byte big-endian length `N` (1 ≤ N ≤ 1_048_576), then `nonce(12) ‖ ciphertext ‖ tag(16)`. AAD = UTF-8 `clipbridge-v1`.
- Plaintext = UTF-8 JSON, one of:
  `{"t":"hello","device":"<name>"}` (phone sends first) · `{"t":"clip","id":"<uuid>","text":"…","ts":<epoch ms>}` · `{"t":"ping"}`
- Any frame that fails to decrypt, exceeds the size limit, or is unparseable → close connection.
- Phone sends ping every 25 s; Mac drops a connection silent for 60 s. Phone reconnect backoff 1, 2, 5, 10, 30 s (stay at 30).
- Loop guard: each side ignores a local clipboard change whose text equals the last text it received or sent.

## Review Focus
1. **Password-manager copies on Mac** — items flagged `org.nspasteboard.ConcealedType` or `org.nspasteboard.TransientType` must NOT be sent. (Task 3 test.)
2. **Echo loop** — Mac→phone text written to phone clipboard must not bounce back. (Task 1 `LoopGuard` test, mirrored in Task 5.)
3. **Wrong or stale key** (phone re-paired elsewhere, attacker on Wi-Fi) — connection closed, nothing written to clipboard. (Task 1 + Task 5 tests: open() with wrong key fails.)
4. **Huge copy** (e.g. 5 MB log) — sender skips text whose encoded frame would exceed 1 MiB, no crash. (Task 1 + Task 5 tests.)
5. **Wi-Fi drop / Mac sleep** — phone reconnects by itself when Mac reappears. (Task 9 manual check; backoff unit test in Task 6.)

---

### Task 1: Mac core library (crypto, framing, messages, pairing, loop guard)

**Files:**
- Create: `mac/Package.swift` (targets: `ClipBridgeCore` library, `ClipBridge` executable, `selftest` executable, `probe` executable)
- Create: `mac/Sources/ClipBridgeCore/{ClipCrypto,FrameCodec,Message,PairingInfo,LoopGuard}.swift`
- Test: `mac/Sources/selftest/main.swift`
- Create: `.gitignore`, `git init` at repo root

**Interfaces — Produces:**
- `struct ClipCrypto { init(key: Data) throws; func seal(_ plaintext: Data, nonce: Data? = nil) throws -> Data; func open(_ box: Data) throws -> Data; static func randomKey() -> Data }`
- `enum FrameCodec { static let maxPayload = 1_048_576; static func encode(_ payload: Data) throws -> Data }` and `final class FrameDecoder { func append(_ bytes: Data) throws -> [Data] }` (throws `FrameError.tooLarge`)
- `enum Message: Codable, Equatable { case hello(device: String); case clip(id: String, text: String, ts: Int64); case ping }` encoded with key `t` exactly as the wire protocol.
- `struct PairingInfo { macId: String; key: Data; host: String; port: Int; var uri: String; init?(uri: String) }`
- `final class LoopGuard { func markReceived(_ text: String); func markSent(_ text: String); func shouldSend(_ text: String) -> Bool }`

- [ ] **Step 1: Write selftest** with a tiny `check(_ cond: Bool, _ name: String)` helper; cases:
  - `seal/open roundtrip` for "héllo 👋".
  - `known vector`: key = 32 bytes `0x00…0x1f`, nonce = 12 bytes `0x00…0x0b`, plaintext `{"t":"ping"}` → print hex of `seal` and assert it equals the constant recorded on first run (record it into `docs/test-vector.txt`; Task 5 asserts the same hex on Android).
  - `open with wrong key throws`; `open with flipped byte throws`.
  - `FrameDecoder` yields 2 payloads when fed two frames split at byte 3 and byte 9; throws `tooLarge` for header length 1_048_577.
  - `Message.clip` encodes to JSON containing `"t":"clip"` and decodes back equal.
  - `PairingInfo(uri:)` roundtrips `uri`; returns nil for `v=2` or key ≠ 32 bytes.
  - `LoopGuard`: after `markReceived("a")`, `shouldSend("a") == false`, `shouldSend("b") == true`.
- [ ] **Step 2:** `cd mac && swift run selftest` → fails to compile (types missing).
- [ ] **Step 3:** Implement the five files (CryptoKit `AES.GCM`, AAD `clipbridge-v1`, box = `combined`).
- [ ] **Step 4:** `swift run selftest` → prints `ALL PASS`, exit 0. Record vector hex.
- [ ] **Step 5:** Commit `feat(mac): core crypto, framing, protocol`.

### Task 2: Mac server + probe client

**Files:** Create `mac/Sources/ClipBridge/Server.swift`, `mac/Sources/probe/main.swift`

**Interfaces:**
- Consumes: Task 1 types.
- Produces: `final class Server { init(key: Data, macId: String); var onClip: (String) -> Void; var onStatus: (Status) -> Void; func start() throws; func send(text: String); var port: UInt16? }`, `enum Status { case waiting, connected(device: String) }`
- `probe` CLI: `probe <pairing-uri> [send "<text>"]` — connects to host/port from URI, sends hello, prints every received clip text to stdout, optionally sends one clip.

- [ ] **Step 1:** Implement `Server` with `NWListener` (TCP, any port, `service: .init(name:type:"_clipbridge._tcp")`); one active connection (a new one replaces the old); requires first frame to be `hello`; 60 s idle timeout; replies nothing to ping.
- [ ] **Step 2:** Implement `probe` with `NWConnection`.
- [ ] **Step 3:** Temporary test harness in `selftest`: start `Server` on localhost with a random key, run probe logic in-process: probe sends `clip "x"` → `onClip` gets "x"; `server.send("y")` → probe receives "y"; probe with wrong key → server closes and `onClip` never fires.
- [ ] **Step 4:** `swift run selftest` → ALL PASS.
- [ ] **Step 5:** Commit `feat(mac): TCP/Bonjour server and probe client`.

### Task 3: Mac menu-bar app

**Files:** Create `mac/Sources/ClipBridge/{main,AppDelegate,PasteboardWatcher,KeyStore,PairWindow,Notifier}.swift`, `mac/bundle/Info.plist`, `mac/build-app.sh`

**Interfaces:**
- `final class PasteboardWatcher { init(interval: 0.5); var onText: (String) -> Void; func start(); func write(_ text: String) }` — skips items whose types include `org.nspasteboard.ConcealedType` or `org.nspasteboard.TransientType`; `write` bumps the known changeCount so it does not re-fire.
- `enum KeyStore { static func loadOrNil() -> (macId: String, key: Data)?; static func save(macId:key:); }` — Keychain generic password, service `app.clipbridge.mac`.
- `PairWindow.show(uri:)` — renders QR with `CIFilter.qrCodeGenerator`, shows the IPv4 + port text below.
- Menu items: status line (`Waiting for phone…` / `Connected: <device>`), `Pair phone…`, `Pause syncing` (toggle), `Quit`.
- Info.plist: `LSUIElement=true`, `CFBundleIdentifier=app.clipbridge.mac`, `NSLocalNetworkUsageDescription`, `NSBonjourServices=[_clipbridge._tcp]`.
- `build-app.sh`: `swift build -c release`, assemble `build/ClipBridge.app`, ad-hoc `codesign -s -`.

- [ ] **Step 1:** Add selftest case: `PasteboardWatcher.shouldSkip(types:)` returns true for a type list containing `org.nspasteboard.ConcealedType`, false for `[.string]`.
- [ ] **Step 2:** Run → fails. Implement. Run → passes.
- [ ] **Step 3:** Wire AppDelegate: Watcher → LoopGuard → Server.send; Server.onClip → LoopGuard.markReceived → Watcher.write → Notifier.
- [ ] **Step 4:** `./build-app.sh && open build/ClipBridge.app`; copy text on Mac → `swift run probe "<uri>"` prints it; `probe "<uri>" send "from probe"` → Mac clipboard (`pbpaste`) == `from probe`.
- [ ] **Step 5:** Commit `feat(mac): menu-bar app`.

### Task 4: Android project skeleton + toolchain

**Files:** Create `android/{settings.gradle.kts,build.gradle.kts,gradle.properties,local.properties}`, `android/app/build.gradle.kts`, Gradle wrapper (via `gradle wrapper --gradle-version 8.11.1`), `android/app/src/main/AndroidManifest.xml`, `THIRD_PARTY_NOTICES.md`

- [ ] **Step 1:** Install SDK packages: `sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"`, accept licences; `brew install gradle` for wrapper generation.
- [ ] **Step 2:** Empty `MainActivity`; `./gradlew assembleDebug` → BUILD SUCCESSFUL, APK at `app/build/outputs/apk/debug/app-debug.apk`.
- [ ] **Step 3:** Commit `chore(android): project skeleton`.

### Task 5: Android core (crypto, framing, messages, pairing, loop guard)

**Files:** Create `android/app/src/main/java/app/clipbridge/core/{ClipCrypto,Frame,Message,PairingInfo,LoopGuard}.kt`; Test `android/app/src/test/java/app/clipbridge/core/CoreTest.kt`

**Interfaces — Produces** (Kotlin mirrors of Task 1): `ClipCrypto(key: ByteArray)` with `seal(plain: ByteArray, nonce: ByteArray? = null): ByteArray`, `open(box: ByteArray): ByteArray`; `Frame.encode(payload): ByteArray`, `class FrameReader(input: InputStream) { fun next(): ByteArray }` (throws `IOException` on >1 MiB); `sealed class Message { Hello(device), Clip(id,text,ts), Ping }` with `toJson(): String` / `Message.parse(json): Message` using `org.json`; `PairingInfo.parse(uri): PairingInfo?`; `LoopGuard` same API as Mac.

- [ ] **Step 1:** Write JUnit tests mirroring Task 1 cases, including `knownVector` asserting the exact hex from `docs/test-vector.txt`. (`org.json` in JVM tests: add `testImplementation("org.json:json:20240303")`.)
- [ ] **Step 2:** `./gradlew testDebugUnitTest` → FAIL (unresolved).
- [ ] **Step 3:** Implement (`javax.crypto` `AES/GCM/NoPadding`, 128-bit tag, `updateAAD`). ClipSync's `AesGcmCipher.kt` may be adapted.
- [ ] **Step 4:** Tests PASS.
- [ ] **Step 5:** Commit `feat(android): core crypto and protocol`.

### Task 6: Android sync service

**Files:** Create `.../sync/{SyncService,Connection,Backoff,Prefs}.kt`

**Interfaces:**
- `object Prefs { fun pairing(ctx): PairingInfo?; fun savePairing(ctx, PairingInfo) }` (private SharedPreferences).
- `class Backoff { fun nextDelayMs(): Long; fun reset() }` → 1000, 2000, 5000, 10000, 30000, 30000…
- `class SyncService : Service` — foreground, type `connectedDevice` (manifest perms `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `CHANGE_WIFI_MULTICAST_STATE`, `INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS`). Resolves Mac via `NsdManager` (match instance `ClipBridge-<macId[0..6]>`), else QR host/port. Incoming clip → `ClipboardManager.setPrimaryClip` (fallback: launch ghost with ACTION_WRITE if it throws). Notification shows status + action "Send clipboard" (PendingIntent → GhostActivity READ).
- `companion fun send(ctx: Context, text: String)` → `startService` with action `app.clipbridge.SEND` + extra `text`; service checks LoopGuard and `Frame` size before sending.

- [ ] **Step 1:** Unit test `BackoffTest`: sequence of 7 calls == `[1000,2000,5000,10000,30000,30000,30000]`; after `reset()` next is 1000.
- [ ] **Step 2:** Fail → implement → pass.
- [ ] **Step 3:** Implement service + connection; `assembleDebug` succeeds.
- [ ] **Step 4:** Commit `feat(android): sync service`.

### Task 7: Android copy detection + one-tap fallbacks

**Files:** Create `.../capture/{GhostActivity,CopyDetectService,SendTileService,ShareReceiverActivity}.kt`, `res/xml/accessibility_config.xml`, transparent theme in `res/values/themes.xml`

**Interfaces:**
- `GhostActivity` (transparent, no animation, `excludeFromRecents`): ACTION_READ reads clipboard in `onWindowFocusChanged(true)` → `SyncService.send`; ACTION_WRITE writes extra text; always `finish()`; 2 s safety timeout. Adapted from ClipSync `ClipboardGhostActivity` (MIT).
- `CopyDetectService : AccessibilityService` — events `TYPE_VIEW_CLICKED|TYPE_NOTIFICATION_STATE_CHANGED|TYPE_VIEW_TEXT_SELECTION_CHANGED`. Triggers ghost READ when: click on node whose text/contentDescription matches `copy` words; toast text matches `copied` words; skips `copyright`/`©`. Words: en `copy`, `copied`, `copied to clipboard`; hi `कॉपी`, `कॉपी किया गया`. Debounce 1000 ms events / 700 ms ghost launches. Adapted from ClipSync `ClipboardAccessibilityService` (MIT).
- `CopyDetect.matches(text: String): Boolean` pure function for testing.
- `SendTileService : TileService` — onClick → ghost READ (`startActivityAndCollapse(PendingIntent)` on API 34+, Intent below).
- `ShareReceiverActivity` — `ACTION_SEND` `text/plain` → `SyncService.send` → toast "Sent to Mac" → finish.

- [ ] **Step 1:** Unit test `CopyDetectTest`: `matches("Copy")`, `matches("Copied to clipboard")`, `matches("कॉपी किया गया")` true; `matches("© 2026 Copyright Inc")`, `matches("Paste")` false.
- [ ] **Step 2:** Fail → implement → pass.
- [ ] **Step 3:** Implement components; `assembleDebug` succeeds. Add ClipSync MIT notice to `THIRD_PARTY_NOTICES.md`.
- [ ] **Step 4:** Commit `feat(android): copy detection, tile, share`.

### Task 8: Android main screen + pairing

**Files:** Create `.../MainActivity.kt`, `res/layout/activity_main.xml`

- Plain Views (no Compose). Shows: pairing status, `Scan Mac QR` (zxing `ScanContract`, portrait), `Turn on automatic copy` (opens `Settings.ACTION_ACCESSIBILITY_SETTINGS`, with hint text about "Allow restricted settings" on Android 13+), `Start/Stop sync`, battery-optimisation hint button (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`). Requests `POST_NOTIFICATIONS` on API 33+.
- Scan result → `PairingInfo.parse` → `Prefs.savePairing` → start `SyncService`. Invalid QR → toast "Not a ClipBridge code".

- [ ] **Step 1:** Implement; `./gradlew assembleDebug testDebugUnitTest` → BUILD SUCCESSFUL.
- [ ] **Step 2:** Commit `feat(android): main screen and QR pairing`.

### Task 9: End-to-end on the real phone (with user)
- [ ] Install APK (`adb install -r app-debug.apk` with USB debugging, or AirDrop/Quick Share the APK to the phone).
- [ ] Pair via QR; Mac menu shows `Connected: <phone>`.
- [ ] Copy on Mac → paste on phone within ~1 s.
- [ ] Enable accessibility; copy in Chrome, WhatsApp, Gmail → arrives on Mac. Tile + notification button + Share work.
- [ ] Copy a password from the Mac password manager → NOT on phone.
- [ ] Turn phone Wi-Fi off/on, sleep the Mac → reconnects within 30 s.
- [ ] Write `README.md` with setup steps; commit.
