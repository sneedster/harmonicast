package io.github.sneedster.harmonicast

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.ln
import kotlin.math.roundToInt

class MusicTuningTest {
    private class Storage : ProfileStorage {
        val values = mutableMapOf<String, String>()
        var fail = false
        override fun read(key: String) = values[key]
        override fun write(values: Map<String, String>) { check(!fail) { "Disk full" }; this.values.putAll(values) }
    }

    @Test fun upgradeDefaultsPreserveConsentAndExistingMix() {
        val storage = Storage()
        storage.write(mapOf("local.automaticPlexRatings" to "true", "local.ratedTrackShare" to "3"))
        assertEquals(MusicTuning(), MusicTuningStore(storage).read())
        MusicTuningStore(storage).write(MusicTuning(0, 4, 1, 3))
        assertEquals(MusicTuning(0, 4, 1, 3), MusicTuningStore(storage).read())
        assertTrue(AutomaticPlexRatings(storage).enabled)
        assertEquals("3", storage.read("local.ratedTrackShare"))
    }

    @Test fun invalidFieldsFallBackIndividuallyAndFailedSaveRetainsValues() {
        val storage = Storage()
        storage.values[MusicTuningStore.KEY] = """{"completion":4,"skip":-1,"repeat":1.5,"selection":"3"}"""
        val store = MusicTuningStore(storage)
        assertEquals(MusicTuning(completion = 4), store.read())
        storage.values[MusicTuningStore.KEY] = "broken"
        assertEquals(MusicTuning(), store.read())
        store.write(MusicTuning(selection = 0))
        storage.fail = true
        assertTrue(runCatching { store.write(MusicTuning()) }.isFailure)
        assertEquals(MusicTuning(selection = 0), store.read())
    }

    @Test fun defaultsReproducePreviousFormulaAcrossRatingsAndEvents() {
        for (rating in listOf(null, 0.0, 1.2, 5.0, 9.9, 10.0)) {
            for (plays in listOf(-1, 0, 1, 10, 100, Int.MAX_VALUE)) {
                for (progress in listOf(-0.1, 0.0, .25, .75, .95, 1.0, 1.1)) {
                    val points = (((rating ?: 5.0) * 10).toInt()).coerceIn(0, 100)
                    val completion = (0.5 * (1 + ln(plays.coerceAtLeast(0) + 1.0))).roundToInt()
                    val skip = -(3 * (1 - progress.coerceIn(0.0, 1.0))).roundToInt()
                    assertEquals((points + completion).coerceIn(0, 100) / 10.0, adjustPersonalRating(rating, "complete", progress, plays), 0.0)
                    assertEquals((points + skip).coerceIn(0, 100) / 10.0, adjustPersonalRating(rating, "skip", progress, plays), 0.0)
                }
            }
        }
    }

    @Test fun zeroSettingsDisableEffectsAndUnknownEventsDoNotPenalize() {
        assertEquals(5.0, adjustPersonalRating(null, "complete", 1.0, 100, MusicTuning(completion = 0)), 0.0)
        assertEquals(5.0, adjustPersonalRating(null, "skip", 0.0, 100, MusicTuning(skip = 0)), 0.0)
        assertEquals(5.1, adjustPersonalRating(5.0, "complete", 1.0, 100, MusicTuning(repeat = 0)), 0.0)
        assertEquals(5.0, adjustPersonalRating(5.0, "pause", 0.0, 0), 0.0)
    }

    @Test fun strengthsAreMonotonicBoundedAndIndependent() {
        val completions = (0..4).map { adjustPersonalRating(5.0, "complete", 1.0, 10, MusicTuning(completion = it)) }
        val skips = (0..4).map { adjustPersonalRating(5.0, "skip", .25, 10, MusicTuning(skip = it)) }
        val repeats = (0..4).map { adjustPersonalRating(5.0, "complete", 1.0, 100, MusicTuning(repeat = it)) }
        assertEquals(completions.sorted(), completions)
        assertEquals(skips.sortedDescending(), skips)
        assertEquals(repeats.sorted(), repeats)
        for (step in 0..4) {
            assertEquals(4.7, adjustPersonalRating(5.0, "skip", 0.0, 0, MusicTuning(completion = step, repeat = step, selection = step)), 0.0)
            assertEquals(10.0, adjustPersonalRating(10.0, "complete", 1.0, Int.MAX_VALUE, MusicTuning(completion = step)), 0.0)
            assertEquals(0.0, adjustPersonalRating(0.0, "skip", 0.0, 0, MusicTuning(skip = step)), 0.0)
        }
    }

    @Test fun selectionPreferenceChangesPicksWithoutChangingShareOrDuplicates() {
        val low = Song("low", "Low", "Artist", rating = 4.0)
        val high = Song("high", "High", "Artist", rating = 8.0)
        val pools = PlexJukeboxPools(listOf(low, high), emptyList(), listOf(low, high))
        assertEquals(1.0, selectionWeight(4.0, MusicTuning(selection = 0)), 0.0)
        assertEquals(1.0, selectionWeight(8.0, MusicTuning(selection = 0)), 0.0)
        assertEquals("low", chooseJukeboxTracks(pools, 1, 10, 0, MusicTuning(selection = 0)) { .4 }.songs.single().id)
        assertEquals("high", chooseJukeboxTracks(pools, 1, 10, 0, MusicTuning(selection = 4)) { .4 }.songs.single().id)
        for (step in 0..4) {
            val selection = chooseJukeboxTracks(pools, 5, 10, 0, MusicTuning(selection = step)) { .4 }
            assertEquals(2, selection.songs.size)
            assertEquals(2, selection.songs.map { it.id }.distinct().size)
            assertEquals(2, selection.nextMixIndex)
        }
    }

    @Test fun resettingRatingsPreservesSelectionPreference() {
        assertEquals(MusicTuning(selection = 4), MusicTuning(0, 0, 0, 4).resetRatings())
    }
}
