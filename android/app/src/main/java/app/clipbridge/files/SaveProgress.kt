package app.clipbridge.files

/** Which files of the current Mac set "Save all" already saved, so a retry after a failure doesn't duplicate them. */
class SaveProgress {
    private var set: List<String> = emptyList()
    private val done = mutableSetOf<String>()

    /** The ids still to save, in order. A different set (a new copy on the Mac) starts over. */
    fun remaining(ids: List<String>): List<String> {
        if (ids != set) { set = ids; done.clear() }
        return ids.filter { it !in done }
    }

    fun saved(id: String) { done += id }
}
