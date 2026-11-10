package app.clipbridge.files

import app.clipbridge.core.Message
import java.io.File

/**
 * The phone's view of the Mac's current file offer, plus at most one fetched copy of it in [dir].
 * The copy is usable only while the offer is current and < 10 minutes old, and is deleted when the offer
 * is cleared, replaced or expires. (Files the user saves to Downloads are separate and never touched.)
 */
class OfferCache(private val dir: File, private val lifetimeMs: Long = 10 * 60_000, private val now: () -> Long) {
    private var offers: List<Message.Offer> = emptyList()
    private var receivedAt = 0L
    private val cached = mutableMapOf<String, File>()

    init { dir.mkdirs() }

    /** Returns false if [newOffer] is the offer we already hold (e.g. re-sent after a reconnect). */
    @Synchronized fun set(newOffer: Message.Offer): Boolean = setAll(listOf(newOffer))

    /** Several files copied together. False if it's the same set we already hold. */
    @Synchronized fun setAll(newOffers: List<Message.Offer>): Boolean {
        if (newOffers.isEmpty()) { clear(); return true }
        if (currentAll().map { it.id } == newOffers.map { it.id }) return false
        dropCopy()
        offers = newOffers
        receivedAt = now()
        return true
    }

    @Synchronized fun clear() {
        dropCopy()
        offers = emptyList()
    }

    /** All live offered files (empty if none / expired — expired copies are deleted). */
    @Synchronized fun currentAll(): List<Message.Offer> {
        if (offers.isEmpty()) return emptyList()
        if (now() - receivedAt >= lifetimeMs) { clear(); return emptyList() }
        return offers
    }

    /** The first live offered file (the only one for a single-file copy). */
    @Synchronized fun current(): Message.Offer? = currentAll().firstOrNull()

    /** The live offer with this id, if any. */
    @Synchronized fun offer(id: String): Message.Offer? = currentAll().firstOrNull { it.id == id }

    @Synchronized fun msSinceOffer(): Long? = if (offers.isEmpty()) null else now() - receivedAt

    /** Keep [file] as the fetched copy of offer [id]. If [id] is no longer current, the file is deleted. */
    @Synchronized fun store(id: String, file: File): Boolean {
        if (offer(id) == null) { file.delete(); return false }
        val previous = cached[id]
        if (previous != null && previous != file) previous.delete()  // only the previous copy — never the file being stored
        cached[id] = file
        return true
    }

    @Synchronized fun cachedFile(id: String): File? =
        if (offer(id) != null) cached[id]?.takeIf { it.exists() } else null

    /** Folder fetches for the current offer should be written to. */
    val fetchDir: File get() = dir

    @Synchronized fun purgeAll() {
        cached.clear()
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun dropCopy() {
        cached.values.forEach { it.delete() }
        cached.clear()
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }
}
