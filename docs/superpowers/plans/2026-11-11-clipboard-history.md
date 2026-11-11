# Clipboard History Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A searchable, windowed, encrypted history on the phone of clips that crossed between Mac and phone.

**Architecture:** Pure-Kotlin history model (window, dedupe, search) + codec + store behind a `HistoryCipher` interface (Keystore AES-GCM on device, fake in tests); one process-wide store; recording calls at the four existing transfer points; a new `HistoryActivity`.

**Tech Stack:** Kotlin, Android SDK only (no new dependencies), JUnit 4, org.json (already a test dependency; `org.json` is in the Android runtime).

**Spec:** `docs/superpowers/specs/2026-11-11-clipboard-history-design.md`

## Global Constraints
- Phone only; both editions (standard, pro). minSdk 29, target 36. No new dependencies.
- Record only what crossed: text from Mac written to clipboard; text to Mac where `send` returned true; Mac file on first successful fetch per offer id; phone file on successful upload.
- Window defaults **50 entries, 7 days**; options **25/50/100** and **1/7/30 days**; history on by default; off = stop + clear.
- Dedupe: equal text to the newest entry → move to top with new time **and new direction**. File entries unique by id.
- HTML kept only if ≤ **65_536** UTF-8 bytes. Sensitive clips never stored.
- Storage: `files/history.bin`, AES-256-GCM, Keystore alias `clipbridge.history`, atomic write; unreadable → empty, never crash.
- Send to Mac from history bypasses `LoopGuard`; automatic paths keep it.
- Tests: `./gradlew testStandardDebugUnitTest testProDebugUnitTest lintStandardRelease lintProRelease` (run tests separately from lint/assemble — a known timing flake under load).

## Review Focus
1. **Clip received from the Mac then re-sent from history** → actually sends (guard bypass) and shows as "To Mac" on top. (Task 4)
2. **Pasting the same Mac PDF three times** (provider probes, cached reads) → one file entry. (Tasks 1, 5)
3. **Corrupt or undecryptable history file** (key reset after restore, partial write) → screen shows empty history, app keeps working. (Task 2)
4. **Turning history off** → existing entries deleted from disk, nothing recorded until on again. (Tasks 2, 5)
5. **Tapping a history clip in ClipBridge Pro** → goes on the phone clipboard but is not auto-sent to the Mac. (Task 6)

---

### Task 1: History model (window, dedupe, search)
**Files:** Create `android/app/src/main/java/app/clipbridge/history/History.kt`; Test `android/app/src/test/java/app/clipbridge/history/HistoryTest.kt`

**Produces:**
- `enum class Direction { FROM_MAC, TO_MAC }`
- `data class HistoryEntry(val id: String, val at: Long, val direction: Direction, val text: String? = null, val html: String? = null, val fileName: String? = null, val mime: String? = null, val size: Long = 0)` with `val isFile get() = fileName != null`
- `data class HistoryLimits(val maxItems: Int = 50, val maxAgeMs: Long = 7 * DAY_MS)`; `companion { const val DAY_MS = 86_400_000L; val ITEM_CHOICES = listOf(25, 50, 100); val DAY_CHOICES = listOf(1, 7, 30); const val MAX_HTML_BYTES = 65_536 }`
- `class History(entries: List<HistoryEntry> = emptyList())` (newest first) with `val entries: List<HistoryEntry>`, `fun addText(text: String, html: String?, direction: Direction, now: Long, sensitive: Boolean = false): Boolean`, `fun addFile(id: String, name: String, mime: String, size: Long, direction: Direction, now: Long): Boolean`, `fun prune(limits: HistoryLimits, now: Long)`, `fun search(query: String): List<HistoryEntry>`, `fun delete(id: String)`, `fun clear()`. `add*` return false when nothing was added/moved (sensitive, blank, duplicate file id).

- [ ] Tests (`HistoryTest`): `newestFirst`; `equalTextMovesToTopWithNewTimeAndDirection` (FROM_MAC "a" at 1, "b" at 2, TO_MAC "a" at 3 → ["a"(TO_MAC,3), "b"]); `onlyTheNewestEntryIsCompared` (add "a", then "b", then "a" → ["a","b","a"]); `sensitiveAndBlankAreNeverStored`; `htmlOver64KbIsDroppedButTextKept`; `fileIdIsRecordedOnce`; `pruneByCount` (60 entries, limits 50 → 50 newest); `pruneByAge` (entry older than 7 days removed); `searchIsCaseInsensitiveOverTextAndFileNames`; `deleteAndClear`.
- [ ] RED → GREEN. Commit `feat(android): clipboard history model`.

### Task 2: Codec, cipher interface, store
**Files:** Create `history/HistoryStore.kt` (with `interface HistoryCipher { fun encrypt(plain: ByteArray): ByteArray; fun decrypt(box: ByteArray): ByteArray }`, `object HistoryCodec { fun encode(entries: List<HistoryEntry>): ByteArray; fun decode(bytes: ByteArray): List<HistoryEntry> }` — JSON `{"v":1,"entries":[…]}`), Test `history/HistoryStoreTest.kt` with a test `XorCipher`.

**Produces:** `class HistoryStore(private val file: File, private val cipher: HistoryCipher, private val clock: () -> Long, var limits: HistoryLimits = HistoryLimits(), var enabled: Boolean = true)` — all methods `@Synchronized`: `fun entries(): List<HistoryEntry>` (prunes first), `fun recordText(text, html, direction, sensitive = false)`, `fun recordFile(id, name, mime, size, direction)`, `fun search(query): List<HistoryEntry>`, `fun delete(id)`, `fun clear()`, `fun setEnabled(on: Boolean)` (off → clear + delete file). Each change prunes and persists (temp file + rename). Loading failure (missing file, decrypt/parse error) → empty history and the bad file deleted.

