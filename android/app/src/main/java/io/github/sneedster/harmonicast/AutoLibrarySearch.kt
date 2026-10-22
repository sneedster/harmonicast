package io.github.sneedster.harmonicast

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay

/** One bounded, indexed search snapshot per browser, shared by count and page reads. */
internal class AutoLibrarySearch(
    private val scope: CoroutineScope,
    private val library: () -> MusicLibrary,
    private val debounceMillis: Long = 300,
) {
    private data class Request(val query: String, val library: MusicLibrary, val songs: Deferred<List<Song>>)
    private var current: Request? = null

    fun search(query: String): Deferred<List<Song>> {
        val term = query.trim()
        val source = library()
        current?.takeIf { it.query == term && it.library === source && !it.songs.isCancelled }?.let { return it.songs }
        clear()
        val songs = scope.async {
            delay(debounceMillis)
            // The all-tracks OR filter can time out on large Plex libraries.
            // Use the same indexed artist/album expansion as voice playback.
            if (term.isEmpty()) emptyList() else source.search(term).take(40)
        }
        current = Request(term, source, songs)
        return songs
    }

    suspend fun page(query: String, page: Int, pageSize: Int): List<Song> {
        require(page >= 0 && pageSize > 0)
        val offset = Math.multiplyExact(page, pageSize)
        // The legacy bridge may fetch a superseded prefix after its empty notification.
        if (current?.let { it.query != query.trim() } == true) return emptyList()
        val request = current?.takeIf { it.library === library() }
        // A failed count lookup must report the same failure, not run it again for the page.
        return (request?.songs ?: search(query)).await().drop(offset).take(pageSize)
    }

    fun clear() {
        current?.songs?.cancel()
        current = null
    }
}
