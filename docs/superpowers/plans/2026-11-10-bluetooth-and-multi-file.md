# Bluetooth Fallback + Several Files Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The phone falls back to an encrypted BLE link when Wi-Fi can't reach the Mac, and Finder copies of 2–20 files are offered and pasted together.

**Architecture:** The existing encrypted session runs over a pluggable transport (TCP or BLE byte stream, packetised to the MTU). Mac = BLE peripheral (CoreBluetooth), phone = central. The phone's sync loop picks Wi-Fi first, Bluetooth after 2 Wi-Fi failures, and moves back to Wi-Fi when it returns. Several files use a new `offers` message whose items are fetched with the existing `fetch`.

**Tech Stack:** as before + CoreBluetooth (Mac) and android.bluetooth.le (Android). No third-party deps.

**Spec:** `docs/superpowers/specs/2026-11-10-bluetooth-and-multi-file-design.md`

## Global Constraints
- Same handshake, encryption and frames over both transports. Wi-Fi is always preferred.
- BLE GATT service UUID `6C1B0001-8C4F-4E5A-9B8E-5A1D3C2B7F10`; `toMac` `6C1B0002-…`; `toPhone` `6C1B0003-…` (same suffix).
- Pairing tag = first 4 bytes of SHA-256(UTF-8 macId), advertised as service data; test vector:
  macId `0123456789abcdef` → tag computed in Task 2 and asserted on Android in Task 6.
- Bluetooth file cap **5 MB** (`FileLimit.bluetoothMaxBytes = 5_242_880`); error reason `too-large-bluetooth`.
- Several files: 2–20 regular files, each ≤ 25 MB, total ≤ 100 MB (`FileLimit.setMaxBytes = 104_857_600`).
- Wire: `{"t":"offers","items":[{"id","name","mime","size"}],"ts":N}`.
- All existing tests stay green after each refactor task. Mac tests: `swift run selftest`; Android: `./gradlew testDebugUnitTest lintDebug`.
- Nothing is pushed.

## Review Focus
1. **Wi-Fi drops mid-transfer and the phone falls back to Bluetooth** → partial files are cleaned up (existing reconnect rules), no duplicate sessions.
2. **Bluetooth permission denied (phone or Mac)** → ClipBridge stays Wi-Fi-only, no crash, setup step explains.
3. **Another ClipBridge Mac nearby** → phone connects only to the Mac whose pairing tag matches, and the handshake still authenticates.
4. **6 MB file pasted while on Bluetooth** → fails fast with the "connect to Wi-Fi" message, never hangs the pasting app.
5. **Finder copy of 3 files + 1 folder, or 25 files** → nothing offered, no names sent.

---

### Task 1: Mac transport abstraction (refactor)
`mac/Sources/ClipBridgeCore/Transport.swift` (+ `Session.swift`, `Server.swift`, `Client.swift`)
**Produces:** `protocol Transport: AnyObject { var isBluetooth: Bool; var onReady/onData/onClose; func start(queue:); func send(_:completion:); func cancel() }`; `final class TCPTransport: Transport` wrapping NWConnection; `Session(transport:…)`; `Server.accept(transport:)` public.
- [ ] Run full selftest → all green unchanged (behaviour-preserving refactor). Commit.

### Task 2: Mac packetiser + in-memory packet transport
`Packetizer.swift`; test `PacketTransportTests.swift`
**Produces:** `enum Packetizer { static func split(_ data: Data, mtu: Int) -> [Data] }`; `PairingTag.of(macId:) -> Data` (4 bytes); test helper `PacketPipe` (two linked Transports, MTU 182, delivers one packet at a time, flagged `isBluetooth`).
- [ ] Tests: split sizes; pairing tag vector printed + recorded; a Client-equivalent session over PacketPipe completes the handshake, exchanges a clip with html, and transfers a 2 MB file intact.
- [ ] RED → GREEN. Commit.

