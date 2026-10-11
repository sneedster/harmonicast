package io.github.sneedster.harmonicast

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.coroutines.runBlocking

class TrackRadioTest {
    @Test fun distanceSettingPersistsClampsAndRecoversInvalidStoredValues() {
        val values = mutableMapOf<String, String>()
        val storage = object : ProfileStorage {
            override fun read(key: String) = values[key]
            override fun write(updates: Map<String, String>) { values.putAll(updates) }
        }
        assertEquals(0.25, TrackRadioSettings(storage).distance, 0.0)
        TrackRadioSettings(storage).distance = 0.15
        assertEquals(0.15, TrackRadioSettings(storage).distance, 0.0)
        TrackRadioSettings(storage).distance = 1.0
        assertEquals(0.30, TrackRadioSettings(storage).distance, 0.0)
        values["local.radioDistance"] = "NaN"
        assertEquals(0.25, TrackRadioSettings(storage).distance, 0.0)
    }
    private fun candidate(id: String, artist: String, distance: Double, album: String = id) = ArtistRadioCandidate(
        Song(id, "Track $id", artist, album, streamUri = "https://plex/part/$id", albumArtist = artist), distance,
    )

    @Test fun artistAndSoundAreBothRequiredWithoutWideningOrUnrelatedFallback() {
        val close = candidate("close", "Related", 0.10)
        val far = candidate("far", "Related", 0.26)
        val unrelated = candidate("unrelated", "Other", 0.01)
        val missing = candidate("missing", "Related", Double.NaN)
        val unplayable = candidate("unplayable", "Related", 0.05).let { it.copy(song = it.song.copy(streamUri = null)) }
        assertEquals(listOf(close.song), artistRadioBatch(current, emptyList(), setOf("Artist", "related"),
            listOf(far, unrelated, missing, unplayable, close), 0.25))
        assertEquals(emptyList<Song>(), artistRadioBatch(current, emptyList(), setOf("Artist", "Related"),
            listOf(far, unrelated), 0.25))
    }

    @Test fun selectionBalancesArtistsAndAlbumsWhileKeepingClosestSongsWithinEachArtist() {
        val candidates = listOf(candidate("a1", "Artist", 0.01, "A"), candidate("a2", "Artist", 0.02, "A"),
            candidate("a3", "Artist", 0.05, "B"), candidate("b1", "Related", 0.08, "C"), candidate("b2", "Related", 0.09, "D"))
        val selected = artistRadioBatch(current, emptyList(), setOf("Artist", "Related"), candidates, 0.25)
        assertEquals(listOf("b1", "a1", "b2", "a3", "a2"), selected.map { it.id })
        assertEquals(selected.size, selected.map { it.id }.distinct().size)
    }

    @Test fun selectionExcludesRecentAliasesAndCapsTheBatchAtTwenty() {
        val recent = candidate("old", "Related", 0.10).song
        val alias = ArtistRadioCandidate(recent.copy(id = "alias"), 0.02)
        val candidates = listOf(alias) + (1..30).map { candidate("$it", "Related", 0.1) }
        val selected = artistRadioBatch(current, listOf(recent), setOf("Related"), candidates, 0.25)
        assertEquals(20, selected.size)
        assertEquals(false, selected.any { it.title == recent.title })
    }

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