- [ ] Tests: `codecRoundTripsAllFields`; `persistsAcrossInstances` (new store on same file sees entries); `fileOnDiskIsNotPlainText` (bytes don't contain the clip text); `corruptFileGivesEmptyHistoryAndRecordsAgain`; `disabledRecordsNothingAndDeletesFile`; `limitsApplyOnRead` (clock advanced 8 days → empty).
- [ ] RED → GREEN. Commit `feat(android): encrypted history store`.

### Task 3: Keystore cipher + settings
**Files:** Create `history/KeystoreCipher.kt` (`class KeystoreCipher(alias: String = "clipbridge.history") : HistoryCipher` — AES/GCM/NoPadding, 256-bit key generated in `AndroidKeyStore` if missing, output = 12-byte IV ‖ ciphertext); Create `history/HistoryHub.kt` (`object HistoryHub { fun store(ctx: Context): HistoryStore }` — one instance per process, file `ctx.filesDir/history.bin`, limits/enabled from Prefs); Modify `sync/Prefs.kt`: `historyEnabled/setHistoryEnabled` (default true), `historyMaxItems/setHistoryMaxItems` (default 50), `historyMaxDays/setHistoryMaxDays` (default 7).

- [ ] Test `HistoryLimitsTest.fromSettings`: `HistoryLimits.from(items = 25, days = 1)` → maxItems 25, maxAgeMs 1 day; unknown values fall back to 50 / 7 (add `fun from(items: Int, days: Int): HistoryLimits` to the companion).
- [ ] RED → GREEN; build + lint (Keystore adapter is device-verified in Task 7, ledger Ruling). Commit.

### Task 4: User-initiated send bypasses the echo guard
**Files:** Modify `sync/SyncLoop.kt` (`fun send(text: String, html: String? = null, userInitiated: Boolean = false): Boolean` — when true skip `loopGuard.shouldSend` but still require non-empty text, then `markSent`); Modify `sync/SyncService.kt` companion `send(text, html, userInitiated = false)`; Test in `sync/SyncLoopTest.kt`.

- [ ] Tests: `userInitiatedSendResendsTextJustReceivedFromMac` (FakeMac sends clip "x"; `send("x")` false; `send("x", userInitiated = true)` true and Mac receives it); `automaticSendStillBlocksEcho` (unchanged behaviour asserted).
- [ ] RED → GREEN. Commit.

### Task 5: Record at the transfer points
**Files:** Modify `sync/SyncService.kt`: `writeClipboard` (FROM_MAC text), companion `send` (TO_MAC when it returned true), `fetchOffer` / `saveOffer` / `prepare` after a successful fetch (FROM_MAC file, id = offer id, via `recordFile` whose id-dedupe makes repeats harmless), `uploadFiles` on `ok` (TO_MAC file, id = new UUID). Sensitive: phone sends are already filtered before `send`; pass `sensitive = false`.

- [ ] Test (store level, already covers dedupe): add `HistoryStoreTest.repeatedFetchOfSameOfferRecordsOnce`.
- [ ] Glue verified by build + device test (Task 7). Commit.

### Task 6: History screen + entry point
**Files:** Create `HistoryActivity.kt`, `res/layout/activity_history.xml`, `res/layout/item_history.xml`; Modify `MainActivity.kt` + `activity_main.xml` (row "Clipboard history" first in the More card, subtitle "Last: …" when not empty), `AndroidManifest.xml`; Modify `UiText.kt`: `fun ago(msAgo: Long): String` and `fun historyPreview(entry: HistoryEntry): String`.

- [ ] Tests (`UiTextTest`): `ago` → "Just now" (< 60 s), "5 min ago", "2 h ago", "Yesterday" (24–48 h), "3 days ago"; `historyPreview` → text collapsed to one paragraph and cut at 140 chars with "…", file → "📄 name · size".
- [ ] Screen: search `EditText` filtering live; list of entries (direction · ago, 3-line preview); tap text → clipboard (with html) after `OwnWrites.markNow()` + toast "Copied"; long-press → Copy / Send to Mac (enabled only when `SyncService.isConnected`; uses `userInitiated = true`) / Delete; ⋮ menu → Settings (dialog: on/off switch, items 25/50/100, days 1/7/30) and Clear history (confirm). Empty states per spec. Insets as in MacSetupActivity.
- [ ] RED → GREEN for UiText; build + lint. Commit.

### Task 7: Privacy text, deploy, device test
- [ ] `PRIVACY.md`: add a "Clipboard history" row/section (on phone only, encrypted, your limits, off/clear, removed on uninstall); update the "What ClipBridge handles" table. `docs/play-store/README.md`: note the history in the description bullets; Data safety unchanged.
- [ ] Full suites; install on phone (check foreground app first; don't interrupt the user).
- [ ] Device: copy on Mac → appears "From Mac"; Send to Mac tile → "To Mac"; same text again moves to top; paste a Mac PDF twice → one entry; tap entry → clipboard; Send to Mac from history of the Mac-originated clip → Mac receives; Settings off → list empty.
- [ ] Final review (fresh reviewer), fix Critical/Important RED→GREEN. Commit.
