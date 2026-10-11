package io.github.sneedster.harmonicast

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt

internal object QueueTransactions { val mutex = Mutex() }

internal fun notifyLocalArtworkChanged() = LocalCoreEvents.publish(CoreEvent.CHANGED)

private object LocalCoreEvents {
    val ratingMutex = Mutex()
    val listeners = CopyOnWriteArrayList<(CoreEvent) -> Unit>()
    fun publish(event: CoreEvent) = listeners.forEach { it(event) }
}

/** Personal-mode authority backed by Android app storage and the selected Plex server. */
class LocalHarmonicastCore(
    private val configuredSource: PersonalPlexSource?,
    private val storage: ProfileStorage,
    private val plex: LocalPlexClient = LocalPlexClient(storage),
    private val offline: OfflineStore? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : HarmonicastCore {
    private val replayWindow = ReplayWindow(storage)
    private val recentPlays = RecentTrackPlays(storage)
    private fun sourceStillSelected(): Boolean {
        if (storage.read("home.mode") == null) return true
        val selected = HomeProfileStore(storage).personalSource ?: return false
        return configuredSource?.let { it.machineIdentifier == selected.machineIdentifier && it.libraryKey == selected.libraryKey && it.accountToken == selected.accountToken } == true
    }
    private val source: PersonalPlexSource get() = checkNotNull(configuredSource) { "Sign in with Plex first" }
    override fun observe(onEvent: (CoreEvent) -> Unit, onDisconnected: () -> Unit): CoreSubscription {
        LocalCoreEvents.listeners += onEvent
        return CoreSubscription { LocalCoreEvents.listeners -= onEvent }
    }

    override val library: MusicLibrary = object : MusicLibrary {
        override suspend fun randomTracks(limit: Int) = plex.discoverySample(source, limit)
        override suspend fun recentTracks() = plex.discoveryRecentTracks(source)
        override suspend fun recentlyPlayedTracks(limit: Int): List<Song> {
            val local = recentPlays.snapshot(nowMillis())
            val cached = offline?.availableSongs(source).orEmpty().filter { (local[it.id] ?: 0) > 0 }
            val remote = try { plex.recentlyPlayedTracks(source, limit) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { if (cached.isEmpty()) throw e else emptyList() }
            return (remote + cached).distinctBy { it.id }
                .sortedByDescending { maxOf(it.lastPlayedAtMillis ?: 0, local[it.id] ?: 0) }.take(limit.coerceIn(1, 40))
        }
        override suspend fun mixPreview(mode: MixDiscovery): List<Song> {
            val now = nowMillis()
            val cutoff = replayWindow.cutoff(now)
            val local = recentPlays.snapshot(now)
            val candidates = if (mode == MixDiscovery.STANDARD) {
                val pools = plex.jukeboxPools(source, 12) { eligibleForAutomaticMix(it, cutoff, local) }
                chooseJukeboxTracks(pools, 12, storage.read("local.ratedTrackShare")?.toIntOrNull()?.coerceIn(0, 10) ?: 8,
                    0, MusicTuningStore(storage).read()).songs
            } else plex.rediscoveryTracks(source, mode).filter {
                eligibleForAutomaticMix(it, cutoff, local) && rediscoveryEligible(it, mode, now, local)
            }
            fun covers(tracks: List<Song>) = tracks.filter { it.artworkUri != null }.distinctBy {
                if (it.album.isNotBlank()) "${it.albumArtist}\u0000${it.album}\u0000${it.year}" else it.artworkUri
            }.take(4)
            val eligible = covers(candidates)
            return if (eligible.isEmpty() && mode == MixDiscovery.FORGOTTEN) covers(plex.favoriteArtworkTracks(source)) else eligible
        }
        override suspend fun letterIndex(kind: BrowseKind) = plex.letterIndex(source, kind)
        override suspend fun browse(kind: BrowseKind, order: BrowseOrder, offset: Int, parent: String?, query: String) = plex.browse(source, kind, order, offset, parent, query)
        override suspend fun albumTracks(id: String) = plex.albumTracks(source, id)
        override suspend fun searchPage(query: String, offset: Int, limit: Int) = plex.searchPage(source, query, offset, limit)
        override suspend fun search(query: String) = plex.search(source, query)
        override suspend fun searchForBrowsing(query: String) = plex.search(source, query, expandAlbums = false, expandArtists = false)
        override suspend fun track(id: String): Song? {
            val cached = if (sourceStillSelected()) offline?.song(source, id) else null
            if (storage.read("local.offlinePlayback") == "true" || offline?.connected() == false) cached?.let { return it }
            return try { plex.track(source, id) }
                catch (e: java.io.IOException) { cached ?: throw e }
        }
        override suspend fun artist(query: String) = plex.artist(source, query)
        override suspend fun discovery(song: Song) = plex.discovery(source, song)
        override suspend fun playlists() = plex.playlists(source)
        override suspend fun playlistTracks(id: String) = plex.playlistTracks(source, id)
        override suspend fun playlistPage(id: String, offset: Int) = plex.playlistPage(source, id, offset)
        override fun streamUrl(song: Song) = offline?.uri(source, song.id) ?: currentSourceUrl(song, song.streamUri)
            ?: throw IllegalStateException("Plex track needs fresh playback metadata")
        override fun artworkUrl(song: Song) = (if (configuredSource != null && sourceStillSelected()) offline?.artworkUri(source, song.id) else null)
            ?: currentSourceUrl(song, song.artworkUri)
    }

    // Persisted queue/playback metadata may still contain a previous local endpoint.
    private fun currentSourceUrl(song: Song, value: String?): String? {
        val source = configuredSource ?: return value
        if (value == null || !song.id.startsWith("plex:${java.net.URLEncoder.encode(source.machineIdentifier, "UTF-8")}:")) return value
        val base = source.baseUrl.toHttpUrlOrNull() ?: return value
        if (value.startsWith("/library/metadata/") && '?' !in value && '#' !in value)
            return base.resolve(value)?.newBuilder()?.setQueryParameter("X-Plex-Token", source.token)?.build()?.toString()
        val original = value.toHttpUrlOrNull() ?: return value
        return original.newBuilder().scheme(base.scheme).host(base.host).port(base.port)
            .setQueryParameter("X-Plex-Token", source.token).build().toString()
    }

    override val queue: MusicQueue = object : MusicQueue {
        override suspend fun songs() = readSongs("local.queue")
        override suspend fun dequeue(): QueueSelection {
            var removedAutomatic = false
            while (true) {
                val candidate = songs().firstOrNull() ?: run {
                    if (removedAutomatic) {
                        storage.write(mapOf(ReplayWindow.STATUS_KEY to ReplayWindow.EMPTY_MESSAGE))
                        LocalCoreEvents.publish(CoreEvent.CHANGED)
                    }
                    return QueueSelection(null)
                }
                val cutoff = replayWindow.cutoff(nowMillis())
                val automatic = !candidate.isManual && !candidate.isRadio
                val cached = if (sourceStillSelected()) offline?.song(source, candidate.id) else null
                var selected: Song? = if (cached != null) candidate.copy(artist = cached.artist, albumArtist = cached.albumArtist) else library.refreshLegacyArtist(candidate)
                if (automatic && cutoff != null) {
                    val local = recentPlays.snapshot(nowMillis())
                    selected = if (!eligibleForAutomaticMix(candidate, cutoff, local)) null
                        else (cached?.copy(streamUri = candidate.streamUri, artworkUri = candidate.artworkUri, coverArt = candidate.coverArt)
                            ?: library.track(candidate.id))?.copy(isManual = false)
                    // Re-read preferences/history after the metadata request. Another
                    // device may have played it, or the user may have changed the window.
                    if (selected != null && !eligibleForAutomaticMix(selected,
                            replayWindow.cutoff(nowMillis()), recentPlays.snapshot(nowMillis()))) selected = null
                }
                // A request may have arrived while Plex metadata was loading. Respect
                // the new queue head and never overwrite newly added requests.
                val removed = QueueTransactions.mutex.withLock {
                    val latest = songs()
                    if (latest.firstOrNull() != candidate) false else {
                        writeSongs("local.queue", latest.drop(1)); true
                    }
                }
                if (!removed) continue
                if (selected == null) removedAutomatic = true
                LocalCoreEvents.publish(CoreEvent.QUEUE_CHANGED)
                if (selected != null) {
                    storage.write(mapOf(ReplayWindow.STATUS_KEY to ""))
                    return QueueSelection(selected, candidate.isManual)
                }
            }
        }
        override suspend fun add(song: Song) = QueueTransactions.mutex.withLock {
            val current = songs()
            val requested = song.copy(isManual = true)
            val updated = fairManualQueue(current + requested)
            writeSongs("local.queue", updated)
            LocalCoreEvents.publish(CoreEvent.QUEUE_CHANGED)
        }
        override suspend fun addGuest(song: Song, pending: () -> Int) = QueueTransactions.mutex.withLock {
            val current = songs()
            if (current.count { it.isManual && it.addedByEmail == song.addedByEmail } + pending() >= 5)
                throw AcquisitionFailure(429, "You already have 5 songs queued or being acquired")
            writeSongs("local.queue", fairManualQueue(current + song.copy(isManual = true)))
            LocalCoreEvents.publish(CoreEvent.QUEUE_CHANGED)
        }
        override suspend fun addOnce(requestId: String, song: Song) = QueueTransactions.mutex.withLock {
            val fulfilled = JSONArray(storage.read("acquisition.fulfilled") ?: "[]")
            if ((0 until fulfilled.length()).any { fulfilled.getString(it) == requestId }) return@withLock
            val updated = fairManualQueue(songs() + song.copy(isManual = true))
            fulfilled.put(requestId)
            storage.write(mapOf("local.queue" to JSONArray().apply { updated.forEach { put(encodeSong(it)) } }.toString(),
                "acquisition.fulfilled" to fulfilled.toString()))
            LocalCoreEvents.publish(CoreEvent.QUEUE_CHANGED)
        }
        override suspend fun addAll(songs: List<Song>, next: Boolean) = QueueTransactions.mutex.withLock {
            val current = songs()
            writeSongs("local.queue", if (next) songs + current else current + songs)
            if (songs.isNotEmpty()) LocalCoreEvents.publish(CoreEvent.QUEUE_CHANGED)
        }
        override suspend fun remove(id: String) = QueueTransactions.mutex.withLock {
            writeSongs("local.queue", songs().filterNot { it.id == id })
            LocalCoreEvents.publish(CoreEvent.QUEUE_CHANGED)
        }
        override suspend fun resetAutomaticTail() = QueueTransactions.mutex.withLock {
            storage.write(mapOf("local.mixEpoch" to java.util.UUID.randomUUID().toString()))
            writeSongs("local.queue", songs().filter { it.isManual })
            storage.write(mapOf("local.radioActive" to "false", "local.radioSeed" to "", "local.radioReturnSeed" to "", "local.offlinePlayback" to "false", "local.radioRecent" to "[]", ReplayWindow.STATUS_KEY to ""))
            LocalCoreEvents.publish(CoreEvent.QUEUE_CHANGED)
        }
        override suspend fun clear() = QueueTransactions.mutex.withLock {
            storage.write(mapOf("local.mixEpoch" to java.util.UUID.randomUUID().toString()))
            writeSongs("local.queue", emptyList())
            storage.write(mapOf("local.radioActive" to "false", "local.radioSeed" to "", "local.radioRecent" to "[]", "local.radioReturnSeed" to "", "local.offlinePlayback" to "false"))
            LocalCoreEvents.publish(CoreEvent.QUEUE_CHANGED)
        }
        override suspend fun somewhereDifferent(): Int = QueueTransactions.mutex.withLock {
            val current = playback.snapshot().nowPlaying.song ?: return@withLock 0
            val queued = songs()
            val near = plex.related(source, current.id, 100, TrackRadioSettings(storage).distance)
            val far = plex.related(source, current.id, 500, 0.50)
            val additions = distinctRadioTracks(current, queued + near + readSongs("local.radioRecent"), far)
                .shuffled().take(3).map { it.copy(isManual = false, isRadio = true) }
            if (additions.isEmpty() || !sourceStillSelected()) return@withLock 0
            val stationSeed = if (storage.read("local.radioActive") == "true")
                storage.read("local.radioSeed")?.takeIf { it.isNotBlank() } ?: encodeSong(current).toString()
                else encodeSong(current).toString()
            // Preserve requests; this brief detour replaces only the automatic tail.
            writeSongs("local.queue", queued.filter { it.isManual } + additions)
            storage.write(mapOf("local.offlinePlayback" to "false", "local.radioActive" to "true", "local.radioSeed" to stationSeed, "local.radioReturnSeed" to stationSeed))
            rememberRadioSong(current)
            LocalCoreEvents.publish(CoreEvent.QUEUE_CHANGED)
            additions.size
        }
        override suspend fun radio(): Int = QueueTransactions.mutex.withLock {
            val current = playback.snapshot().nowPlaying.song ?: return@withLock 0
            val queued = songs()
            val station = storage.read("local.radioSeed")?.takeIf { it.isNotBlank() }?.let { decodeSong(JSONObject(it)) }
            if (storage.read("local.radioActive") == "true" && station?.id == current.id && queued.any { it.isRadio }) {
                storage.write(mapOf(ReplayWindow.STATUS_KEY to ""))
                return@withLock 0
            }
            val requests = queued.filter { it.isManual }
            val additions = plex.artistRadio(source, current, requests + readSongs("local.radioRecent"), TrackRadioSettings(storage).distance)
                .map { it.copy(isManual = false, isRadio = true) }
            if (!sourceStillSelected()) return@withLock 0
            if (additions.isEmpty()) {
                storage.write(mapOf(ReplayWindow.STATUS_KEY to ARTIST_RADIO_EMPTY_MESSAGE))
                LocalCoreEvents.publish(CoreEvent.CHANGED)
                return@withLock 0
            }
            storage.write(mapOf("local.offlinePlayback" to "false", "local.radioReturnSeed" to "", "local.radioActive" to "true", "local.radioSeed" to encodeSong(current).toString()))
            rememberRadioSong(current)
            writeSongs("local.queue", requests + additions)
            storage.write(mapOf(ReplayWindow.STATUS_KEY to if (additions.isEmpty()) ARTIST_RADIO_EMPTY_MESSAGE else ""))
            LocalCoreEvents.publish(CoreEvent.QUEUE_CHANGED)
            return@withLock additions.size
        }
        override suspend fun enableAutomaticPlayback() {
            if (storage.read("local.offlinePlayback") == "true") return
            // Keep radio continuity separate from the general automatic mix. The mutex
            // makes simultaneous refill callers observe one batch, and clear cannot
            // be undone by an in-flight refill.
            val radioHandled = QueueTransactions.mutex.withLock {
                if (storage.read("local.radioActive") != "true") return@withLock false
                if (songs().isNotEmpty()) return@withLock true
                val seed = storage.read("local.radioReturnSeed")?.takeIf { it.isNotBlank() }?.let { decodeSong(JSONObject(it)) }
                    ?: storage.read("local.radioSeed")?.takeIf { it.isNotBlank() }?.let { decodeSong(JSONObject(it)) }
                    ?: playback.snapshot().nowPlaying.song
                val additions = if (seed == null) emptyList() else
                    plex.artistRadio(source, seed, readSongs("local.radioRecent"), TrackRadioSettings(storage).distance)
                        .map { it.copy(isManual = false, isRadio = true) }
                if (!sourceStillSelected()) return@withLock true
                writeSongs("local.queue", additions)
                storage.write(mapOf("local.radioReturnSeed" to ""))
                storage.write(mapOf(ReplayWindow.STATUS_KEY to if (additions.isEmpty())
                    ARTIST_RADIO_EMPTY_MESSAGE else ""))
                LocalCoreEvents.publish(CoreEvent.QUEUE_CHANGED)
                true
            }
            if (radioHandled) return
            val settings = offline?.cacheSettings
            val state = playback.snapshot()
            val target = if (settings?.enabled == true) settings.count - (if (state.nowPlaying.song != null) 1 else 0) else 5
            val waiting = songs()
            val topUp = settings?.enabled == true && state.isAutoQueue && offline.connected()
            if (waiting.isEmpty() || (topUp && waiting.size < target)) {
                val excluded = (waiting.map { it.id } + listOfNotNull(state.nowPlaying.song?.id)).toSet()
                val needed = (target - waiting.size).coerceAtLeast(1)
                val epoch = storage.read("local.mixEpoch")
                val cutoff = replayWindow.cutoff(nowMillis())
                val local = recentPlays.snapshot(nowMillis())
                val mode = MixPresetStore(storage).discovery
                val pools = if (mode == MixDiscovery.STANDARD) plex.jukeboxPools(source) { it.id !in excluded && eligibleForAutomaticMix(it, cutoff, local) }
                    else plex.rediscoveryTracks(source, mode).filter {
                        it.id !in excluded && eligibleForAutomaticMix(it, cutoff, local) && rediscoveryEligible(it, mode, nowMillis(), local)
                    }.let { PlexJukeboxPools(it.filter { song -> (song.rating ?: 0.0) > 1.0 }, it.filter { song -> song.rating == null }, it) }
                val share = ratedTrackShare()
                val start = storage.read("local.jukeboxMixIndex")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val selection = chooseJukeboxTracks(pools, needed, share, start, MusicTuningStore(storage).read(),
                    existingSongs = listOfNotNull(state.nowPlaying.song) + waiting)
                // Preserve requests added during candidate loading.
                QueueTransactions.mutex.withLock {
                // A user may have started radio while the general mix was loading.
                if (storage.read("local.radioActive") == "true" || epoch != storage.read("local.mixEpoch") || !sourceStillSelected()) return@withLock
                val latest = songs()
                val playing = playback.snapshot().nowPlaying.song?.id
                val additions = selection.songs.filter { candidate -> candidate.id != playing && latest.none { it.id == candidate.id } }
                    .take((target - latest.size).coerceAtLeast(0)).map { it.copy(isManual = false) }
                val status = if (additions.isEmpty() && latest.isEmpty()) ReplayWindow.EMPTY_MESSAGE else ""
                val statusChanged = storage.read(ReplayWindow.STATUS_KEY) != status
                writeSongs("local.queue", latest + additions)
                storage.write(mapOf(ReplayWindow.STATUS_KEY to status, "local.jukeboxMixIndex" to selection.nextMixIndex.toString()))
                if (additions.isNotEmpty()) LocalCoreEvents.publish(CoreEvent.QUEUE_CHANGED)
                else if (statusChanged) LocalCoreEvents.publish(CoreEvent.CHANGED)
                }
            }
        }
        override suspend fun ratedTrackShare() = storage.read("local.ratedTrackShare")?.toIntOrNull()?.coerceIn(0, 10) ?: 8
        override suspend fun setRatedTrackShare(value: Int) {
            storage.write(mapOf("local.ratedTrackShare" to value.coerceIn(0, 10).toString()))
        }
    }

    override val playback: PlaybackState = object : PlaybackState {
        override suspend fun snapshot(): PlaybackSnapshot {
            val value = storage.read("local.playback")?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: return PlaybackSnapshot(NowPlaying())
            return PlaybackSnapshot(
                NowPlaying(value.optJSONObject("song")?.let(::decodeSong), value.optBoolean("isPlaying")),
                value.optDouble("position", 0.0).coerceAtLeast(0.0),
                value.optBoolean("isAutoQueue"),
            )
        }
        override suspend fun claim() = Unit
        override suspend fun skip() = LocalCoreEvents.publish(CoreEvent.FORCE_SKIP)
        override suspend fun isActivePlayer() = true
        override suspend fun publish(song: Song?, isPlaying: Boolean, isAutoQueue: Boolean) {
            val previous = snapshot()
            if (song != null && isPlaying && (previous.nowPlaying.song?.id != song.id || !previous.nowPlaying.isPlaying)) {
                recentPlays.record(song.id, nowMillis())
                storage.write(mapOf(ReplayWindow.STATUS_KEY to ""))
            }
            val persistedSong = song?.let { value ->
                val sameTrack = previous.nowPlaying.song?.takeIf { it.id == value.id }
                val resolved = if (value.streamUri != null) value
                    else sameTrack?.takeIf { it.streamUri != null }
                        ?: offline?.song(source, value.id) ?: library.track(value.id) ?: value
                // Cached audio metadata deliberately has no upstream URLs. Preserve artwork
                // independently of streamUri when a player callback supplies it or we know it.
                // Keep a server image path behind local cover handles so clearing cache
                // can still fall back to Plex instead of persisting a deleted file URI.
                val incomingArtwork = value.artworkUri?.takeUnless { it.startsWith("file:") }
                val known = if (incomingArtwork == null && sameTrack?.artworkUri == null && resolved.artworkUri == null)
                    queue.songs().firstOrNull { it.id == value.id } else null
                resolved.copy(artworkUri = incomingArtwork ?: sameTrack?.artworkUri ?: resolved.artworkUri ?: known?.artworkUri,
                    coverArt = value.coverArt.ifBlank { sameTrack?.coverArt.orEmpty() }
                        .ifBlank { resolved.coverArt }.ifBlank { known?.coverArt.orEmpty() })
            }
            // Playback callbacks can carry metadata captured before a vote finished.
            val displayedSong = persistedSong?.let { persisted ->
                val current = library.refreshLegacyArtist(persisted)
                previous.nowPlaying.song?.takeIf { it.id == current.id }
                    ?.let { current.copy(rating = it.rating) } ?: current
            }
            val value = JSONObject()
                .put("song", displayedSong?.let(::encodeSong) ?: JSONObject.NULL)
                .put("isPlaying", isPlaying)
                .put("isAutoQueue", isAutoQueue)
                .put("position", if (previous.nowPlaying.song?.id == persistedSong?.id) previous.positionSeconds else 0.0)
            storage.write(mapOf("local.playback" to value.toString()))
            if (displayedSong != null && isPlaying) QueueTransactions.mutex.withLock {
                if (storage.read("local.radioActive") == "true") {
                    if (storage.read("local.radioSeed").isNullOrBlank())
                        storage.write(mapOf("local.radioSeed" to encodeSong(displayedSong).toString()))
                    rememberRadioSong(displayedSong)
                }
            }
            LocalCoreEvents.publish(CoreEvent.CHANGED)
        }
        override suspend fun savePosition(seconds: Double) {
            val state = snapshot()
            val value = JSONObject()
                .put("song", state.nowPlaying.song?.let(::encodeSong) ?: JSONObject.NULL)
                .put("isPlaying", state.nowPlaying.isPlaying)
                .put("isAutoQueue", state.isAutoQueue)
                .put("position", seconds.coerceAtLeast(0.0))
            storage.write(mapOf("local.playback" to value.toString()))
        }
        override suspend fun scrobble(id: String, submission: Boolean) {
            if (sourceStillSelected() && storage.read("local.offlinePlayback") != "true" && source.canWriteToPlex && offline?.connected() != false && submission) plex.scrobble(source, id)
        }
        override suspend fun recordEvent(song: Song, event: String, progress: Double) {
            if (event == "complete" || event == "skip") recentPlays.record(song.id, nowMillis())
            try {
                if (sourceStillSelected() && storage.read("local.offlinePlayback") != "true" && offline?.connected() != false && PlexAccessPolicy.forSource(configuredSource, joinedGuest = false).canRateTracks && AutomaticPlexRatings(storage).enabled && event in setOf("complete", "skip")) {
                    LocalCoreEvents.ratingMutex.withLock {
                        if (AutomaticPlexRatings(storage).enabled) {
                            val current = plex.track(source, song.id)
                            if (current != null) {
                                val adjusted = adjustPersonalRating(current.rating, event, progress, current.viewCount,
                                    MusicTuningStore(storage).read())
                                if (adjusted != (current.rating ?: 5.0) && AutomaticPlexRatings(storage).enabled && sourceStillSelected()) {
                                    val saved = plex.rate(source, song.id, adjusted)
                                    updateDisplayedRating(song.id, saved)
                                }
                            }
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("HarmonicastCore", "Could not update automatic rating", e)
            }
            val history = storage.read("local.playbackHistory")?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()
            history.put(JSONObject().put("song", encodeSong(song)).put("event", event)
                .put("progress", progress.coerceIn(0.0, 1.0)).put("at", nowMillis()))
            val bounded = JSONArray()
            for (index in maxOf(0, history.length() - 500) until history.length()) bounded.put(history.get(index))
            storage.write(mapOf("local.playbackHistory" to bounded.toString()))
        }
    }

    override val guests: GuestControl = object : GuestControl {
        override suspend fun policy() = GuestPolicy(
            isHost = true,
            isActivePlayer = true,
            configured = configuredSource != null,
            needsPlexSetup = false,
            isSetupOwner = true,
        )
        override suspend fun roomVote(up: Boolean) {
            if (source.canWriteToPlex) return vote(up)
            // Shared room reactions never change Plex ratings. Keep the existing
            // automatic-track down-vote skip; manual queue selections remain intact.
            val state = playback.snapshot()
            checkNotNull(state.nowPlaying.song) { "No song is currently playing" }
            if (shouldSkipAfterVote(up, state.isAutoQueue)) LocalCoreEvents.publish(CoreEvent.FORCE_SKIP)
        }
        override suspend fun vote(up: Boolean) = LocalCoreEvents.ratingMutex.withLock {
            check(PlexAccessPolicy.forSource(configuredSource, joinedGuest = false).canRateTracks) { "Connect a personal Plex library to rate tracks" }
            val state = playback.snapshot()
            val current = state.nowPlaying.song
                ?: throw IllegalStateException("No song is currently playing")
            val fresh = plex.track(source, current.id) ?: current
            val points = ((fresh.rating ?: 5.0) * 10).toInt()
            val saved = plex.rate(source, current.id, (points + if (up) 10 else -10).coerceIn(0, 100) / 10.0)
            updateDisplayedRating(current.id, saved)
            if (shouldSkipAfterVote(up, state.isAutoQueue) && playback.snapshot().nowPlaying.song?.id == current.id) LocalCoreEvents.publish(CoreEvent.FORCE_SKIP)
        }
    }

    private fun updateDisplayedRating(id: String, rating: Double) {
        val state = storage.read("local.playback")?.let(::JSONObject) ?: return
        val song = state.optJSONObject("song")?.let(::decodeSong) ?: return
        // A Plex request can finish after the listener has moved to the next track.
        if (song.id != id) return
        state.put("song", encodeSong(song.copy(rating = rating)))
        storage.write(mapOf("local.playback" to state.toString()))
        LocalCoreEvents.publish(CoreEvent.CHANGED)
    }

    // Bounded radio-session memory; source changes and queue clear reset it.
    // Caller holds QueueTransactions.mutex.
    private fun rememberRadioSong(song: Song) {
        val recent = readSongs("local.radioRecent").filterNot { it.id == song.id }
        writeSongs("local.radioRecent", (recent + song).takeLast(100))
    }

    private fun readSongs(key: String): List<Song> = storage.read(key)?.let {
        runCatching {
            val array = JSONArray(it)
            // Entries without provenance belong to the automatic lane.
            if (key == "local.queue") {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    if (!item.has("isManual")) item.put("isManual", false)
                }
            }
            decodeSongs(array)
        }.getOrDefault(emptyList())
    } ?: emptyList()

    private fun writeSongs(key: String, songs: List<Song>) {
        storage.write(mapOf(key to JSONArray().apply { songs.forEach { put(encodeSong(it)) } }.toString()))
    }
}

internal fun shouldSkipAfterVote(up: Boolean, isAutoQueue: Boolean) = !up && isAutoQueue

/** Round-robin each participant's requests while keeping the automatic tail last. */
internal fun fairManualQueue(songs: List<Song>): List<Song> {
    val manual = songs.filter(Song::isManual)
    val participantOrder = manual.map { it.addedByEmail.ifBlank { "Owner" } }.distinct()
    val lanes = participantOrder.associateWith { participant ->
        manual.filter { it.addedByEmail.ifBlank { "Owner" } == participant }
    }
    val fair = buildList {
        val longest = lanes.values.maxOfOrNull { it.size } ?: 0
        for (index in 0 until longest) {
            participantOrder.forEach { participant -> lanes.getValue(participant).getOrNull(index)?.let(::add) }
        }
    }
    return fair + songs.filterNot(Song::isManual)
}

fun harmonicastCore(api: AppStorage): HarmonicastCore = LocalHarmonicastCore(api.profile.personalSource, api.storage, offline = api.offline)

data class JukeboxSelection(val songs: List<Song>, val nextMixIndex: Int)

internal fun chooseJukeboxTracks(
    pools: PlexJukeboxPools,
    count: Int,
    ratedShare: Int,
    mixIndex: Int,
    tuning: MusicTuning = MusicTuning(),
    existingSongs: List<Song> = emptyList(),
    random: () -> Double = Math::random,
): JukeboxSelection {
    val chosen = mutableListOf<Song>()
    val used = mutableSetOf<String>()
    // Rolling cache refills often select just one song. Keep diversity across
    // the current listening window and new batch whenever the pools allow it.
    val albums = existingSongs.mapTo(mutableSetOf(), ::albumKey)
    var cursor = mixIndex.coerceAtLeast(0)
    val ratedSlots = ratedShare.coerceIn(0, 10)
    fun pick(pool: List<Song>, preferUnusedAlbums: Boolean): Song? {
        val eligible = pool.filterNot { it.id in used }
        if (eligible.isEmpty()) return null
        val candidates = if (preferUnusedAlbums) eligible.filterNot { albumKey(it) in albums } else eligible
        if (candidates.isEmpty()) return null
        val weighted = candidates.map { it to selectionWeight(it.rating, tuning) }
        var target = random().coerceIn(0.0, 0.999999) * weighted.sumOf { it.second }
        val song = weighted.firstOrNull { (_, weight) -> target.also { target -= weight } <= weight }?.first
            ?: weighted.last().first
        albums += albumKey(song)
        return song
    }
    while (chosen.size < count) {
        val slot = cursor % 10
        val unratedSlots = 10 - ratedSlots
        val exploration = ((slot + 1) * unratedSlots) / 10 > (slot * unratedSlots) / 10
        val primary = if (exploration) pools.unrated else pools.rated
        val secondary = if (exploration) pools.rated else pools.unrated
        val strictFallback = when (ratedSlots) {
            0 -> pools.fallback.filter { it.rating == null }
            10 -> pools.fallback.filter { (it.rating ?: 0.0) > 1.0 }
            else -> pools.fallback
        }
        // Look for a fresh album in every allowed pool before relaxing album
        // avoidance. Try same-category fallback tracks first to preserve the mix.
        val primaryFallback = strictFallback.filter {
            if (exploration) it.rating == null else (it.rating ?: 0.0) > 1.0
        }
        val allowedPools = listOf(primary, primaryFallback) +
            (if (ratedSlots in 1..9) listOf(secondary) else emptyList()) + listOf(strictFallback)
        val song = allowedPools.firstNotNullOfOrNull { pick(it, preferUnusedAlbums = true) }
            ?: allowedPools.firstNotNullOfOrNull { pick(it, preferUnusedAlbums = false) }
            ?: break
        chosen += song
        used += song.id
        cursor++
    }
    return JukeboxSelection(chosen, cursor)
}

/**
 * Album identity for batch spreading. Legacy queue metadata saved before Plex
 * track/album artist separation has no album, so those tracks keep their own
 * identity instead of collapsing into one "empty album" group.
 */
internal fun albumKey(song: Song): String =
    if (song.album.isBlank()) song.id
    else "${song.albumArtist ?: ""}\u0000${song.album}".lowercase()

internal const val ARTIST_RADIO_EMPTY_MESSAGE = "No fresh songs from this artist or related artists match the starting song's sound. Try Artist Radio from another song or start your automatic mix."

internal fun adjustPersonalRating(rating: Double?, event: String, progress: Double, viewCount: Int,
    tuning: MusicTuning = MusicTuning()): Double {
    val points = (((rating ?: 5.0) * 10).toInt()).coerceIn(0, 100)
    val delta = if (event == "complete") {
        (0.5 * tuning.completionMultiplier * (1 + tuning.repeatMultiplier * kotlin.math.ln(viewCount.coerceAtLeast(0) + 1.0))).roundToInt()
    } else if (event == "skip") {
        -(3 * tuning.skipMultiplier * (1 - progress.coerceIn(0.0, 1.0))).roundToInt()
    } else 0
    return (points + delta).coerceIn(0, 100) / 10.0
}
