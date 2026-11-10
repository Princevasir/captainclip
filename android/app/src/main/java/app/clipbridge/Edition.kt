package app.clipbridge

/** Which ClipBridge this is: full (automatic copy via an accessibility service) or Lite (without it). */
object Edition {
    const val AUTO_COPY: Boolean = BuildConfig.AUTO_COPY
}
