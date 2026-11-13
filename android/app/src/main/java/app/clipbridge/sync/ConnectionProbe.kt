package app.clipbridge.sync

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import java.net.InetSocketAddress
import java.net.Socket

/** Gathers what the phone can observe about its connection right now. Blocking: call off the main thread. */
class ConnectionProbe(private val ctx: Context) {
    fun gather(): CheckFacts {
        val pairing = Prefs.pairing(ctx)
        val onWifi = onWifi()
        var visible = false
        var reachable = false
        if (pairing != null && onWifi) {
            val found = MacLocator(ctx).find(pairing, 3_000)
            visible = found != null
            val target = found ?: Prefs.lastMacAddress(ctx)
            reachable = target != null && canConnect(target.first, target.second)
        }
        val bluetoothOn = runCatching {
            ctx.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
        }.getOrDefault(false)
        return CheckFacts(
            connected = SyncService.isConnected,
            path = if (SyncService.isConnected) (if (SyncService.onBluetooth) SyncLoop.Path.BLUETOOTH else SyncLoop.Path.WIFI) else null,
            onWifi = onWifi,
            macVisible = visible,
            macReachable = reachable,
            bluetoothOn = bluetoothOn,
            bluetoothAllowed = bluetoothAllowed(),
            pairingInvalid = SyncService.state == SyncLoop.Status.PAIRING_INVALID,
            backgroundOk = Prefs.companionLinked(ctx) ||
                ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName),
            paused = SyncService.isPaused(ctx) || !Prefs.syncEnabled(ctx),
        )
    }

    private fun onWifi(): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun bluetoothAllowed(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN).all {
            ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun canConnect(host: String, port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(host, port), 2_000) }
        true
    }.getOrDefault(false)
}
