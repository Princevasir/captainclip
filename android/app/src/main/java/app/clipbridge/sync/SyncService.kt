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
import app.clipbridge.core.LoopGuard
import java.util.concurrent.CountDownLatch

/**
 * Foreground service that keeps one connection to the Mac:
 * find Mac (NSD, else QR address) → connect → wait until closed → back off → repeat.
 * Clips from the Mac go straight onto the phone clipboard.
 */
class SyncService : Service() {
    companion object {
        const val ACTION_START = "app.clipbridge.START"
        const val ACTION_STOP = "app.clipbridge.STOP"
        const val ACTION_SEND = "app.clipbridge.SEND"
        const val EXTRA_TEXT = "text"
        private const val CHANNEL = "sync"
        private const val NOTIFICATION_ID = 1

        /** Human-readable status for the main screen. */
        @Volatile var status: String = "Stopped"
            private set
        var onStatusChanged: (() -> Unit)? = null

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, SyncService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, SyncService::class.java).setAction(ACTION_STOP))
        }

        /** Send text to the Mac (used by the ghost screen, tile, notification and share target). */
        fun send(ctx: Context, text: String) {
            ctx.startService(Intent(ctx, SyncService::class.java).setAction(ACTION_SEND).putExtra(EXTRA_TEXT, text))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val loopGuard = LoopGuard()
    private val backoff = Backoff()
    private val wake = Object()
    @Volatile private var running = false
    @Volatile private var connection: Connection? = null
    private var worker: Thread? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { shutdown(); return START_NOT_STICKY }
            ACTION_SEND -> intent.getStringExtra(EXTRA_TEXT)?.let(::sendToMac)
        }
        ensureRunning()
        return START_STICKY
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun ensureRunning() {
        if (running) return
        running = true
        createChannel()
        val notification = buildNotification("Looking for your Mac…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        watchNetwork()
        worker = Thread(::loop, "clipbridge-sync").apply { isDaemon = true; start() }
    }

    private fun loop() {
        val locator = MacLocator(this)
        while (running) {
            val info = Prefs.pairing(this)
            if (info == null) { setStatus("Not paired — scan the Mac's QR code"); sleepOrWake(30_000); continue }
            setStatus("Looking for your Mac…")
            val (host, port) = locator.find(info) ?: (info.host to info.port)
            val closed = CountDownLatch(1)
            val conn = Connection(host, port, info.key, Build.MODEL, object : Connection.Listener {
                override fun onConnected() { backoff.reset(); setStatus("Connected to Mac") }
                override fun onClip(text: String) = receivedFromMac(text)
                override fun onClosed() = closed.countDown()
            })
            connection = conn
            conn.start()
            closed.await()
            connection = null
            if (!running) break
            setStatus("Mac not reachable — retrying")
            sleepOrWake(backoff.nextDelayMs())
        }
    }

    private fun receivedFromMac(text: String) {
        loopGuard.markReceived(text)
        main.post {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("ClipBridge", text))
        }
    }

    private fun sendToMac(text: String) {
        if (!Prefs.syncEnabled(this) || !loopGuard.shouldSend(text)) return
        val conn = connection
        if (conn != null && conn.send(text)) loopGuard.markSent(text)
    }

    /** Wake the reconnect loop early when Wi-Fi comes back. */
    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                backoff.reset()
                synchronized(wake) { wake.notifyAll() }
            }
        }
        cm.registerDefaultNetworkCallback(callback)
        networkCallback = callback
    }

    private fun sleepOrWake(ms: Long) {
        synchronized(wake) { try { wake.wait(ms) } catch (_: InterruptedException) {} }
    }

    private fun shutdown() {
        running = false
        connection?.close()
        synchronized(wake) { wake.notifyAll() }
        networkCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        networkCallback = null
        setStatus("Stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun setStatus(text: String) {
        status = text
        if (running) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
        main.post { onStatusChanged?.invoke() }
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL, "Clipboard sync", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("ClipBridge")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
