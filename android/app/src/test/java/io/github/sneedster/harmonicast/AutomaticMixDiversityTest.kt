package io.github.sneedster.harmonicast

import org.junit.Assert.assertEquals
import org.junit.Test

class AutomaticMixDiversityTest {
    private fun track(id: String, album: String) = Song(
        id, id, "Artist", album = album, albumArtist = "Artist", rating = 8.0,
    )

    @Test fun strictRatedPoolUsesAnUnseenFallbackAlbumBeforeRepeating() {
        val a1 = track("a1", "A")
        val a2 = track("a2", "A")
        val b1 = track("b1", "B")
        val selected = chooseJukeboxTracks(
            PlexJukeboxPools(listOf(a1, a2), emptyList(), listOf(a1, a2, b1)),
            2, 10, 0,
        ) { 0.0 }.songs
        assertEquals(listOf("A", "B"), selected.map { it.album })
    }

    @Test fun successiveSingleTrackRefillsAvoidAnAlbumAlreadyInTheQueue() {
        val tracks = listOf(track("a1", "A"), track("a2", "A"), track("b1", "B"))
        val first = chooseJukeboxTracks(PlexJukeboxPools(tracks, emptyList(), tracks), 1, 10, 0) { 0.0 }
        // A rolling top-up excludes queued IDs and carries the listening window
        // into a fresh picker invocation with needed=1 and the persisted cursor.
        val remaining = tracks.filterNot { candidate -> first.songs.any { it.id == candidate.id } }
        val second = chooseJukeboxTracks(
            PlexJukeboxPools(remaining, emptyList(), remaining), 1, 10, first.nextMixIndex,
            existingSongs = first.songs,
        ) { 0.0 }
        assertEquals(listOf("A", "B"), (first.songs + second.songs).map { it.album })
    }

    @Test fun mixedPoolUsesAnotherAlbumBeforeRepeatingThePrimaryAlbum() {
        val a1 = track("a1", "A")
        val a2 = track("a2", "A")
        val b1 = track("b1", "B").copy(rating = null)
        val selected = chooseJukeboxTracks(
            PlexJukeboxPools(listOf(a1, a2), listOf(b1), listOf(a1, a2, b1)), 2, 8, 0,
        ) { 0.0 }.songs
        assertEquals(listOf("A", "B"), selected.map { it.album })
    }

    @Test fun unseenFallbackAlbumPreservesTheRequestedRatingCategory() {
        val a1 = track("a1", "A")
        val a2 = track("a2", "A")
        val b1 = track("b1", "B").copy(rating = null)
        val c1 = track("c1", "C")
        val selected = chooseJukeboxTracks(
            PlexJukeboxPools(listOf(a1, a2), listOf(b1), listOf(a1, a2, b1, c1)), 2, 8, 0,
        ) { 0.0 }.songs
        assertEquals(listOf("A", "C"), selected.map { it.album })
    }

    @Test fun singleAlbumStillFillsWhenTheListeningWindowAlreadyContainsIt() {
        val tracks = listOf(track("a1", "A"), track("a2", "A"))
        val selected = chooseJukeboxTracks(
            PlexJukeboxPools(tracks, emptyList(), tracks), 2, 10, 0,
            existingSongs = listOf(track("playing", "A")),
        ) { 0.0 }.songs
        assertEquals(listOf("a1", "a2"), selected.map { it.id })
    }
}
