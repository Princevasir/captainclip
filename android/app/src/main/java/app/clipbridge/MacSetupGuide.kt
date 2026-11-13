package app.clipbridge

/** How to install and pair the Mac app (shown in MacSetupActivity). */
object MacSetupGuide {
    const val DOWNLOAD_URL = "https://github.com/Princevasir/captainclip/releases/latest"

    val steps = listOf(
        "On your Mac, open the download page (tap Share link to send it to your Mac) and download CaptainClip-mac.zip.",
        "Double-click the zip, then drag CaptainClip into your Applications folder.",
        "Open CaptainClip. The first time, macOS blocks it: open System Settings → Privacy & Security and click " +
            "Open Anyway. (macOS 13–14: right-click CaptainClip → Open.)",
        "Allow it to find devices on your network and to use Bluetooth. A clipboard icon appears in the menu bar, " +
            "top right — there's no Dock icon.",
        "Click that icon → Pair phone…, then on this phone tap Scan QR code and point it at the code.",
    )

    /** A drawn macOS picture for each step (generated: cd mac && swift run iconmaker --guide <res/drawable-nodpi>). */
    val pictures = listOf(R.drawable.guide_mac_1, R.drawable.guide_mac_2, R.drawable.guide_mac_3, R.drawable.guide_mac_4,
        R.drawable.guide_mac_5)

    val shareText = "CaptainClip for Mac — download CaptainClip-mac.zip from $DOWNLOAD_URL"
}
