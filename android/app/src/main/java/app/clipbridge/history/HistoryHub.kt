package app.clipbridge.history

import android.content.Context
import app.clipbridge.sync.Prefs
import java.io.File

/** The one history store for this process (the app runs in a single process). */
object HistoryHub {
    @Volatile private var store: HistoryStore? = null

    fun store(ctx: Context): HistoryStore = store ?: synchronized(this) {
        store ?: HistoryStore(
            File(ctx.applicationContext.filesDir, "history.bin"), KeystoreCipher(),
            limits = HistoryLimits.from(Prefs.historyMaxItems(ctx), Prefs.historyMaxDays(ctx)),
            enabled = Prefs.historyEnabled(ctx),
            includeFromMac = Prefs.historyFromMac(ctx),
        ).also { store = it }
    }
}
