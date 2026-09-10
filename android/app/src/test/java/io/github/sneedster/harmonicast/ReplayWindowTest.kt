package io.github.sneedster.harmonicast

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReplayWindowTest {
    private class Storage : ProfileStorage {
        val values = mutableMapOf<String, String>()
        override fun read(key: String) = values[key]
        override fun write(values: Map<String, String>) { this.values.putAll(values) }
    }
    private val now = 1_800_000_000_000L
    private val week = 7 * ReplayWindow.DAY_MILLIS
    private val source = PersonalPlexSource("token", "https://plex", "machine", "Server", "7", "Music")
    private fun song(id: String = "1") = Song("plex:machine:$id", "Song $id", "Artist", streamUri = "https://plex/part/$id")
    private fun metadata(id: String = "1", at: Long? = null, rating: Double? = 8.0): String =
        """{"type":"track","ratingKey":"$id","librarySectionID":"7","title":"Song $id",${if (at == null) "" else "\"lastViewedAt\":${at / 1000},"}${if (rating == null) "" else "\"userRating\":$rating,"}"Media":[{"Part":[{"key":"/part/$id"}]}]}"""
    private fun container(vararg tracks: String) = """{"MediaContainer":{"Metadata":[${tracks.joinToString(",")}]}}"""

    @Test fun defaultsToAWeekAndSettingSurvivesRecreationWithConsentUnchanged() {
        val storage = Storage()
        val setting = ReplayWindow(storage)
        assertEquals(7, setting.days)
        assertEquals(now - week, setting.cutoff(now))
        setting.days = 14
        assertEquals(14, ReplayWindow(storage).days)
        assertFalse(AutomaticPlexRatings(storage).enabled)
        setting.days = 0
        assertNull(setting.cutoff(now))
        storage.values[ReplayWindow.SETTING_KEY] = "-1"
        assertEquals(7, setting.days)
        storage.values[ReplayWindow.SETTING_KEY] = "garbled"
        assertEquals(7, setting.days)
    }

    @Test fun newerOfPlexAndLocalPlayWinsAndExactBoundaryExpires() {
        val cutoff = now - week
        assertTrue(eligibleForAutomaticMix(song(), cutoff, emptyMap()))
        assertFalse(eligibleForAutomaticMix(song().copy(lastPlayedAtMillis = cutoff + 1), cutoff, emptyMap()))
        assertTrue(eligibleForAutomaticMix(song().copy(lastPlayedAtMillis = cutoff), cutoff, emptyMap()))
        assertFalse(eligibleForAutomaticMix(song().copy(lastPlayedAtMillis = cutoff - 1), cutoff, mapOf(song().id to now)))
        assertFalse(eligibleForAutomaticMix(song().copy(lastPlayedAtMillis = now), cutoff, mapOf(song().id to cutoff - 1)))
        assertTrue(eligibleForAutomaticMix(song().copy(lastPlayedAtMillis = now), null, mapOf(song().id to now)))
    }

    @Test fun importsExistingSkipHistoryAndKeepsLatestEventPerTrack() {
        val storage = Storage()
        storage.values["local.playbackHistory"] = JSONArray()
            .put(JSONObject().put("song", encodeSong(song())).put("event", "skip").put("at", now - 1000))
            .put(JSONObject().put("song", encodeSong(song())).put("event", "complete").put("at", now - week))
            .put(JSONObject().put("song", encodeSong(song("2"))).put("event", "pause").put("at", now))
            .toString()
        assertEquals(mapOf(song().id to now - 1000), RecentTrackPlays(storage).snapshot(now))
        storage.values["local.playbackHistory"] = "[]"
        assertEquals(now - 1000, RecentTrackPlays(storage).snapshot(now)[song().id])
    }

    @Test fun recentTracksOutliveFiveHundredEventsAndExpireByTime() {
        val storage = Storage()
        val plays = RecentTrackPlays(storage)
        for (id in 1..550) plays.record(song(id.toString()).id, now + id)
        assertEquals(550, plays.snapshot(now + 1000).size)
        assertNotNull(plays.snapshot(now + 1000)[song().id])
        assertTrue(plays.snapshot(now + 31 * ReplayWindow.DAY_MILLIS).isEmpty())
    }

    @Test fun plexLastPlayedSurvivesSongSerializationAndOlderSongsStillDecode() = runBlocking {
        val storage = Storage()
        val http = object : PlexHttp {
            override suspend fun request(url: String, method: String, headers: Map<String, String>, form: Map<String, String>) = container(metadata(at = now))
        }
        val mapped = LocalPlexClient(storage, http).track(source, song().id)!!
        assertEquals(now, mapped.lastPlayedAtMillis)
        assertEquals(now, decodeSong(encodeSong(mapped)).lastPlayedAtMillis)
        assertNull(decodeSong(JSONObject().put("id", "old")).lastPlayedAtMillis)
    }

    @Test fun skippedUnratedSongIsExcludedWithAutomaticRatingsOffAfterRestart() = runBlocking {
        val storage = Storage()
        val http = object : PlexHttp {
            override suspend fun request(url: String, method: String, headers: Map<String, String>, form: Map<String, String>): String {
                assertEquals("GET", method)
                return container(metadata(rating = null))
            }
        }
        val core = LocalHarmonicastCore(source, storage, LocalPlexClient(storage, http)) { now }
        core.playback.recordEvent(song(), "skip", .01)
        assertFalse(AutomaticPlexRatings(storage).enabled)
        val restarted = LocalHarmonicastCore(source, storage, LocalPlexClient(storage, http)) { now + 1000 }
        restarted.queue.enableAutomaticPlayback()
        assertTrue(restarted.queue.songs().isEmpty())
        assertTrue(storage.read(ReplayWindow.STATUS_KEY)!!.contains("No eligible"))
    }

    @Test fun startIsRecordedEvenWithoutACompletionOrSkip() = runBlocking {
        val storage = Storage()
        val core = LocalHarmonicastCore(source.copy(canWriteToPlex = false), storage, nowMillis = { now })
        core.playback.publish(song(), isPlaying = true, isAutoQueue = false)
        assertEquals(now, RecentTrackPlays(storage).snapshot(now)[song().id])
        assertFalse(AutomaticPlexRatings(storage).enabled)
    }

    @Test fun staleAutomaticQueueRechecksPlexButExplicitRequestsBypassWindow() = runBlocking {
        val storage = Storage()
        val calls = mutableListOf<String>()
        val http = object : PlexHttp {
            override suspend fun request(url: String, method: String, headers: Map<String, String>, form: Map<String, String>): String {
                calls += url
                return container(metadata(at = now))
            }
        }
        val core = LocalHarmonicastCore(source, storage, LocalPlexClient(storage, http)) { now }
        core.queue.addAll(listOf(song().copy(isManual = false)))
        assertNull(core.queue.dequeue().song)
        assertTrue(calls.any { "/library/metadata/1" in it })
        core.queue.add(song())
        val callCount = calls.size
        assertEquals(song().id, core.queue.dequeue().song!!.id)
        assertEquals(callCount, calls.size)
        assertEquals("", storage.read(ReplayWindow.STATUS_KEY))
    }

    @Test fun localSkipRemovesAlreadyQueuedAutomaticCopiesButNotRadio() = runBlocking {
        val storage = Storage()
        val core = LocalHarmonicastCore(source, storage, nowMillis = { now })
        core.queue.addAll(listOf(song().copy(isManual = false), song().copy(isManual = false, isRadio = true)))
        core.playback.recordEvent(song(), "skip", 0.0)
        val next = core.queue.dequeue().song!!
        assertTrue(next.isRadio)
        assertTrue(core.queue.songs().isEmpty())
    }

    @Test fun noFallbackPoolCanReintroduceRecentlyPlayedTracks() = runBlocking {
        val storage = Storage()
        val http = object : PlexHttp {
            override suspend fun request(url: String, method: String, headers: Map<String, String>, form: Map<String, String>) =
                container(metadata(at = now), metadata("2", rating = null))
        }
        val core = LocalHarmonicastCore(source, storage, LocalPlexClient(storage, http)) { now }
        core.queue.enableAutomaticPlayback()
        assertEquals(listOf(song("2").id), core.queue.songs().map { it.id })
    }

    @Test fun windowChangeDuringFreshMetadataCheckIsRespected() = runBlocking {
        val storage = Storage()
        val http = object : PlexHttp {
            override suspend fun request(url: String, method: String, headers: Map<String, String>, form: Map<String, String>): String {
                ReplayWindow(storage).days = 14
                return container(metadata(at = now - 10 * ReplayWindow.DAY_MILLIS))
            }
        }
        val core = LocalHarmonicastCore(source, storage, LocalPlexClient(storage, http)) { now }
        core.queue.addAll(listOf(song().copy(isManual = false)))
        assertNull(core.queue.dequeue().song)
    }

    @Test fun incomingRequestDuringMetadataFetchKeepsPriority() = runBlocking {
        val storage = Storage()
        lateinit var core: LocalHarmonicastCore
        val http = object : PlexHttp {
            override suspend fun request(url: String, method: String, headers: Map<String, String>, form: Map<String, String>): String {
                core.queue.add(song("requested"))
                return container(metadata())
            }
        }
        core = LocalHarmonicastCore(source, storage, LocalPlexClient(storage, http)) { now }
        core.queue.addAll(listOf(song().copy(isManual = false)))
        assertEquals(song("requested").id, core.queue.dequeue().song!!.id)
        assertEquals(listOf(song().id), core.queue.songs().map { it.id })
    }

    @Test fun boundedOldestPagesTopUpAnUnluckyRandomSample() = runBlocking {
        val storage = Storage()
        val calls = mutableListOf<String>()
        val http = object : PlexHttp {
            override suspend fun request(url: String, method: String, headers: Map<String, String>, form: Map<String, String>): String {
                calls += url
                val rating = if ("userRating=-1" in url) null else 8.0
                return if ("sort=random" in url) container(metadata(at = now, rating = rating), metadata("2", now, rating))
                else container(metadata("3", rating = rating), metadata("4", rating = rating))
            }
        }
        val pools = LocalPlexClient(storage, http).jukeboxPools(source, limit = 2) {
            eligibleForAutomaticMix(it, now - week, emptyMap())
        }
        assertEquals(6, calls.size)
        assertEquals(setOf(song("3").id, song("4").id), pools.fallback.map { it.id }.toSet())
        assertTrue(calls.any { "X-Plex-Container-Start=0" in it && "lastViewedAt" in it })
    }

    @Test fun signingOutClearsReplayHistoryButKeepsWindowPreference() {
        val storage = Storage()
        RecentTrackPlays(storage).record(song().id, now)
        ReplayWindow(storage).days = 14
        HomeProfileStore(storage).clearPersonalSource()
        assertTrue(RecentTrackPlays(storage).snapshot(now).isEmpty())
        assertEquals(14, ReplayWindow(storage).days)
    }
    @Test fun changingLibrariesKeepsServerScopedReplayHistory() {
        val storage = Storage()
        RecentTrackPlays(storage).record(song().id, now)
        HomeProfileStore(storage).clearPersonalPlaybackState()
        val history = RecentTrackPlays(storage).snapshot(now)
        assertEquals(now, history[song().id])
        assertTrue(eligibleForAutomaticMix(song().copy(id = "plex:other-server:1"), now - week, history))
    }

}
