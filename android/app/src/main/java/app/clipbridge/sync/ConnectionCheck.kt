package app.clipbridge.sync

/** What the phone knows about its connection right now. Contains no addresses or clipboard content. */
data class CheckFacts(
    val connected: Boolean,
    val path: SyncLoop.Path?,
    val onWifi: Boolean,
    val macVisible: Boolean,
    val macReachable: Boolean,
    val bluetoothOn: Boolean,
    val bluetoothAllowed: Boolean,
    val pairingInvalid: Boolean,
    val backgroundOk: Boolean,
    val paused: Boolean,
)

enum class Severity { OK, WARN, PROBLEM }

data class CheckLine(val title: String, val severity: Severity, val detail: String)

object ConnectionCheck {
    fun headline(f: CheckFacts): String = when {
        f.paused -> "Syncing is paused"
        f.pairingInvalid -> "Your Mac was paired again — scan its new QR code"
        f.connected -> when (f.path) {
            SyncLoop.Path.WIFI -> "Connected to your Mac over Wi-Fi"
            SyncLoop.Path.BLUETOOTH -> "Connected to your Mac over Bluetooth"
            null -> "Connected to your Mac"
        }
        !f.onWifi -> "This phone isn't on Wi-Fi"
        f.macVisible && !f.macReachable -> "Your Wi-Fi is blocking devices from talking to each other"
        !f.macVisible && f.macReachable -> "Your Mac answered at its last address — CaptainClip will reconnect"
        !f.macVisible -> "Can't find your Mac on this Wi-Fi"
        else -> "Still looking for your Mac"
    }

    fun lines(f: CheckFacts): List<CheckLine> = listOf(wifi(f), mac(f), bluetooth(f), pairing(f), background(f))

    private fun wifi(f: CheckFacts) = when {
        f.onWifi -> CheckLine("Wi-Fi", Severity.OK, "All good — this phone is on Wi-Fi.")
        f.connected -> CheckLine("Wi-Fi", Severity.WARN, "This phone isn't on Wi-Fi — you're still synced over Bluetooth, or join your Mac's Wi-Fi for the fastest sync.")
        else -> CheckLine("Wi-Fi", Severity.PROBLEM, "This phone isn't on Wi-Fi — join the same Wi-Fi as your Mac, or use Bluetooth instead.")
    }

    private fun mac(f: CheckFacts) = when {
        f.connected && f.path == SyncLoop.Path.WIFI -> CheckLine("Mac on this Wi-Fi", Severity.OK, "All good — connected to your Mac over Wi-Fi.")
        !f.onWifi -> CheckLine("Mac on this Wi-Fi", Severity.WARN, "Can't look for your Mac without Wi-Fi — join your Mac's Wi-Fi, or use Bluetooth.")
        f.macVisible && f.macReachable -> CheckLine("Mac on this Wi-Fi", Severity.OK, "All good — your Mac is on this Wi-Fi and answering.")
        f.macVisible -> CheckLine("Mac on this Wi-Fi", Severity.PROBLEM, "Your Mac is here but can't be reached — guest, hotel or office Wi-Fi often blocks this, so use Bluetooth or a different Wi-Fi.")
        f.macReachable -> CheckLine("Mac on this Wi-Fi", Severity.WARN, "Your Wi-Fi hides devices from each other, but your Mac's last address works.")
        else -> CheckLine("Mac on this Wi-Fi", Severity.PROBLEM, "Is CaptainClip open on your Mac, and is it on the same Wi-Fi?")
    }

    private fun bluetooth(f: CheckFacts) = when {
        !f.bluetoothAllowed -> CheckLine("Bluetooth", Severity.WARN, "CaptainClip isn't allowed to use Bluetooth — allow Nearby devices for CaptainClip in your phone's settings.")
        !f.bluetoothOn -> CheckLine("Bluetooth", Severity.WARN, "Bluetooth is off — turn it on to keep syncing when Wi-Fi isn't available.")
        else -> CheckLine("Bluetooth", Severity.OK, "All good — Bluetooth is on and ready as a backup.")
    }

    private fun pairing(f: CheckFacts) =
        if (f.pairingInvalid) CheckLine("Pairing", Severity.PROBLEM, "Your Mac was paired again — scan the new QR code shown on your Mac to reconnect.")
        else CheckLine("Pairing", Severity.OK, "All good — this phone is paired with your Mac.")

    private fun background(f: CheckFacts) =
        if (f.backgroundOk) CheckLine("Background", Severity.OK, "All good — CaptainClip can keep running in the background.")
        else CheckLine("Background", Severity.WARN, "Android may stop CaptainClip in the background — set its battery use to Unrestricted in your phone's settings.")
}

/** [kind] is one of: connected-wifi, connected-bluetooth, lost, auth-failed, paused, resumed. */
data class ConnEvent(val at: Long, val kind: String)

object ConnectionReport {
    private const val MAX_EVENTS = 20

    fun text(
        app: String,
        android: String,
        lines: List<CheckLine>,
        events: List<ConnEvent>,
        now: Long,
        time: (Long) -> String,
    ): String = buildString {
        appendLine("CaptainClip connection report")
        appendLine("App: $app")
        appendLine("Android: $android")
        appendLine("Time: ${time(now)}")
        appendLine()
        lines.forEach { appendLine("${mark(it.severity)} ${it.title} — ${it.detail}") }
        appendLine()
        appendLine("Recent events:")
        events.takeLast(MAX_EVENTS).forEach { appendLine("${time(it.at)}  ${it.kind}") }
    }

    private fun mark(s: Severity) = when (s) {
        Severity.OK -> "✓"
        Severity.WARN -> "!"
        Severity.PROBLEM -> "✕"
    }
}
