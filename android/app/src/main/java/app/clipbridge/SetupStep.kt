package app.clipbridge

/** The next thing the user should do on the main screen, in order. */
enum class SetupStep(val help: String) {
    PAIR("On your Mac, click the ClipBridge menu-bar icon → Pair phone…, then scan the QR code."),
    NOTIFICATIONS("Allow notifications so ClipBridge can stay connected in the background."),
    AUTO_COPY("Turn on automatic copy: Settings → Accessibility → ClipBridge automatic copy. " +
        "If it is greyed out (Android 13+): App info → ⋮ → Allow restricted settings, then try again."),
    DONE("All set. Copy on either device and paste on the other. " +
        "If a copy doesn't arrive, use the \"Send to Mac\" Quick Settings tile or notification button.");

    companion object {
        fun next(paired: Boolean, notificationsAllowed: Boolean, autoCopyOn: Boolean): SetupStep = when {
            !paired -> PAIR
            !notificationsAllowed -> NOTIFICATIONS
            !autoCopyOn -> AUTO_COPY
            else -> DONE
        }
    }
}
