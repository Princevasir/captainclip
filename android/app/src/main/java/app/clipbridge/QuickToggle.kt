package app.clipbridge

import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.Build
import app.clipbridge.capture.SendTileService
import app.clipbridge.sync.Prefs

/** Asks Android to add the Send to Mac toggle (Android 13+ shows an "Add this button?" box). */
object QuickToggle {
    fun request(activity: Activity, onDone: (added: Boolean) -> Unit) = request(activity, onoff = false, onDone)

    /** [onoff] true = the ClipBridge on/off switch instead of Send to Mac. */
    fun request(activity: Activity, onoff: Boolean, onDone: (added: Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < 33) return onDone(false)
        val bar = activity.getSystemService(StatusBarManager::class.java) ?: return onDone(false)
        bar.requestAddTileService(
            ComponentName(activity, if (onoff) app.clipbridge.capture.ConnectionTileService::class.java else SendTileService::class.java),
            activity.getString(if (onoff) R.string.connection_tile_label else R.string.tile_label),
            Icon.createWithResource(activity, if (onoff) R.drawable.ic_devices else R.drawable.ic_tile_send),
            activity.mainExecutor,
        ) { result ->
            val added = result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
            if (added) if (onoff) Prefs.setSwitchTileAdded(activity, true) else Prefs.setTileAdded(activity, true)
            onDone(added)
        }
    }
}
