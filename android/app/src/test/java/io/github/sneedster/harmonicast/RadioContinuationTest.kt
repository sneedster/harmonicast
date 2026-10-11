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
    private fun core(storage: Memory, mix: List<Song> = emptyList(), fetch: suspend (String) -> List<Song>): LocalHarmonicastCore {
        val http = object : PlexHttp {
            override suspend fun request(url: String, method: String, headers: Map<String, String>, form: Map<String, String>): String {
                if (url.endsWith("/library/metadata/10000")) return """{"MediaContainer":{"Metadata":[{"type":"artist","ratingKey":"10000","librarySectionID":"7","title":"Artist"}]}}"""
                val isMix = url.contains("/sections/7/all?")
                if (!url.contains("/nearest?") && !isMix) {
                    val key = url.substringAfterLast('/')
                    assertTrue(key.matches(Regex("\\d+")))
                    return """{"MediaContainer":{"Metadata":[{"type":"track","ratingKey":"$key","librarySectionID":"7","title":"Song $key","grandparentTitle":"Artist","grandparentRatingKey":"10000"}]}}"""
                }
                if (!isMix) assertTrue(url.contains("limit=500") || url.contains("limit=100"))
                val tracks = if (isMix) mix else fetch(url)
                return JSONObject().put("MediaContainer", JSONObject().put("Metadata", JSONArray().apply {
                    tracks.forEach { song -> put(JSONObject().put("type", "track")
                        .put("ratingKey", song.id.substringAfterLast(':')).put("librarySectionID", "7")
                        .put("title", song.title).put("grandparentTitle", song.artist).put("distance", 0.01).put("userRating", 8)
                        .put("Media", JSONArray().put(JSONObject().put("Part", JSONArray()
                            .put(JSONObject().put("key", "/part/${song.id.substringAfterLast(':')}")))))) }
                })).toString()
            }
        }
        return LocalHarmonicastCore(source, storage, LocalPlexClient(storage, http))
    }

    @Test fun detourPreservesRequestsAndReturnsToOriginalSeedWithoutChangingDistance() = runBlocking {
        val storage = Memory(); TrackRadioSettings(storage).distance = 0.15
        val calls = mutableListOf<String>()
        val core = core(storage) { url ->
            calls += url
            if (url.contains("maxDistance=0.5")) (2..14).map { song(it) } else (2..11).map { song(it) }
        }
        core.playback.publish(song(1), true, false)
        core.queue.add(song(50))
        assertEquals(3, core.queue.somewhereDifferent())
        assertEquals(song(50).id, core.queue.dequeue().song!!.id)
        repeat(3) { val next = core.queue.dequeue().song!!; assertTrue(next.id in (12..14).map { song(it).id }); core.playback.publish(next, true, true) }
        core.queue.enableAutomaticPlayback()
        assertTrue(calls.last().contains("/1/nearest"))
        assertTrue(calls[2].contains("maxDistance=0.15"))
        assertTrue(calls.last().contains("maxDistance=0.3"))
        assertEquals(0.15, TrackRadioSettings(storage).distance, 0.0)
        assertTrue(storage.read("local.radioReturnSeed").isNullOrEmpty())
        core.queue.clear(); assertEquals("false", storage.read("local.radioActive"))
    }

    @Test fun emptyQueueKeepsOriginalSeedAndSurvivesCoreRecreation() = runBlocking {
        val storage = Memory()
        val calls = mutableListOf<String>()
        var continued = false
        val fetch: suspend (String) -> List<Song> = { url ->
            calls += url
            when {
                url.contains("/1/nearest") && !continued -> (2..11).map { song(it) }
                url.contains("/1/nearest") -> listOf(song(100, "Song 1"), song(101, "Song 2")) + (12..21).map { song(it) }
                else -> error("Unexpected seed")
            }
        }
        var core = core(storage, fetch = fetch)
        core.playback.publish(song(1), true, false)
        assertEquals(10, core.queue.radio())
        repeat(10) {
            val next = core.queue.dequeueWithAutomaticFallback()
            assertEquals(song(it + 2).id, next.song!!.id)
            assertTrue(next.song!!.isRadio)
            assertFalse(next.isManual)
            core.playback.publish(next.song, true, true)
        }
        continued = true
        core = core(storage, fetch = fetch)
        assertEquals(song(12).id, core.queue.dequeueWithAutomaticFallback().song!!.id)
        assertEquals(4, calls.size)
        assertTrue(calls.last().contains("/1/nearest"))
        assertEquals(9, core.queue.songs().size)
        assertTrue(core.queue.songs().none { it.title in setOf("Song 1", "Song 2") })
    }

    @Test fun noFreshMatchesStopsWithoutUnrelatedMixAndKeepsSeedForRetry() = runBlocking {
        val storage = Memory()
        var fresh = true
        var nextId = 2
        val core = core(storage) { if (fresh) (nextId..nextId + 9).map { song(it) } else listOf(song(99, "Song 1")) }
        core.playback.publish(song(1), true, false)
        assertEquals(10, core.queue.radio())
        repeat(10) { core.playback.publish(core.queue.dequeue().song!!, true, true) }
        fresh = false
        assertNull(core.queue.dequeueWithAutomaticFallback().song)
        assertTrue(storage.read(ReplayWindow.STATUS_KEY)!!.contains("No fresh songs from this artist"))
        core.playback.publish(null, false, false)
        fresh = true
        nextId = 12
        assertEquals(song(12).id, core.queue.dequeueWithAutomaticFallback().song!!.id)
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
        var nextId = 2
        val core = core(storage) { calls += it; (nextId..nextId + 19).map { id -> song(id) } }
        core.playback.publish(song(1), true, false)
        TrackRadioSettings(storage).distance = 0.15
        core.queue.radio()
        assertEquals(listOf("0.15"), calls.map { it.substringAfter("maxDistance=") })
        repeat(20) { core.playback.publish(core.queue.dequeue().song!!, true, true) }
        nextId = 22
        calls.clear()
        TrackRadioSettings(storage).distance = 0.10
        core.queue.enableAutomaticPlayback()
        assertEquals(listOf("0.1"), calls.map { it.substringAfter("maxDistance=") })
    }

    @Test fun failedRadioStartPreservesTheQueueAndDoesNotActivateRadio() = runBlocking {
        val storage = Memory()
        val waiting = listOf(song(50), song(51).copy(isManual = false))
        storage.write(mapOf("local.queue" to JSONArray(waiting.map(::encodeSong)).toString()))
        val core = core(storage) { emptyList() }
        core.playback.publish(song(1), true, false)
        assertEquals(0, core.queue.radio())
        assertEquals(waiting.map { it.id }, core.queue.songs().map { it.id })
        assertNotEquals("true", storage.read("local.radioActive"))
        assertNull(storage.read("local.radioSeed"))
        assertTrue(storage.read(ReplayWindow.STATUS_KEY)!!.contains("No fresh songs"))
    }

    @Test fun failedReplacementPreservesTheExistingStationAndQueue() = runBlocking {
        val storage = Memory()
        var available = true
        val core = core(storage) { if (available) (2..21).map { song(it) } else emptyList() }
        core.playback.publish(song(1), true, false)
        assertEquals(20, core.queue.radio())
        val waiting = core.queue.songs()
        val originalSeed = storage.read("local.radioSeed")
        core.playback.publish(song(100), true, false)
        available = false
        assertEquals(0, core.queue.radio())
        assertEquals(waiting, core.queue.songs())
        assertEquals(originalSeed, storage.read("local.radioSeed"))
        assertEquals("true", storage.read("local.radioActive"))
        assertEquals(ARTIST_RADIO_EMPTY_MESSAGE, storage.read(ReplayWindow.STATUS_KEY))
        core.playback.publish(song(1), true, false)
        assertEquals(0, core.queue.radio())
        assertEquals("", storage.read(ReplayWindow.STATUS_KEY))
    }

    @Test fun explicitMixStartLeavesBothEmptyAndPopulatedRadioSessions() = runBlocking {
        for (hasRequests in listOf(false, true)) {
            val storage = Memory()
            val waiting = if (hasRequests) listOf(song(50), song(51).copy(isManual = false, isRadio = true)) else emptyList()
            storage.write(mapOf(
                "local.radioActive" to "true", "local.radioSeed" to encodeSong(song(1)).toString(),
                "local.radioReturnSeed" to encodeSong(song(1)).toString(), "local.radioRecent" to "[]",
                "local.offlinePlayback" to "true", ReplayWindow.STATUS_KEY to ARTIST_RADIO_EMPTY_MESSAGE,
                "local.queue" to JSONArray(waiting.map(::encodeSong)).toString(),
            ))
            val core = core(storage, mix = (80..99).map { song(it) }) { error("Explicit mix must not refill radio") }
            core.playback.publish(song(1), true, false)
            val first = core.queue.startAutomaticMix()
            if (hasRequests) assertEquals(song(50).id, first.song!!.id)
            else assertTrue(first.song!!.id in (80..99).map { song(it).id })
            assertEquals(hasRequests, first.isManual)
            assertEquals("false", storage.read("local.radioActive"))
            assertEquals("false", storage.read("local.offlinePlayback"))
            assertEquals("", storage.read("local.radioSeed"))
            assertEquals("", storage.read("local.radioReturnSeed"))
            assertEquals("[]", storage.read("local.radioRecent"))
            assertEquals("", storage.read(ReplayWindow.STATUS_KEY))
            val next = core.queue.dequeueWithAutomaticFallback()
            assertTrue(next.song!!.id in (80..99).map { song(it).id })
            assertFalse(next.isManual)
            assertTrue(core.queue.songs().none { it.isRadio })
        }
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

    @Test fun startingArtistRadioReplacesTheAutomaticTailButKeepsManualRequests() = runBlocking {
        val storage = Memory()
        storage.write(mapOf("local.queue" to JSONArray().apply {
            put(encodeSong(song(50)))
            put(encodeSong(song(51).copy(isManual = false)))
        }.toString()))
        val core = core(storage) { (2..11).map { song(it) } }
        core.playback.publish(song(1), true, false)
        assertEquals(10, core.queue.radio())
        val queued = core.queue.songs()
        assertEquals(song(50).id, queued.first().id)
        assertTrue(queued.first().isManual)
        assertFalse(queued.any { it.id == song(51).id })
        assertEquals(10, queued.count { it.isRadio })
    }

    @Test fun detourDuringAStationReturnsToTheOriginalArtistAndTrack() = runBlocking {
        val storage = Memory()
        val calls = mutableListOf<String>()
        val core = core(storage) { url ->
            calls += url
            if (url.contains("maxDistance=0.5")) (2..14).map { song(it) } else (2..11).map { song(it) }
        }
        core.playback.publish(song(1), true, false)
        core.queue.radio()
        core.playback.publish(core.queue.dequeue().song!!, true, true)
        assertEquals(3, core.queue.somewhereDifferent())
        repeat(3) { core.playback.publish(core.queue.dequeue().song!!, true, true) }
        core.queue.enableAutomaticPlayback()
        assertTrue(calls.last().contains("/1/nearest?"))
        assertEquals(song(1).id, decodeSong(JSONObject(storage.read("local.radioSeed")!!)).id)
    }
}
