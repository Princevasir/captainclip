package app.clipbridge

/** How to install and pair the Mac app (shown in MacSetupActivity). */
object MacSetupGuide {
    const val DOWNLOAD_URL = "https://github.com/Princevasir/clipbridge/releases/latest"

    val steps = listOf(
        "On your Mac, open the download page (tap Share link to send it to your Mac) and download ClipBridge-mac.zip.",
        "Double-click the zip, then drag ClipBridge into your Applications folder.",
        "Open ClipBridge. The first time, macOS blocks it: open System Settings → Privacy & Security and click " +
            "Open Anyway. (macOS 13–14: right-click ClipBridge → Open.)",
        "Allow it to find devices on your network and to use Bluetooth. A clipboard icon appears in the menu bar, " +
            "top right — there's no Dock icon.",
        "Click that icon → Pair phone…, then on this phone tap Scan QR code and point it at the code.",
    )

    val shareText = "ClipBridge for Mac — download ClipBridge-mac.zip from $DOWNLOAD_URL"
}
