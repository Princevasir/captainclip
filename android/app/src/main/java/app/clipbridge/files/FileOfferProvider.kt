package app.clipbridge.files

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import app.clipbridge.sync.SyncService
import java.io.FileNotFoundException

/**
 * Serves the Mac's offered file behind the clipboard placeholder. Metadata (name, size, type) never touches
 * the network; content is fetched from the Mac only when a real paste target opens it (see PreviewGuard),
 * and only after the whole file has arrived and been verified.
 */
class FileOfferProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        Files.cache(context!!).purgeAll()  // nothing survives an app restart
        return true
    }

    private fun offerFor(uri: Uri) = Files.idFrom(uri)?.let { id -> Files.cache(context!!).current()?.takeIf { it.id == id } }

    override fun getType(uri: Uri): String? = offerFor(uri)?.mime.also { log("getType -> $it", uri) }

    override fun getStreamTypes(uri: Uri, mimeTypeFilter: String): Array<String>? =
        offerFor(uri)?.mime?.takeIf { PreviewGuard.matches(mimeTypeFilter, it) }?.let { arrayOf(it) }
            .also { log("getStreamTypes($mimeTypeFilter) -> ${it?.toList()}", uri) }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        Log.i(TAG, "call($method, $arg, ${extras?.keySet()}) by ${runCatching { callingPackage }.getOrNull()}")
        return null
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        log("query(projection=${projection?.toList()}, selection=$selection)", uri)
        val offer = offerFor(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            if (offer != null) addRow(columns.map { if (it == OpenableColumns.SIZE) offer.size else if (it == OpenableColumns.DISPLAY_NAME) offer.name else null })
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        log("openFile", uri)
        return open(uri, PreviewGuard.Method.OPEN_FILE, sizeHint = false, mimeFilter = null)
    }

    override fun openTypedAssetFile(uri: Uri, mimeTypeFilter: String, opts: Bundle?): AssetFileDescriptor {
        log("openTypedAssetFile($mimeTypeFilter, opts=${opts?.keySet()})", uri)
        // Gmail and My Files paste this way; previews (image of a PDF, thumbnail sizes, the keyboard) are refused.
        val sizeHint = opts?.containsKey(android.content.ContentResolver.EXTRA_SIZE) == true
        val pfd = open(uri, PreviewGuard.Method.OPEN_TYPED_ASSET, sizeHint, mimeTypeFilter)
        return AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    private fun open(uri: Uri, method: PreviewGuard.Method, sizeHint: Boolean, mimeFilter: String?): ParcelFileDescriptor {
        val cache = Files.cache(context!!)
        val offer = offerFor(uri) ?: throw FileNotFoundException("offer expired")
        val since = cache.msSinceOffer() ?: 0
        if (!PreviewGuard.allow(method, callingPackage, blockedCallers(), since, sizeHint, mimeFilter, offer.mime)) {
            Log.i(TAG, "refused ${callingPackage} (${since} ms after offer)")
            throw FileNotFoundException("not a paste")
        }
        Log.i(TAG, "serving ${offer.name} to $callingPackage")
        val file = cache.cachedFile(offer.id) ?: SyncService.fetchOffer(offer.id, cache.fetchDir).also { cache.store(offer.id, it) }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun blockedCallers(): Set<String> {
        val ime = Settings.Secure.getString(context!!.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
        return KNOWN_PREVIEWERS + listOfNotNull(ime)
    }

    private fun log(what: String, uri: Uri) = Log.i(TAG, "$what by ${runCatching { callingPackage }.getOrNull()} for $uri")

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    companion object {
        const val TAG = "ClipBridgeFiles"
        /** System clipboard UIs that show previews (Samsung + AOSP). The default keyboard is added at runtime. */
        val KNOWN_PREVIEWERS = setOf(
            "com.android.systemui",
            "com.samsung.android.honeyboard",
            "com.samsung.android.clipboarduiservice",
            "com.sec.android.app.clipboardui",
            "com.google.android.inputmethod.latin",
        )
    }
}
