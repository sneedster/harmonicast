package io.github.sneedster.harmonicast

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AutoLibraryBrowserTest {
    private class Library : MusicLibrary {
        val calls = mutableListOf<Triple<BrowseKind, Int, String?>>()
        override suspend fun letterIndex(kind: BrowseKind) = listOf(LibraryLetter("A", 0, 40), LibraryLetter("Z", 400, 1))
        override suspend fun browse(kind: BrowseKind, order: BrowseOrder, offset: Int, parent: String?, query: String): LibraryPage {
            calls += Triple(kind, offset, parent)
            return if (kind == BrowseKind.ARTISTS) LibraryPage(listOf(LibraryEntry("artist:/1", "Artist", "", null, kind)), null)
            else LibraryPage(listOf(LibraryEntry("album:/2", "Album", "Artist", null, kind)), if (offset == 0) 40 else null)
        }
        override suspend fun albumTracks(id: String): List<Song> {
            assertEquals("album:/2", id)
            return listOf(Song("track", "Song", "Artist", "Album", streamUri = "http://local/audio"))
        }
        override suspend fun search(query: String) = emptyList<Song>()
        override suspend fun track(id: String): Song? = null
        override suspend fun artist(query: String): LibraryArtistBrowse? = null
        override suspend fun discovery(song: Song): ArtistDiscovery = error("unused")
        override fun streamUrl(song: Song) = song.streamUri.orEmpty()
        override fun artworkUrl(song: Song): String? = null
    }
    @Test fun artistsAlbumsAndMoreUseSourceScopedDataWithoutPlaybackCommands() = runBlocking {
        val library = Library(); val browser = AutoLibraryBrowser(library)
        val root = browser.children(AutoLibraryBrowser.ROOT)
        assertEquals(listOf("Recently added", "Albums", "Artists", "Recently played"), root.map { it.title })
        val letters = browser.children(root[2].id)
        assertEquals(listOf("A", "Z"), letters.map { it.title })
        assertTrue(library.calls.isEmpty())
        val artists = browser.children(letters.first().id)
        val albums = browser.children(artists.first().id)
        assertEquals("artist:/1", library.calls.last().third)
        assertEquals("Song", browser.children(albums.first().id).single().song?.title)
        val next = browser.children(albums.last().id)
        assertEquals(40, library.calls.last().second)
        assertEquals(1, next.size)
    }
    @Test fun malformedRouteFailsBeforeLibraryAccess() = runBlocking {
        val library = Library()
        try { AutoLibraryBrowser(library).children("harmonicast:library/browse/ALBUMS/TITLE/-1/"); fail("Expected rejection") }
        catch (_: IllegalArgumentException) { }
        assertTrue(library.calls.isEmpty())
    }
    @Test fun choosingLateLetterJumpsDirectlyToItsServerOffset() = runBlocking {
        val library = Library(); val browser = AutoLibraryBrowser(library)
        val root = browser.children(AutoLibraryBrowser.ROOT)
        val letters = browser.children(root[2].id)
        val rows = browser.children(letters.last().id)
        assertEquals(400, library.calls.single().second)
        assertEquals(1, rows.size)
        assertFalse(rows.any { it.title == "More…" })
    }
}
