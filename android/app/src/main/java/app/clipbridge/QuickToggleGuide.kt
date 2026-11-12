package app.clipbridge

/** Words for the notification panel buttons (Send to Mac, ClipBridge on/off), per Android version. */
object QuickToggleGuide {
    /** Android 13+ can ask "Add this button?" from inside the app. */
    fun oneTap(sdk: Int): Boolean = sdk >= 33

    /** Heading of the help card: with one-tap Add only tips are left; older phones add by hand. */
    fun title(sdk: Int): String = if (oneTap(sdk)) "Tips" else "How to add them"

    fun steps(sdk: Int): List<String> = if (oneTap(sdk)) listOf(
        "New buttons go to the end of the panel. To move them up next to Torch: swipe down twice, tap ✏️ (Samsung: ⋮ → Edit buttons) and drag them.",
        "Copy something, swipe down and tap Send to Mac. Tap ClipBridge to pause or resume syncing.",
    ) else listOf(
        "Swipe down twice from the top of the screen, tap ✏️ Edit (Samsung: ⋮ → Edit buttons) and find Send to Mac and ClipBridge.",
        "Drag them up next to Torch, then tap Done.",
        "Copy something, swipe down and tap Send to Mac. Tap ClipBridge to pause or resume syncing.",
    )
}
