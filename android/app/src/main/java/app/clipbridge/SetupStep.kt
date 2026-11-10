package app.clipbridge

/** The next setup step the main screen shows, in order. Notifications are optional (sync works without them). */
enum class SetupStep(val title: String, val help: String) {
    PAIR("Pair with your Mac",
        "On your Mac, click the ClipBridge icon in the menu bar → Pair phone…, then scan the QR code."),
    AUTO_COPY("Turn on automatic copy",
        "Settings → Accessibility → Installed apps → ClipBridge automatic copy. " +
            "Greyed out? App info → ⋮ → Allow restricted settings first."),
    DONE("All set", "Copy on one device, paste on the other.");

    companion object {
        fun next(paired: Boolean, autoCopyOn: Boolean): SetupStep = when {
            !paired -> PAIR
            !autoCopyOn -> AUTO_COPY
            else -> DONE
        }
    }
}
