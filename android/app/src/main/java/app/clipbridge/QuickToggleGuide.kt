package app.clipbridge

/** How to put the "Send to Mac" toggle in the notification panel (Quick Settings), per Android version. */
object QuickToggleGuide {
    /** Android 13+ can ask "Add this button?" from inside the app. */
    fun oneTap(sdk: Int): Boolean = sdk >= 33

    fun steps(sdk: Int): List<String> = listOf(
        if (oneTap(sdk)) "Tap Add it now, then Add in the box that appears."
        else "Swipe down twice from the top of the screen, tap ✏️ Edit (Samsung: ⋮ → Edit buttons) and find Send to Mac.",
        "Samsung phones put new toggles at the end of the panel: tap ✏️ Edit and drag Send to Mac up next to Torch, then Done.",
        "Copy something on your phone, swipe down and tap Send to Mac. It lights up with \"Ready\" while your Mac is connected.",
    )
}
