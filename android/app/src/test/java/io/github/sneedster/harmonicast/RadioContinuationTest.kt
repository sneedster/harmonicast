package io.github.sneedster.harmonicast

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RadioContinuationTest {
    private class Memory : ProfileStorage {
        val values = mutableMapOf<String, String>()
        override fun read(key: String) = values[key]
        override fun write(values: Map<String, String>) { this.values.putAll(values) }
    }
    private val source = PersonalPlexSource("token", "https://plex", "machine", "Server", "7", "Music")
    private fun song(n: Int, title: String = "Song $n") = Song("plex:machine:$n", title, "Artist",
        streamUri = "https://plex/part/$n")
    private fun core(storage: Memory, fetch: suspend (String) -> List<Song>): LocalHarmonicastCore {
        val http = object : PlexHttp {
            override suspend fun request(url: String, method: String, headers: Map<String, String>, form: Map<String, String>): String {
                assertTrue("Radio must not fall back to unrelated library pools: $url", url.contains("/nearest?"))
                assertTrue(url.contains("limit=100"))
                val tracks = fetch(url)
                return JSONObject().put("MediaContainer", JSONObject().put("Metadata", JSONArray().apply {
                    tracks.forEach { song -> put(JSONObject().put("type", "track")
                        .put("ratingKey", song.id.substringAfterLast(':')).put("librarySectionID", "7")
                        .put("title", song.title).put("grandparentTitle", song.artist)
                        .put("Media", JSONArray().put(JSONObject().put("Part", JSONArray()
                            .put(JSONObject().put("key", "/part/${song.id.substringAfterLast(':')}")))))) }
                })).toString()
            }
        }
        return LocalHarmonicastCore(source, storage, LocalPlexClient(storage, http))
    }

    @Test fun emptyQueueContinuesFromLastPlayedSongAndSurvivesCoreRecreation() = runBlocking {
        val storage = Memory()
        val calls = mutableListOf<String>()
        val fetch: suspend (String) -> List<Song> = { url ->
            calls += url
            when {
                url.contains("/1/nearest") -> (2..11).map { song(it) }
                url.contains("/11/nearest") -> listOf(song(100, "Song 1"), song(101, "Song 2")) + (12..21).map { song(it) }
                else -> error("Unexpected seed")
            }
        }
        var core = core(storage, fetch)
        core.playback.publish(song(1), true, false)
        assertEquals(10, core.queue.radio())
        repeat(10) {
            val next = core.queue.dequeueWithAutomaticFallback()
            assertEquals(song(it + 2).id, next.song!!.id)
            assertTrue(next.song!!.isRadio)
            assertFalse(next.isManual)
            core.playback.publish(next.song, true, true)
        }
        core = core(storage, fetch)
        assertEquals(song(12).id, core.queue.dequeueWithAutomaticFallback().song!!.id)
        assertEquals(2, calls.size)
        assertTrue(calls.last().contains("/11/nearest"))
        assertEquals(9, core.queue.songs().size)
        assertTrue(core.queue.songs().none { it.title in setOf("Song 1", "Song 2") })
    }

    @Test fun noFreshMatchesStopsWithoutUnrelatedMixAndKeepsSeedForRetry() = runBlocking {
        val storage = Memory()
        var fresh = false
        val core = core(storage) { if (fresh) (2..11).map { song(it) } else listOf(song(99, "Song 1")) }
        core.playback.publish(song(1), true, false)
        assertEquals(0, core.queue.radio())
        assertNull(core.queue.dequeueWithAutomaticFallback().song)
        assertTrue(storage.read(ReplayWindow.STATUS_KEY)!!.contains("No fresh sonic matches"))
        core.playback.publish(null, false, false)
        fresh = true
        assertEquals(song(2).id, core.queue.dequeueWithAutomaticFallback().song!!.id)
    }

    @Test fun refillsAreSerializedAndClearEndsRadio() = runBlocking {
        val storage = Memory()
        val core = core(storage) { (2..11).map { song(it) } }
        core.playback.publish(song(1), true, false)
        core.queue.radio()
        repeat(10) { core.queue.dequeue() }
        val one = async { core.queue.enableAutomaticPlayback() }
        val two = async { core.queue.enableAutomaticPlayback() }
        one.await(); two.await()
        assertEquals(10, core.queue.songs().size)
        core.queue.clear()
        assertTrue(core.queue.songs().isEmpty())
        assertEquals("false", storage.read("local.radioActive"))
        assertEquals("[]", storage.read("local.radioRecent"))
        assertEquals("", storage.read("local.radioSeed"))
    }

    @Test fun manualRequestArrivingDuringRefillIsPreserved() = runBlocking {
        val storage = Memory()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var block = false
        val core = core(storage) {
            if (block) { started.complete(Unit); finish.await() }
            (2..11).map { song(it) }
        }
        core.playback.publish(song(1), true, false)
        core.queue.radio()
        repeat(10) { core.queue.dequeue() }
        block = true
        val refill = async { core.queue.enableAutomaticPlayback() }
        started.await()
        val manual = async { core.queue.add(song(50)) }
        finish.complete(Unit)
        refill.await(); manual.await()
        assertEquals(song(50).id, core.queue.songs().first().id)
        assertEquals(11, core.queue.songs().size)
    }

    @Test fun candidateOverfetchProducesTwentyDistinctTracks() = runBlocking {
        val storage = Memory()
        val core = core(storage) { (2..26).flatMap { n -> (0..3).map { copy -> song(n * 10 + copy, "Song $n") } } }
        core.playback.publish(song(1), true, false)
        assertEquals(20, core.queue.radio())
        assertEquals(20, core.queue.songs().map { it.artist to it.title }.distinct().size)
    }

    @Test fun savedDistanceIsUsedByExistingCoreAtNextBatch() = runBlocking {
        val storage = Memory()
        val calls = mutableListOf<String>()
        val core = core(storage) { calls += it; emptyList() }
        core.playback.publish(song(1), true, false)
        TrackRadioSettings(storage).distance = 0.15
        core.queue.radio()
        assertEquals(listOf("0.15", "0.2", "0.25"), calls.map { it.substringAfter("maxDistance=") })
        calls.clear()
        TrackRadioSettings(storage).distance = 0.10
        core.queue.enableAutomaticPlayback()
        assertEquals(listOf("0.1", "0.15", "0.2"), calls.map { it.substringAfter("maxDistance=") })
    }

    @Test fun sourceResetClearsRadioSessionButKeepsDistancePreference() {
        val storage = Memory()
        storage.values.putAll(mapOf("local.radioActive" to "true", "local.radioSeed" to "seed", "local.radioRecent" to "recent"))
        TrackRadioSettings(storage).distance = 0.15
        HomeProfileStore(storage).clearPersonalPlaybackState()
        assertEquals("false", storage.read("local.radioActive"))
        assertEquals("", storage.read("local.radioSeed"))
        assertEquals("[]", storage.read("local.radioRecent"))
        assertEquals(0.15, TrackRadioSettings(storage).distance, 0.0)
    }
}
