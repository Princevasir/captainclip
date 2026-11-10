package app.clipbridge.files

import app.clipbridge.core.Message
import java.io.File

/**
 * The phone's view of the Mac's current file offer, plus at most one fetched copy of it in [dir].
 * The copy is usable only while the offer is current and < 10 minutes old, and is deleted when the offer
 * is cleared, replaced or expires. (Files the user saves to Downloads are separate and never touched.)
 */
class OfferCache(private val dir: File, private val lifetimeMs: Long = 10 * 60_000, private val now: () -> Long) {
    private var offer: Message.Offer? = null
    private var receivedAt = 0L
    private var cached: File? = null

    init { dir.mkdirs() }

    @Synchronized fun set(newOffer: Message.Offer) {
        dropCopy()
        offer = newOffer
        receivedAt = now()
    }

    @Synchronized fun clear() {
        dropCopy()
        offer = null
    }

    /** The live offer, or null if none / expired (an expired offer's copy is deleted). */
    @Synchronized fun current(): Message.Offer? {
        val o = offer ?: return null
        if (now() - receivedAt >= lifetimeMs) { clear(); return null }
        return o
    }

    @Synchronized fun msSinceOffer(): Long? = offer?.let { now() - receivedAt }

    /** Keep [file] as the fetched copy of offer [id]. If [id] is no longer current, the file is deleted. */
    @Synchronized fun store(id: String, file: File): Boolean {
        if (current()?.id != id) { file.delete(); return false }
        if (cached != file) dropCopy()
        cached = file
        return true
    }

    @Synchronized fun cachedFile(id: String): File? =
        if (current()?.id == id) cached?.takeIf { it.exists() } else null

    /** Folder fetches for the current offer should be written to. */
    val fetchDir: File get() = dir

    @Synchronized fun purgeAll() {
        cached = null
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun dropCopy() {
        cached?.delete()
        cached = null
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }
}
