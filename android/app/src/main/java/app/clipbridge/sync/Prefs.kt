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
}
