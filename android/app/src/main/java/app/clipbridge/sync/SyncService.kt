package app.clipbridge.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import app.clipbridge.MainActivity
import app.clipbridge.capture.GhostActivity
import app.clipbridge.capture.OwnWrites

/**
 * Foreground service hosting the [SyncLoop]. Clips from the Mac go straight onto the phone clipboard.
 * Only START (when sync is enabled and paired) or RECONNECT bring it up; sending never does.
 */
class SyncService : Service() {
    companion object {
        private const val ACTION_START = "app.clipbridge.START"
        private const val ACTION_STOP = "app.clipbridge.STOP"
        private const val ACTION_RECONNECT = "app.clipbridge.RECONNECT"
        private const val CHANNEL = "sync"
        private const val NOTIFICATION_ID = 1

        @Volatile private var instance: SyncService? = null

        /** Human-readable status for the main screen. */
        @Volatile var status: String = "Stopped"
            private set
        var onStatusChanged: (() -> Unit)? = null

        fun start(ctx: Context) = command(ctx, ACTION_START, foreground = true)
        fun stop(ctx: Context) { if (instance != null) command(ctx, ACTION_STOP, foreground = false) }

        /** After re-pairing: (re)start and connect with the new pairing right away. */
        fun reconnect(ctx: Context) = command(ctx, ACTION_RECONNECT, foreground = true)

        /**
         * Send text to the Mac, in-process (no Intent, so no Binder size limit). Never starts the
         * service: if sync is stopped, nothing is sent. Returns true if queued for the Mac.
         */
        fun send(text: String): Boolean = instance?.loop?.send(text) ?: false

        private fun command(ctx: Context, action: String, foreground: Boolean) {
            val intent = Intent(ctx, SyncService::class.java).setAction(action)
            runCatching { if (foreground) ctx.startForegroundService(intent) else ctx.startService(intent) }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var loop: SyncLoop? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { shutdown(); return START_NOT_STICKY }
            ACTION_RECONNECT -> if (ensureRunning()) loop?.reconnect()
            else -> {  // START, or a sticky restart with a null intent
                if (!Prefs.syncEnabled(this) || Prefs.pairing(this) == null) { shutdown(); return START_NOT_STICKY }
                ensureRunning()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    /** Returns false if Android refused to let us run in the foreground. */
    private fun ensureRunning(): Boolean {
        if (loop != null) return true
        createChannel()
        val started = runCatching {
            val notification = buildNotification("Looking for your Mac…")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.isSuccess
        if (!started) { stopSelf(); return false }

        val newLoop = SyncLoop(
            pairing = { Prefs.pairing(this) },
            locate = { MacLocator(this).find(it) },
            device = Build.MODEL,
            callbacks = object : SyncLoop.Callbacks {
                override fun onStatus(status: SyncLoop.Status) = setStatus(describe(status))
                override fun onClip(text: String) = writeClipboard(text)
            },
        )
        loop = newLoop
        instance = this
        watchNetwork()
        newLoop.start()
        return true
    }

    private fun writeClipboard(text: String) {
        main.post {
            runCatching {
                OwnWrites.markNow()  // so copy detection ignores the system "Copied" overlay this causes
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ClipBridge", text))
            }
        }
    }

    private fun describe(status: SyncLoop.Status) = when (status) {
        SyncLoop.Status.STOPPED -> "Stopped"
        SyncLoop.Status.NOT_PAIRED -> "Not paired — scan the Mac's QR code"
        SyncLoop.Status.SEARCHING -> "Looking for your Mac…"
        SyncLoop.Status.CONNECTED -> "Connected to Mac"
        SyncLoop.Status.RETRYING -> "Mac not reachable — retrying"
        SyncLoop.Status.PAIRING_INVALID -> "Mac rejected this phone — scan the Mac's QR code again"
    }

    /** Wake the reconnect loop early when the network comes back. */
    private fun watchNetwork() {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { loop?.wake() }
        }
        runCatching { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(callback) }
        networkCallback = callback
    }

    private fun shutdown() {
        instance = null
        val old = loop
        loop = null
        networkCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        networkCallback = null
        if (old != null) Thread { old.stop() }.start()  // stop() joins the worker; keep it off the main thread
        setStatus("Stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun setStatus(text: String) {
        status = text
        if (loop != null) {
            runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text)) }
        }
        main.post { onStatusChanged?.invoke() }
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL, "Clipboard sync", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val sendNow = PendingIntent.getActivity(
            this, 2, GhostActivity.readIntent(this, announce = true), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("ClipBridge")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Send clipboard to Mac", sendNow).build())
            .build()
    }
}
