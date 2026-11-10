package app.clipbridge.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

object BootPolicy {
    fun shouldStart(paired: Boolean, enabled: Boolean) = paired && enabled
}

/** Restarts sync after the phone reboots (or the app updates), if the user had it on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (BootPolicy.shouldStart(paired = Prefs.pairing(context) != null, enabled = Prefs.syncEnabled(context))) {
            SyncService.start(context)
        }
    }
}
