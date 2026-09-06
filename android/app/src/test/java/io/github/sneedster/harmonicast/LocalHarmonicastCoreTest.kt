package io.github.sneedster.harmonicast

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LocalHarmonicastCoreTest {
    private class MemoryStorage : ProfileStorage {
        val values = mutableMapOf<String, String>()
        override fun read(key: String) = values[key]
        override fun write(values: Map<String, String>) { this.values.putAll(values) }
    }
    private val source = PersonalPlexSource("token", "https://plex", "machine", "Server", "7", "Music")
    private fun song(id: String) = Song(
        "plex:machine:$id", "Song $id", "Artist", streamUri = "https://plex/part/$id?token",
    )

    @Test fun queueAndPlaybackSurviveCoreRecreation() = runBlocking {
        val storage = MemoryStorage()
        val first = LocalHarmonicastCore(source, storage)
        first.queue.add(song("1"))
        first.queue.add(song("2"))
        first.playback.publish(song("1"), true, false)
        first.playback.savePosition(12.5)

        val restarted = LocalHarmonicastCore(source, storage)
        assertEquals(listOf("plex:machine:1", "plex:machine:2"), restarted.queue.songs().map(Song::id))
        val state = restarted.playback.snapshot()
        assertEquals("plex:machine:1", state.nowPlaying.song?.id)
        assertEquals("https://plex/part/1?token", state.nowPlaying.song?.streamUri)
        assertEquals(12.5, state.positionSeconds, 0.0)
        assertTrue(state.nowPlaying.isPlaying)
        assertEquals("plex:machine:1", restarted.queue.dequeue().song?.id)
        assertEquals(listOf("plex:machine:2"), restarted.queue.songs().map(Song::id))
    }

    @Test fun localCoreIsAlwaysOwnerAndDoesNotRequireGuestCredentials() = runBlocking {
        val core = LocalHarmonicastCore(source, MemoryStorage())
        val policy = core.guests.policy()
        assertTrue(policy.isHost)
        assertTrue(policy.isActivePlayer)
        assertTrue(policy.configured)
    }

    @Test fun sharedReadOnlyCoreKeepsLocalPlaybackButSuppressesPlexWrites() = runBlocking {
        val storage = MemoryStorage()
        val readOnly = LocalHarmonicastCore(source.copy(canWriteToPlex = false), storage)
        val track = song("shared")

        readOnly.playback.publish(track, true, false)
        readOnly.playback.scrobble(track.id, submission = true)
        readOnly.playback.recordEvent(track, "completed", 1.0)

        assertEquals(track.id, readOnly.playback.snapshot().nowPlaying.song?.id)
        assertTrue(storage.values["local.playbackHistory"].orEmpty().contains("completed"))
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { readOnly.guests.vote(true) }
        }
        assertTrue(error.message.orEmpty().contains("read-only"))
    }

    @Test fun ratedTrackShareSurvivesCoreRecreation() = runBlocking {
        val storage = MemoryStorage()
        LocalHarmonicastCore(source, storage).queue.setRatedTrackShare(3)

        assertEquals(3, LocalHarmonicastCore(source, storage).queue.ratedTrackShare())
        assertEquals("3", storage.values["local.ratedTrackShare"])
    }

    @Test fun localEventsCrossTheUiAndPlaybackServiceCoreInstances() = runBlocking {
        val storage = MemoryStorage()
        val ui = LocalHarmonicastCore(source, storage)
        val service = LocalHarmonicastCore(source, storage)
        val events = mutableListOf<CoreEvent>()
        val subscription = service.observe({ events += it }, {})
        ui.queue.add(song("1"))
        ui.playback.skip()
        assertEquals(listOf(CoreEvent.QUEUE_CHANGED, CoreEvent.FORCE_SKIP), events)
        subscription.close()
    }

    @Test fun emptyQueueSeedsAutomaticMixBeforeDequeueing() = runBlocking {
        val items = mutableListOf<Song>()
        var automaticStarts = 0
        val queue = object : MusicQueue {
            override suspend fun songs() = items.toList()
            override suspend fun dequeue() = QueueSelection(items.removeFirstOrNull(), false)
            override suspend fun add(song: Song) { items += song }
            override suspend fun addAll(songs: List<Song>, next: Boolean) { items += songs }
            override suspend fun remove(id: String) { items.removeAll { it.id == id } }
            override suspend fun clear() = items.clear()
            override suspend fun radio() = 0
            override suspend fun enableAutomaticPlayback() {
                automaticStarts++
                items += song("auto")
            }
            override suspend fun ratedTrackShare() = 8
            override suspend fun setRatedTrackShare(value: Int) = Unit
        }

        val selection = queue.dequeueWithAutomaticFallback()

        assertEquals("plex:machine:auto", selection.song?.id)
        assertEquals(1, automaticStarts)
    }

    @Test fun playNextPreservesPlaylistOrderAheadOfExistingQueue() = runBlocking {
        val core = LocalHarmonicastCore(source, MemoryStorage())
        core.queue.add(song("old"))
        core.queue.addAll(listOf(song("1"), song("2")), next = true)
        assertEquals(listOf("1", "2", "old"), core.queue.songs().map { it.id.substringAfterLast(':') })
    }

    @Test fun manualRequestsStayAheadOfAutomaticTracksWithoutPassingEarlierRequests() = runBlocking {
        val core = LocalHarmonicastCore(source, MemoryStorage())
        core.queue.addAll(
            listOf(song("auto-1").copy(isManual = false), song("auto-2").copy(isManual = false)),
            next = false,
        )
        core.queue.add(song("request-1"))
        core.queue.add(song("request-2"))

        assertEquals(
            listOf("request-1", "request-2", "auto-1", "auto-2"),
            core.queue.songs().map { it.id.substringAfterLast(':') },
        )
    }

    @Test fun manualRequestsAlternateDisposableParticipantsAheadOfAutomaticTracks() = runBlocking {
        val core = LocalHarmonicastCore(source, MemoryStorage())
        core.queue.addAll(listOf(song("auto").copy(isManual = false)), next = false)
        core.queue.add(song("guest-1a").copy(addedByEmail = "Nearby guest 1"))
        core.queue.add(song("guest-1b").copy(addedByEmail = "Nearby guest 1"))
        core.queue.add(song("guest-2a").copy(addedByEmail = "Nearby guest 2"))

        assertEquals(
            listOf("guest-1a", "guest-2a", "guest-1b", "auto"),
            core.queue.songs().map { it.id.substringAfterLast(':') },
        )
    }

    @Test fun legacyQueueWithoutProvenanceMigratesBehindNewRequests() = runBlocking {
        val storage = MemoryStorage().apply {
            values["local.queue"] = """[{"id":"plex:machine:old","title":"Old auto","artist":"Artist"}]"""
        }
        val core = LocalHarmonicastCore(source, storage)

        core.queue.add(song("request"))

        assertEquals(listOf("request", "old"), core.queue.songs().map { it.id.substringAfterLast(':') })
        assertEquals(listOf(true, false), core.queue.songs().map(Song::isManual))
    }

    @Test fun automaticMixSpreadsUnratedExplorationAcrossTenSlots() {
        val rated = (1..8).map { song("r$it").copy(rating = 8.0) }
        val unrated = (1..2).map { song("u$it") }
        val result = chooseJukeboxTracks(PlexJukeboxPools(rated, unrated, rated + unrated), 10, 8, 0) { 0.0 }
        assertEquals(listOf("r", "r", "r", "r", "u", "r", "r", "r", "r", "u"),
            result.songs.map { it.id.substringAfterLast(':').take(1) })
        assertEquals(10, result.nextMixIndex)
    }

    @Test fun automaticMixHonorsStrictRatedAndUnratedEndpoints() {
        val rated = listOf(song("rated").copy(rating = 8.0))
        val unrated = listOf(song("new"))
        val pools = PlexJukeboxPools(rated, unrated, rated + unrated)
        assertEquals(listOf("rated"), chooseJukeboxTracks(pools, 2, 10, 0) { 0.0 }.songs.map { it.id.substringAfterLast(':') })
        assertEquals(listOf("new"), chooseJukeboxTracks(pools, 2, 0, 0) { 0.0 }.songs.map { it.id.substringAfterLast(':') })
    }

    @Test fun playbackRatingMatchesExistingCompletionAndSkipRules() {
        assertEquals(5.1, adjustPersonalRating(null, "complete", 1.0, 0), 0.0)
        assertEquals(4.7, adjustPersonalRating(5.0, "skip", 0.0, 0), 0.0)
        assertEquals(5.0, adjustPersonalRating(5.0, "skip", 0.95, 0), 0.0)
    }

    @Test fun downRatingOnlyAutoSkipsAutomaticTracks() {
        assertTrue(shouldSkipAfterVote(up = false, isAutoQueue = true))
        assertFalse(shouldSkipAfterVote(up = false, isAutoQueue = false))
        assertFalse(shouldSkipAfterVote(up = true, isAutoQueue = true))
    }
}
