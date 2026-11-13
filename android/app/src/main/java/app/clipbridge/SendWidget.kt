package app.clipbridge

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import app.clipbridge.capture.GhostActivity
import app.clipbridge.sync.Prefs
import app.clipbridge.sync.SyncLoop
import app.clipbridge.sync.SyncService

/** Home-screen widget: connection status and a one-tap Send to Mac (sends the current clipboard). */
class SendWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) = render(ctx, manager, ids)

    companion object {
        /** Redraw every placed widget (connection changed). */
        fun refresh(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx) ?: return
            val ids = runCatching { manager.getAppWidgetIds(ComponentName(ctx, SendWidget::class.java)) }.getOrNull() ?: return
            if (ids.isNotEmpty()) render(ctx, manager, ids)
        }

        private fun pausedLine(ctx: Context): String? = UiText.pausedLine(Prefs.pausedUntil(ctx), System.currentTimeMillis()) {
            android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(it))
        }

        private fun render(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
            val (title, subtitle) = UiText.connectionLine(
                paired = Prefs.pairing(ctx) != null, syncOn = Prefs.syncEnabled(ctx),
                connected = SyncService.state == SyncLoop.Status.CONNECTED, macName = SyncService.macName,
                bluetooth = SyncService.onBluetooth, pausedLine = pausedLine(ctx))
            // While a timed pause runs, a tap on Send resumes instead of sending.
            val send = if (SyncService.isPaused(ctx)) {
                PendingIntent.getBroadcast(ctx, 23, Intent(ctx, ResumeReceiver::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            } else PendingIntent.getActivity(ctx, 21, GhostActivity.readIntent(ctx, announce = true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val open = PendingIntent.getActivity(ctx, 22, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val views = RemoteViews(ctx.packageName, R.layout.widget_send).apply {
                setTextViewText(R.id.widgetTitle, title)
                setTextViewText(R.id.widgetSubtitle, subtitle)
                setOnClickPendingIntent(R.id.widgetSend, send)
                setOnClickPendingIntent(R.id.widgetRoot, open)
            }
            manager.updateAppWidget(ids, views)
        }
    }
}

/** Widget tap while paused: resume syncing, then redraw. */
class ResumeReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        SyncService.resume(ctx)
        SendWidget.refresh(ctx)
        app.clipbridge.capture.ConnectionTileService.refresh(ctx)
    }
}
