package app.clipbridge.files

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import java.io.File

/** Process-wide file-offer state shared by the sync service and the content provider. */
object Files {
    const val AUTHORITY = "app.clipbridge.files"

    @Volatile private var instance: OfferCache? = null

    fun cache(context: Context): OfferCache = instance ?: synchronized(this) {
        instance ?: OfferCache(File(context.applicationContext.cacheDir, "offers")) { SystemClock.elapsedRealtime() }
            .also { instance = it }
    }

    fun uriFor(id: String, name: String): Uri =
        Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("offer").appendPath(id).appendPath(name).build()

    /** Offer id from content://app.clipbridge.files/offer/<id>/<name>. */
    fun idFrom(uri: Uri): String? = uri.pathSegments.takeIf { it.size >= 2 && it[0] == "offer" }?.get(1)
}
