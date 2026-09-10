package io.github.sneedster.harmonicast

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MusicTuningIntegrationTest {
    private class Storage : ProfileStorage {
        val values = mutableMapOf<String, String>()
        override fun read(key: String) = values[key]
        override fun write(values: Map<String, String>) { this.values.putAll(values) }
    }
    private val source = PersonalPlexSource("token", "https://plex", "machine", "Server", "7", "Music")
    private val song = Song("plex:machine:1", "Song", "Artist", rating = 5.0, streamUri = "https://plex/part/1")
    private fun metadata(rating: Double? = 5.0) = """{"MediaContainer":{"Metadata":[{"type":"track","ratingKey":"1","librarySectionID":"7","title":"Song",${if (rating != null) "\"userRating\":$rating," else ""}"Media":[{"Part":[{"key":"/part/1"}]}]}]}}"""

    @Test fun zeroAdjustmentDoesNotAssignAnUnratedSongAndHistorySurvives() = runBlocking {
        val storage = Storage()
        AutomaticPlexRatings(storage).enabled = true
        MusicTuningStore(storage).write(MusicTuning(completion = 0, skip = 0))
        val calls = mutableListOf<String>()
        val http = object : PlexHttp {
            override suspend fun request(url: String, method: String, headers: Map<String, String>, form: Map<String, String>): String { calls += url; return metadata(null) }
        }
        val core = LocalHarmonicastCore(source, storage, LocalPlexClient(storage, http))
        core.playback.recordEvent(song, "complete", 1.0)
        core.playback.recordEvent(song, "skip", 0.0)
        assertFalse(calls.any { "/:/rate" in it })
        assertTrue(storage.read("local.playbackHistory")!!.contains("skip"))
    }

    @Test fun changesDuringMetadataFetchApplyWithoutRecreatingCore() = runBlocking {
        val storage = Storage()
        AutomaticPlexRatings(storage).enabled = true
        val calls = mutableListOf<String>()
        val http = object : PlexHttp {
            override suspend fun request(url: String, method: String, headers: Map<String, String>, form: Map<String, String>): String {
                calls += url
                if ("/library/metadata" in url) MusicTuningStore(storage).write(MusicTuning(skip = 4))
                return metadata()
            }
        }
        LocalHarmonicastCore(source, storage, LocalPlexClient(storage, http)).playback.recordEvent(song, "skip", 0.0)
        assertTrue(calls.any { "rating=4.4" in it })
    }

    @Test fun failedAutomaticWriteStillRecordsHistoryAndKeepsDisplayedRating() = runBlocking {
        val storage = Storage()
        AutomaticPlexRatings(storage).enabled = true
        val http = object : PlexHttp {
            override suspend fun request(url: String, method: String, headers: Map<String, String>, form: Map<String, String>): String {
                if ("/:/rate" in url) error("Plex unavailable")
                return metadata()
            }
        }
        val core = LocalHarmonicastCore(source, storage, LocalPlexClient(storage, http))
        core.playback.publish(song, true, false)
        core.playback.recordEvent(song, "complete", 1.0)
        assertEquals(5.0, core.playback.snapshot().nowPlaying.song!!.rating!!, 0.0)
        assertTrue(storage.read("local.playbackHistory")!!.contains("complete"))
    }

    @Test fun explicitVoteWaitsForAutomaticUpdateAndUsesFreshRating() = runBlocking {
        val storage = Storage()
        AutomaticPlexRatings(storage).enabled = true
        val enteredWrite = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        var rating = 5.0
        var metadataReads = 0
        val http = object : PlexHttp {
            override suspend fun request(url: String, method: String, headers: Map<String, String>, form: Map<String, String>): String {
                if ("/:/rate" in url) {
                    val newRating = url.substringAfter("rating=").substringBefore('&').toDouble()
                    if (newRating == 5.1) { enteredWrite.complete(Unit); releaseWrite.await() }
                    rating = newRating
                } else metadataReads++
                return metadata(rating)
            }
        }
        val core = LocalHarmonicastCore(source, storage, LocalPlexClient(storage, http))
        core.playback.publish(song, true, false)
        val automatic = launch { core.playback.recordEvent(song, "complete", 1.0) }
        enteredWrite.await()
        val vote = launch { core.guests.vote(true) }
        yield()
        assertEquals(1, metadataReads)
        releaseWrite.complete(Unit)
        automatic.join(); vote.join()
        assertEquals(6.1, rating, .0001)
    }
}
