package app.clipbridge

/** The next setup step the main screen shows, in order. Notifications are optional (sync works without them). */
enum class SetupStep(val title: String, val help: String) {
    PAIR("Pair with your Mac",
        "On your Mac, click the ClipBridge icon in the menu bar → Pair phone…, then scan the QR code."),
    AUTO_COPY("Turn on automatic copy",
        "Settings → Accessibility → Installed apps → ClipBridge automatic copy. " +
            "Greyed out? App info → ⋮ → Allow restricted settings first."),
    BLUETOOTH("Allow Bluetooth fallback",
        "Keeps ClipBridge connected when your phone and Mac aren't on the same Wi-Fi. " +
            "Android asks for \"Nearby devices\" — ClipBridge only uses it to find your own Mac."),
    DONE("All set", "Copy on one device, paste on the other.");

    enum class PermissionAction { ASK, OPEN_APP_INFO }

    companion object {
        /** "Allow Bluetooth": ask Android, unless it will no longer show the prompt ("Don't ask again"). */
        fun bluetoothAction(askedBefore: Boolean, canShowPrompt: Boolean): PermissionAction =
            if (askedBefore && !canShowPrompt) PermissionAction.OPEN_APP_INFO else PermissionAction.ASK

        fun next(paired: Boolean, autoCopyOn: Boolean, bluetoothAllowed: Boolean = true, bluetoothDismissed: Boolean = false): SetupStep = when {
            !paired -> PAIR
            !autoCopyOn -> AUTO_COPY
            !bluetoothAllowed && !bluetoothDismissed -> BLUETOOTH
            else -> DONE
        }
    }
}
