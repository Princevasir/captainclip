package app.clipbridge

/** The next setup step the main screen shows, in order. Notifications are optional (sync works without them). */
enum class SetupStep(val title: String, val help: String) {
    PAIR("Pair with your Mac",
        "On your Mac, click the CaptainClip icon in the menu bar → Pair phone…, then scan the QR code."),
    AUTO_COPY("Turn on automatic copy",
        "Settings → Accessibility → Installed apps → CaptainClip automatic copy. " +
            "Greyed out? App info → ⋮ → Allow restricted settings first."),
    BLUETOOTH("Allow Bluetooth fallback",
        "Keeps CaptainClip connected when your phone and Mac aren't on the same Wi-Fi. " +
            "Android asks for \"Nearby devices\" — CaptainClip only uses it to find your own Mac."),
    COMPANION("Keep CaptainClip connected",
        "Link your Mac as a companion device, like a watch or earbuds: Android then lets CaptainClip stay connected " +
            "and reconnect in the background, without battery restrictions."),
    QUICK_TOGGLE("Add Send to Mac to your notification panel",
        "A button next to Torch when you swipe down: copy on your phone, swipe down, tap Send to Mac."),
    DONE("All set", "Copy on one device, paste on the other.");

    enum class PermissionAction { ASK, OPEN_APP_INFO }

    companion object {
        /** "Allow Bluetooth": ask Android, unless it will no longer show the prompt ("Don't ask again"). */
        fun bluetoothAction(askedBefore: Boolean, canShowPrompt: Boolean): PermissionAction =
            if (askedBefore && !canShowPrompt) PermissionAction.OPEN_APP_INFO else PermissionAction.ASK

        /** Bluetooth scanning needs "Nearby devices" on Android 12+, or location on Android 10-11. */
        fun bluetoothFallbackAvailable(sdk: Int, hasLocationPermission: Boolean): Boolean = sdk >= 31 || hasLocationPermission

        fun next(paired: Boolean, autoCopyOn: Boolean, bluetoothAllowed: Boolean = true, bluetoothDismissed: Boolean = false,
                 autoCopyAvailable: Boolean = true, bluetoothAvailable: Boolean = true,
                 tileAdded: Boolean = true, tileDismissed: Boolean = false,
                 companionAvailable: Boolean = false, companionLinked: Boolean = false, companionDismissed: Boolean = false,
        ): SetupStep = when {
            !paired -> PAIR
            autoCopyAvailable && !autoCopyOn -> AUTO_COPY
            bluetoothAvailable && !bluetoothAllowed && !bluetoothDismissed -> BLUETOOTH
            companionAvailable && !companionLinked && !companionDismissed -> COMPANION
            !tileAdded && !tileDismissed -> QUICK_TOGGLE
            else -> DONE
        }
    }
}
