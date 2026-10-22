package io.github.sneedster.harmonicast

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AutoLibrarySearchTest {
    private class Library : MusicLibrary {
        val calls = mutableListOf<String>()
        override suspend fun search(query: String): List<Song> {
            calls += query
            return (0 until 60).map { Song("$query:$it", "Track $it", query) }
        }
        override suspend fun track(id: String): Song? = null
        override suspend fun artist(query: String): LibraryArtistBrowse? = null
        override suspend fun discovery(song: Song): ArtistDiscovery = error("unused")
        override fun streamUrl(song: Song) = "https://plex.invalid/audio"
        override fun artworkUrl(song: Song): String? = null
        override suspend fun searchPage(query: String, offset: Int, limit: Int): TrackSearchPage =
            error("Auto must not use the slow full-section OR scan")
    }

    @Test fun typingSupersedesPrefixesAndCountAndPagesShareOneBoundedIndexedLookup() = runBlocking {
        val library = Library()
        val search = AutoLibrarySearch(this, { library }, debounceMillis = 1)
        val prefix = search.search("alina")
        val final = search.search("alina baraz")
        assertTrue(prefix.isCancelled)
        assertEquals(40, final.await().size)
        assertEquals(40, search.page("alina baraz", 0, Int.MAX_VALUE).size)
        assertEquals("Track 20", search.page("alina baraz", 1, 20).first().title)
        assertTrue(search.page("alina", 0, Int.MAX_VALUE).isEmpty())
        assertEquals(listOf("alina baraz"), library.calls)
    }

    @Test fun sourceChangeInvalidatesSnapshotAndClearCancelsPendingWork() = runBlocking {
        var library = Library()
        val search = AutoLibrarySearch(this, { library }, debounceMillis = 1)
        search.search("artist").await()
        val previous = library
        library = Library()
        search.page("artist", 0, 20)
        assertEquals(listOf("artist"), previous.calls)
        assertEquals(listOf("artist"), library.calls)
        val pending = search.search("another")
        search.clear()
        assertTrue(pending.isCancelled)
        assertEquals(listOf("artist"), library.calls)
    }
}
