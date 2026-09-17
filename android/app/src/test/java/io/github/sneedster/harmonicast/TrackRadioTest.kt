package io.github.sneedster.harmonicast

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackRadioTest {
    private val current = Song("seed", "Seed", "Artist")

    @Test fun fourAlbumCopiesProduceOneSuggestionWithoutPadding() {
        val copies = (1..4).map { Song("copy-$it", "Track", "Artist", "Album $it") }
        assertEquals(listOf(copies.first()), distinctRadioTracks(current, emptyList(), copies))
    }

    @Test fun excludesCurrentAndQueuedSongsAcrossIdsAndTagFormatting() {
        val queued = Song("queued", "Don't Stop", "Some Artist")
        val candidates = listOf(
            current.copy(id = "seed-copy", album = "Compilation"),
            queued.copy(id = "queued-copy", title = " DON’T   STOP ", artist = "some artist"),
            Song("new", "New track", "Artist"),
        )
        assertEquals(listOf(candidates.last()), distinctRadioTracks(current, listOf(queued), candidates))
    }

    @Test fun retainsSuggestionOrderCoversAndExplicitVersions() {
        val candidates = listOf(
            Song("one", "Track", "Artist"),
            Song("two", "Track (Live)", "Artist"),
            Song("three", "Track (Remix)", "Artist"),
            Song("four", "Track", "Another Artist"),
        )
        assertEquals(candidates, distinctRadioTracks(current, emptyList(), candidates))
    }

    @Test fun missingTagsUseIdsAndRejectedAliasesCannotReturn() {
        val blank = Song("blank", "", "")
        val candidates = listOf(blank, blank, blank.copy(id = "other"), current.copy(id = "alias"),
            Song("alias", "Changed metadata", "Artist"))
        assertEquals(listOf(blank, blank.copy(id = "other")), distinctRadioTracks(current, emptyList(), candidates))
    }

    @Test fun repeatedRequestAddsNothingAndEmptyResponseStaysEmpty() {
        val candidates = listOf(Song("one", "Track", "Artist"))
        val first = distinctRadioTracks(current, emptyList(), candidates)
        assertEquals(emptyList<Song>(), distinctRadioTracks(current, first, candidates))
        assertEquals(emptyList<Song>(), distinctRadioTracks(current, emptyList(), emptyList()))
    }
}
