package app.clipbridge.capture

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.clipbridge.MainActivity
import app.clipbridge.R
import app.clipbridge.UiText
import app.clipbridge.sync.Prefs
import app.clipbridge.sync.SyncLoop
import app.clipbridge.sync.SyncService

/** Quick Settings "CaptainClip" connection switch: on = syncing with your Mac, off = paused. */
class ConnectionTileService : TileService() {
    override fun onTileAdded() = Prefs.setSwitchTileAdded(this, true)
    override fun onTileRemoved() = Prefs.setSwitchTileAdded(this, false)

    override fun onStartListening() {
        val tile = qsTile ?: return
        val paired = Prefs.pairing(this) != null
        val paused = paired && SyncService.isPaused(this)
        val on = paired && Prefs.syncEnabled(this) && !paused
        tile.icon = Icon.createWithResource(this, R.drawable.ic_devices)
        tile.label = getString(R.string.connection_tile_label)
        tile.subtitle = UiText.connectionLine(paired, Prefs.syncEnabled(this),
            SyncService.state == SyncLoop.Status.CONNECTED, SyncService.macName, SyncService.onBluetooth,
            pausedLine = UiText.pausedLine(Prefs.pausedUntil(this), System.currentTimeMillis()) {
                android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(it))
            }).second
        tile.state = when { !paired -> Tile.STATE_UNAVAILABLE; on -> Tile.STATE_ACTIVE; else -> Tile.STATE_INACTIVE }
        tile.updateTile()
    }

    // The Intent overload is only used below Android 14, where it is the correct API.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        if (Prefs.pairing(this) == null) return openApp()
        if (SyncService.isPaused(this)) {
            if (runCatching { SyncService.resume(this) }.isFailure) return openApp()
            return onStartListening()
        }
        val enable = !Prefs.syncEnabled(this)
        Prefs.setSyncEnabled(this, enable)
        if (enable) {
            // Allowed from a tile tap; if Android refuses (no companion link on some versions), open the app instead.
            if (runCatching { SyncService.start(this) }.isFailure) return openApp()
        } else {
            SyncService.stop(this)
        }
        onStartListening()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 31, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(intent)
        }
    }

    companion object {
        fun refresh(ctx: Context) {
            runCatching { requestListeningState(ctx, ComponentName(ctx, ConnectionTileService::class.java)) }
        }
    }
}
