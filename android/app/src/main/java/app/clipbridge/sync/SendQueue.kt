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
 */
class SendQueue(
    private val clock: () -> Long,
    private val ttlMs: Long = 3_600_000,
    private val maxFiles: Int = 20,
    private val maxBytes: Long = 200L * 1024 * 1024,
    private val discard: (File) -> Unit = { it.delete() },
) {
    data class Pending(val clip: QueuedClip?, val files: List<OutgoingFile>)

    private class Stamped<T>(val item: T, val at: Long, val size: Long = 0)

    private var clip: Stamped<QueuedClip>? = null
    private val files = ArrayList<Stamped<OutgoingFile>>()
    private var bytes = 0L

    val count: Int get() = synchronized(this) { expire(); (if (clip != null) 1 else 0) + files.size }
    val isEmpty: Boolean get() = count == 0

    fun putClip(clip: QueuedClip) = synchronized(this) {
        discardClip()
        this.clip = Stamped(clip, clock())
    }

    /** Returns how many of [incoming] were accepted; the rest are deleted. */
    fun addFiles(incoming: List<OutgoingFile>): Int = synchronized(this) {
        expire()
        var accepted = 0
        for (f in incoming) {
            val size = f.file.length()
            if (files.size >= maxFiles || bytes + size > maxBytes) {
                discard(f.file)
            } else {
                files.add(Stamped(f, clock(), size))
                bytes += size
                accepted++
            }
        }
        accepted
    }

    /** Takes everything still valid and empties the queue. */
    fun drain(): Pending = synchronized(this) {
        expire()
        val p = Pending(clip?.item, files.map { it.item })
        clip = null
        files.clear()
        bytes = 0
        p
    }

    /** Drops everything and deletes the files. */
    fun clear() = synchronized(this) {
        discardClip()
        files.forEach { discard(it.item.file) }
        files.clear()
        bytes = 0
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
