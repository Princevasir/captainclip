package app.clipbridge

/** Which CaptainClip this is: Pro (automatic copy via an accessibility service) or the standard app (without it). */
object Edition {
    const val AUTO_COPY: Boolean = BuildConfig.AUTO_COPY
    /** Holds location (needed for Bluetooth scanning on Android 10-11). The standard app doesn't. */
    const val LOCATION_FOR_BLUETOOTH: Boolean = BuildConfig.LOCATION_FOR_BLUETOOTH
}
