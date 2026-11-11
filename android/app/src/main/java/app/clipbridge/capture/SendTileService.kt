package app.clipbridge.capture

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.os.Build
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.clipbridge.R
import app.clipbridge.UiText
import app.clipbridge.sync.SyncService

/** Quick Settings tile: one tap sends the current clipboard to the Mac. */
class SendTileService : TileService() {
    /** Lit (like Location) while the Mac is connected, dim otherwise; the second line says how. */
    override fun onStartListening() {
        val tile = qsTile ?: return
        val connected = SyncService.isConnected
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_send)
        tile.label = getString(R.string.tile_label)
        tile.subtitle = UiText.tileSubtitle(connected, SyncService.onBluetooth)
        tile.state = if (connected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    companion object {
        /** Ask the system to redraw the tile (connection changed). */
        fun refresh(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, SendTileService::class.java)) }
        }
    }

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
