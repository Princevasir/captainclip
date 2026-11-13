package app.clipbridge.sync

import java.io.File

/** The latest clipboard copy made while the Mac was unreachable. */
sealed class QueuedClip {
    data class Text(val text: String, val html: String?) : QueuedClip()
    data class Image(val file: OutgoingFile, val key: String) : QueuedClip()
}

/**
 * Copies kept while away: at most one clip (the newest wins) plus a bounded, ordered list of files.
 * Items expire [ttlMs] after they were queued; anything dropped has its file deleted via [discard].
 * Items that failed to send go back with [requeueClip]/[requeueFile] and keep their original queue time.
 */
class SendQueue(
    private val clock: () -> Long,
    private val ttlMs: Long = 3_600_000,
    private val maxFiles: Int = 20,
    private val maxBytes: Long = 200L * 1024 * 1024,
    private val discard: (File) -> Unit = { it.delete() },
) {
    /** What [drain] took. The times and clip version let failed items go back unchanged. */
    data class Pending(
        val clip: QueuedClip?,
        val files: List<OutgoingFile>,
        val clipAt: Long = 0,
        val filesAt: List<Long> = emptyList(),
        val filesOrder: List<Long> = emptyList(),
        val clipVersion: Long = 0,
        val epoch: Long = 0,
    )

    private class Stamped<T>(val item: T, val at: Long, val size: Long = 0, val order: Long = 0)

    private var clip: Stamped<QueuedClip>? = null
    private val files = ArrayList<Stamped<OutgoingFile>>()
    private var bytes = 0L
    /** Bumped whenever a newer clip exists (queued or sent directly): an older one must never follow it. */
    private var clipVersion = 0L
    private var nextOrder = 0L
    /** Bumped by [clear] (re-pairing): anything drained before it must never come back. */
    private var epoch = 0L

    val count: Int get() = synchronized(this) { expire(); (if (clip != null) 1 else 0) + files.size }
    val isEmpty: Boolean get() = count == 0

    fun putClip(clip: QueuedClip) = synchronized(this) {
        discardClip()
        clipVersion++
        this.clip = Stamped(clip, clock())
    }

    /** A newer clip went straight to the Mac: the queued one (and any drained one in flight) is stale. */
    fun supersedeClip() = synchronized(this) {
        discardClip()
        clipVersion++
    }

    /** False once a newer clip was queued or sent after [p] was drained. */
    fun isLatestClip(p: Pending): Boolean = synchronized(this) { p.clipVersion == clipVersion }

    /** Returns how many of [incoming] were accepted; the rest are deleted. */
    fun addFiles(incoming: List<OutgoingFile>): Int = synchronized(this) {
        expire()
        var accepted = 0
        for (f in incoming) {
            val size = f.file.length()
            if (files.size >= maxFiles || bytes + size > maxBytes) {
                discard(f.file)
            } else {
                files.add(Stamped(f, clock(), size, nextOrder++))
                bytes += size
                accepted++
            }
        }
        accepted
    }

    /**
     * Takes everything still valid. Files (and a clipboard image) larger than [maxFileBytes] stay queued, e.g.
     * until a Wi-Fi connection when Bluetooth can't carry them.
     */
    fun drain(maxFileBytes: Long = Long.MAX_VALUE): Pending = synchronized(this) {
        expire()
        val c = clip?.takeIf { (it.item as? QueuedClip.Image)?.file?.file?.length()?.let { n -> n <= maxFileBytes } ?: true }
        val taken = files.filter { it.size <= maxFileBytes }
        val p = Pending(c?.item, taken.map { it.item }, c?.at ?: 0, taken.map { it.at }, taken.map { it.order }, clipVersion, epoch)
        if (c != null) clip = null
        files.removeAll(taken.toSet())
        bytes -= taken.sumOf { it.size }
        p
    }

    /**
     * [p]'s clip failed to send: back in the queue, unless a newer clip exists, it has expired, or the queue was
     * cleared since [p] was drained. Returns true if it was kept (otherwise its image is deleted).
     */
    fun requeueClip(p: Pending): Boolean = synchronized(this) {
        val c = p.clip ?: return@synchronized false
        val keep = clip == null && p.epoch == epoch && p.clipVersion == clipVersion && p.clipAt >= clock() - ttlMs
        if (keep) clip = Stamped(c, p.clipAt) else (c as? QueuedClip.Image)?.let { discard(it.file.file) }
        keep
    }

    /**
     * [file] (one of [p]'s files) failed to send: back in its place in the queue, unless expired, over the limits,
     * or the queue was cleared since [p] was drained. Returns true if it was kept (otherwise it is deleted).
     */
    fun requeueFile(p: Pending, file: OutgoingFile): Boolean = synchronized(this) {
        val i = p.files.indexOf(file)
        val at = p.filesAt.getOrNull(i) ?: clock()
        val order = p.filesOrder.getOrNull(i) ?: nextOrder++
        val size = file.file.length()
        if (p.epoch != epoch || at < clock() - ttlMs || files.size >= maxFiles || bytes + size > maxBytes) {
            discard(file.file)
            return@synchronized false
        }
        val index = files.indexOfFirst { it.order > order }.let { if (it < 0) files.size else it }
        files.add(index, Stamped(file, at, size, order))
        bytes += size
        true
    }

    /** Drops everything and deletes the files; items drained before this can't be requeued. */
    fun clear() = synchronized(this) {
        discardClip()
        files.forEach { discard(it.item.file) }
        files.clear()
        bytes = 0
        epoch++
        clipVersion++
    }

    private fun expire() {
        val cutoff = clock() - ttlMs
        clip?.let { if (it.at < cutoff) discardClip() }
        val it = files.iterator()
        while (it.hasNext()) {
            val s = it.next()
            if (s.at < cutoff) { discard(s.item.file); bytes -= s.size; it.remove() }
        }
    }

    private fun discardClip() {
        (clip?.item as? QueuedClip.Image)?.let { discard(it.file.file) }
        clip = null
    }
}

/** The one send queue for this process. */
object SendQueueHub {
    val queue = SendQueue(clock = System::currentTimeMillis)
}
