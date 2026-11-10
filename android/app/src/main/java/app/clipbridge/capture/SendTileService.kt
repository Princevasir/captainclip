package app.clipbridge.capture

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.TileService

/** Quick Settings tile: one tap sends the current clipboard to the Mac. */
class SendTileService : TileService() {
    // The Intent overload is only used below Android 14, where it is the correct API.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        val intent = GhostActivity.readIntent(this, announce = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(intent)
        }
    }
}
