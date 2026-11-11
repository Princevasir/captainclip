package app.clipbridge

/** Which ClipBridge this is: full (automatic copy via an accessibility service) or Lite (without it). */
object Edition {
    const val AUTO_COPY: Boolean = BuildConfig.AUTO_COPY
    /** Holds location (needed for Bluetooth scanning on Android 10-11). Lite doesn't. */
    const val LOCATION_FOR_BLUETOOTH: Boolean = BuildConfig.LOCATION_FOR_BLUETOOTH
}
