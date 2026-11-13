package app.clipbridge.sync

import android.content.Context
import app.clipbridge.core.PairingInfo

/** App-private storage for the pairing (QR contents) and the on/off switch. */
object Prefs {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("clipbridge", Context.MODE_PRIVATE)

    fun pairing(ctx: Context): PairingInfo? = prefs(ctx).getString("pairing", null)?.let(PairingInfo::parse)

    fun savePairing(ctx: Context, info: PairingInfo) = prefs(ctx).edit().putString("pairing", info.uri).apply()

    fun syncEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("enabled", true)

    fun setSyncEnabled(ctx: Context, enabled: Boolean) = prefs(ctx).edit().putBoolean("enabled", enabled).apply()

    fun bluetoothDismissed(ctx: Context): Boolean = prefs(ctx).getBoolean("bluetoothDismissed", false)

    fun setBluetoothDismissed(ctx: Context) = prefs(ctx).edit().putBoolean("bluetoothDismissed", true).apply()

    /** Whether the Send to Mac toggle is in Quick Settings (Android tells the tile when it's added/removed). */
    fun tileAdded(ctx: Context): Boolean = prefs(ctx).getBoolean("tileAdded", false)
    fun setTileAdded(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("tileAdded", on).apply()
    fun switchTileAdded(ctx: Context): Boolean = prefs(ctx).getBoolean("switchTileAdded", false)
    fun setSwitchTileAdded(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("switchTileAdded", on).apply()
    fun tileDismissed(ctx: Context): Boolean = prefs(ctx).getBoolean("tileDismissed", false)
    fun setTileDismissed(ctx: Context) = prefs(ctx).edit().putBoolean("tileDismissed", true).apply()

    /** The Mac is linked as an Android companion device (background privileges). */
    fun companionLinked(ctx: Context): Boolean = prefs(ctx).getBoolean("companionLinked", false)
    fun setCompanionLinked(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("companionLinked", on).apply()
    fun companionDismissed(ctx: Context): Boolean = prefs(ctx).getBoolean("companionDismissed", false)
    fun setCompanionDismissed(ctx: Context) = prefs(ctx).edit().putBoolean("companionDismissed", true).apply()

    fun historyEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("historyEnabled", true)
    fun setHistoryEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("historyEnabled", on).apply()
    fun featuresSeen(ctx: Context): Boolean = prefs(ctx).getBoolean("featuresSeen", false)
    fun setFeaturesSeen(ctx: Context) = prefs(ctx).edit().putBoolean("featuresSeen", true).apply()
    fun historyFromMac(ctx: Context): Boolean = prefs(ctx).getBoolean("historyFromMac", false)
    fun setHistoryFromMac(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("historyFromMac", on).apply()
    fun historyMaxItems(ctx: Context): Int = prefs(ctx).getInt("historyMaxItems", 50)
    fun setHistoryMaxItems(ctx: Context, n: Int) = prefs(ctx).edit().putInt("historyMaxItems", n).apply()
    fun historyMaxDays(ctx: Context): Int = prefs(ctx).getInt("historyMaxDays", 7)
    fun setHistoryMaxDays(ctx: Context, n: Int) = prefs(ctx).edit().putInt("historyMaxDays", n).apply()
    /** Epoch millis when a timed pause ends; 0 = no timed pause. */
    fun pausedUntil(ctx: Context): Long = prefs(ctx).getLong("pausedUntil", 0L)
    fun setPausedUntil(ctx: Context, until: Long) = prefs(ctx).edit().putLong("pausedUntil", until).apply()

    fun bluetoothAsked(ctx: Context): Boolean = prefs(ctx).getBoolean("bluetoothAsked", false)

    fun setBluetoothAsked(ctx: Context) = prefs(ctx).edit().putBoolean("bluetoothAsked", true).apply()
}
