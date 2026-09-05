package io.github.sneedster.harmonicast

/** Session history kept outside the Media3 timeline and the shared request queue. */
internal class PlaybackHistory<T>(private val capacity: Int = 100) {
    init { require(capacity > 0) }

    private val items = mutableListOf<T>()
    private var index = -1

    fun record(item: T) {
        // A replay (or a resume of the same item) is not a new history entry.
        if (items.getOrNull(index) == item) return
        while (items.lastIndex > index) items.removeAt(items.lastIndex)
        items.add(item)
        if (items.size > capacity) items.removeAt(0)
        index = items.lastIndex
    }

    /** Null means restart the current song. */
    fun previous(positionMs: Long, forcePrevious: Boolean = false): T? {
        if ((!forcePrevious && positionMs > 3_000) || index <= 0) return null
        return items[--index]
    }

    /** Null means continue with the shared queue. */
    fun next(): T? = if (index < items.lastIndex) items[++index] else null
}
