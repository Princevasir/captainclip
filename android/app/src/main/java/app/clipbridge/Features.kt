package app.clipbridge

/** What ClipBridge does, in the order the store listing uses (shown on FeaturesActivity). */
object Features {
    data class Feature(val icon: Int, val title: String, val body: String)

    fun list(pro: Boolean): List<Feature> {
        val all = mutableListOf(
            Feature(R.drawable.ic_from_mac, "Copy on your Mac, paste here",
                "Anything you copy on your Mac is on this phone's clipboard a moment later. Just paste — nothing to tap."),
            Feature(R.drawable.ic_to_mac, "Send to Mac, next to Copy",
                "Select text in any app and tap Send to Mac in the menu, right next to Copy. Or use Share → Send to Mac."),
            Feature(R.drawable.ic_tile_send, "One tap, wherever you are",
                "Send what you copied from the notification panel button, the home-screen widget, or long-press the " +
                    "ClipBridge icon."),
            Feature(R.drawable.ic_image, "Screenshots and pictures",
                "Take a screenshot or copy a picture on your Mac, then paste it into any app here."),
            Feature(R.drawable.ic_file, "Files, both ways",
                "Copy up to 20 files in Finder, then share them or save them to Downloads here. Share → Send to Mac " +
                    "puts this phone's files in your Mac's Downloads."),
            Feature(R.drawable.ic_history, "Clipboard history",
                "Find and reuse what you sent from this phone. Kept only here, encrypted."),
            Feature(R.drawable.ic_devices, "Wi-Fi or Bluetooth",
                "Works on the same Wi-Fi, and over Bluetooth when there isn't one (Android 12+) — it switches by itself."),
            Feature(R.drawable.ic_lock, "Private by design",
                "No account, no cloud. Your phone and Mac talk directly, end-to-end encrypted. Passwords are never sent."),
        )
        if (pro) all.add(1, Feature(R.drawable.ic_sparkle, "Automatic copy",
            "What you copy on this phone goes to your Mac by itself — no tap needed."))
        return all
    }

    /** Opened by itself once, right after the first successful pairing. */
    fun showAfterPairing(seen: Boolean): Boolean = !seen
}