### Task 3: Mac Bluetooth size cap
`Bridge.swift`/`Server.swift`; tests in `PacketTransportTests.swift`
- [ ] Tests: over a Bluetooth transport, fetch of a 6 MB offer → `file-error too-large-bluetooth`; 1 MB → served; over TCP 6 MB → served; phone upload of 6 MB over BLE → refused.
- [ ] RED → GREEN. Commit.

### Task 4: Mac BLE peripheral (radio glue)
`BLEServer.swift` (CBPeripheralManager: service, characteristics, advertising with pairing tag, one central → `BLEPeripheralTransport` → `server.accept`), `Info.plist` (`NSBluetoothAlwaysUsageDescription`), menu line "Bluetooth fallback: …".
- [ ] Build + selftest green; device-verified in Task 12. Commit.

### Task 5: Android link abstraction (refactor)
`sync/Link.kt` (`interface Link { val isBluetooth: Boolean; fun open(): Streams; fun close() }`, `TcpLink`), `Connection(link, …)`.
- [ ] Full unit suite green unchanged. Commit.

### Task 6: Android packetiser + packet link
`core/Packetizer.kt`, `core/PairingTag.kt`; tests `PacketLinkTest.kt` (FakeMac over an in-memory 182-byte packet channel)
- [ ] Tests: split sizes; pairing tag equals the Mac vector; Connection over the packet link handshakes, receives a clip with html, uploads a 2 MB file intact.
- [ ] RED → GREEN. Commit.

### Task 7: Android path selection
`sync/SyncLoop.kt` (`wifiLink`/`bleLink` dialers injected; `Status.CONNECTED` + `path: Path {WIFI, BLUETOOTH}`)
- [ ] Tests with fake dialers: Wi-Fi fails twice → Bluetooth used; Bluetooth dialer null (no permission) → stays on Wi-Fi retries; while on Bluetooth, Wi-Fi available again → switches to Wi-Fi within one probe; status reports the path.
- [ ] RED → GREEN. Commit.

### Task 8: Android Bluetooth caps and timeouts
`SyncLoop.fetch/sendFile`, `SyncService.fetchOffer`
- [ ] Tests: sendFile 6 MB on Bluetooth → false; fetch timeout on Bluetooth = 10 s + size ÷ 30 KB/s; first-data 6 s.
- [ ] RED → GREEN. Commit.

### Task 9: Android BLE central (radio glue) + permission step
`sync/BleLink.kt` (scan filter UUID + tag, connect, MTU 517, 2M PHY, notifications → PipedInputStream, writes with flow control), manifest permissions, `SetupStep` optional "Allow Bluetooth fallback", status "via Bluetooth".
- [ ] SetupStep test for the new optional step; build + lint green; radio device-verified in Task 12. Commit.

### Task 10: Mac several files
`PasteboardWatcher.onFiles`, `OfferStore.makeSet`, `Message.offers`, `Bridge`; tests `OfferSetTests.swift`
- [ ] Tests: 3 files → one `offers` with 3 items, no text; each item fetchable; 25 files / folder / total > 100 MB → nothing; text copy → offer-clear; old `offer` for 1 file unchanged.
- [ ] RED → GREEN. Commit.

### Task 11: Android several files
`Message.Offers`, `OfferCache` (set), provider per item, multi-item placeholder, *Save to Downloads* saves all, card shows "N files · size"
- [ ] Tests: parse Mac `offers` JSON; OfferCache keeps all items, expiry/clear rules per set, cached copies per item.
- [ ] RED → GREEN; build + lint. Commit.

### Task 12: Review, deploy, device tests
- [ ] Fresh reviewer on the branch; fix Critical/Important RED→GREEN.
- [ ] Deploy (APK via USB if Developer options are on, Mac app rebuild).
- [ ] Device: turn the phone's Wi-Fi off → status "Connected via Bluetooth"; copy text both ways; ⌃⇧⌘4 screenshot paste; Wi-Fi on → "via Wi-Fi". ⌘C 3 PDFs → paste all into Gmail; Save all.
- [ ] README. Commit.
