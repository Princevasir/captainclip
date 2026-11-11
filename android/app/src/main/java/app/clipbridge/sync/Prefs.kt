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

    fun historyEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("historyEnabled", true)
    fun setHistoryEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("historyEnabled", on).apply()
    fun historyMaxItems(ctx: Context): Int = prefs(ctx).getInt("historyMaxItems", 50)
    fun setHistoryMaxItems(ctx: Context, n: Int) = prefs(ctx).edit().putInt("historyMaxItems", n).apply()
    fun historyMaxDays(ctx: Context): Int = prefs(ctx).getInt("historyMaxDays", 7)
    fun setHistoryMaxDays(ctx: Context, n: Int) = prefs(ctx).edit().putInt("historyMaxDays", n).apply()

    fun bluetoothAsked(ctx: Context): Boolean = prefs(ctx).getBoolean("bluetoothAsked", false)

    fun setBluetoothAsked(ctx: Context) = prefs(ctx).edit().putBoolean("bluetoothAsked", true).apply()
}
