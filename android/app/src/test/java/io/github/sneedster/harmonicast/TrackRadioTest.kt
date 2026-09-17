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
    @Test fun wideningCountsDistinctSongsAndStopsAtTen() = runBlocking {
        val calls = mutableListOf<Double>()
        val selected = radioBatch(current, emptyList(), startDistance = 0.10) { distance ->
            calls += distance
            val count = if (distance < 0.15) 4 else 12
            (1..count).flatMap { n -> (1..4).map { copy -> Song("$n-$copy", "Track $n", "Artist") } }
        }
        assertEquals(listOf(0.10, 0.15), calls)
        assertEquals(12, selected.size)
    }

    @Test fun wideningHasCeilingAndRestartsForEachBatch() = runBlocking {
        val calls = mutableListOf<Double>()
        repeat(2) {
            val selected = radioBatch(current, emptyList()) { distance ->
                calls += distance
                listOf(Song("one", "Track", "Artist"))
            }
            assertEquals(1, selected.size)
        }
        assertEquals(listOf(0.25, 0.30, 0.35, 0.25, 0.30, 0.35), calls)
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
