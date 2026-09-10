package io.github.sneedster.harmonicast

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt

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
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : HarmonicastCore {
    private val replayWindow = ReplayWindow(storage)
    private val recentPlays = RecentTrackPlays(storage)
    private val source: PersonalPlexSource get() = checkNotNull(configuredSource) { "Sign in with Plex first" }
    override fun observe(onEvent: (CoreEvent) -> Unit, onDisconnected: () -> Unit): CoreSubscription {
        LocalCoreEvents.listeners += onEvent
        return CoreSubscription { LocalCoreEvents.listeners -= onEvent }
    }

    override val library: MusicLibrary = object : MusicLibrary {
        override suspend fun browse(kind: BrowseKind, order: BrowseOrder, offset: Int, parent: String?, query: String) = plex.browse(source, kind, order, offset, parent, query)
        override suspend fun albumTracks(id: String) = plex.albumTracks(source, id)
        override suspend fun search(query: String) = plex.search(source, query)
        override suspend fun searchForBrowsing(query: String) = plex.search(source, query, expandAlbums = false, expandArtists = false)
        override suspend fun track(id: String) = plex.track(source, id)
        override suspend fun artist(query: String) = plex.artist(source, query)
        override suspend fun discovery(song: Song) = plex.discovery(source, song)
        override suspend fun playlists() = plex.playlists(source)
        override suspend fun playlistTracks(id: String) = plex.playlistTracks(source, id)
        override suspend fun playlistPage(id: String, offset: Int) = plex.playlistPage(source, id, offset)
        override fun streamUrl(song: Song) = song.streamUri
            ?: throw IllegalStateException("Plex track needs fresh playback metadata")
        override fun artworkUrl(song: Song) = song.artworkUri
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
                var selected: Song? = candidate
                if (automatic && cutoff != null) {
                    val local = recentPlays.snapshot(nowMillis())
                    selected = if (!eligibleForAutomaticMix(candidate, cutoff, local)) null
                        else plex.track(source, candidate.id)?.copy(isManual = false)
                    // Re-read preferences/history after the metadata request. Another
                    // device may have played it, or the user may have changed the window.
                    if (selected != null && !eligibleForAutomaticMix(selected,
                            replayWindow.cutoff(nowMillis()), recentPlays.snapshot(nowMillis()))) selected = null
                }
                // A request may have arrived while Plex metadata was loading. Respect
                // the new queue head and never overwrite newly added requests.
                val latest = songs()
                if (latest.firstOrNull() != candidate) continue
                writeSongs("local.queue", latest.drop(1))
                if (selected == null) removedAutomatic = true
                LocalCoreEvents.publish(CoreEvent.QUEUE_CHANGED)
                if (selected != null) {
                    storage.write(mapOf(ReplayWindow.STATUS_KEY to ""))
                    return QueueSelection(selected, candidate.isManual)
                }
            }
        }
        override suspend fun add(song: Song) {
            val current = songs()
            val requested = song.copy(isManual = true)
            val updated = fairManualQueue(current + requested)
            writeSongs("local.queue", updated)
            LocalCoreEvents.publish(CoreEvent.QUEUE_CHANGED)
        }
        override suspend fun addAll(songs: List<Song>, next: Boolean) {
            val current = songs()
            writeSongs("local.queue", if (next) songs + current else current + songs)
            if (songs.isNotEmpty()) LocalCoreEvents.publish(CoreEvent.QUEUE_CHANGED)
        }
        override suspend fun remove(id: String) {
            writeSongs("local.queue", songs().filterNot { it.id == id })
            LocalCoreEvents.publish(CoreEvent.QUEUE_CHANGED)
        }
        override suspend fun clear() {
            writeSongs("local.queue", emptyList())
            LocalCoreEvents.publish(CoreEvent.QUEUE_CHANGED)
        }
        override suspend fun radio(): Int {
            val current = playback.snapshot().nowPlaying.song ?: return 0
            val existing = songs().mapTo(mutableSetOf(), Song::id)
            val additions = plex.related(source, current.id).filter { existing.add(it.id) }
                .map { it.copy(isManual = false, isRadio = true) }
            if (additions.isNotEmpty()) {
                writeSongs("local.queue", songs() + additions)
                LocalCoreEvents.publish(CoreEvent.QUEUE_CHANGED)
            }
            return additions.size
        }
        override suspend fun enableAutomaticPlayback() {
            if (songs().isEmpty()) {
                val cutoff = replayWindow.cutoff(nowMillis())
                val local = recentPlays.snapshot(nowMillis())
                val pools = plex.jukeboxPools(source) { eligibleForAutomaticMix(it, cutoff, local) }
                val share = ratedTrackShare()
                val start = storage.read("local.jukeboxMixIndex")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val selection = chooseJukeboxTracks(pools, 5, share, start, MusicTuningStore(storage).read())
                // Preserve requests added during candidate loading.
                writeSongs("local.queue", songs() + selection.songs.map { it.copy(isManual = false) })
                storage.write(mapOf(ReplayWindow.STATUS_KEY to if (selection.songs.isEmpty() && songs().isEmpty())
                    ReplayWindow.EMPTY_MESSAGE else ""))
                storage.write(mapOf("local.jukeboxMixIndex" to selection.nextMixIndex.toString()))
                LocalCoreEvents.publish(CoreEvent.QUEUE_CHANGED)
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
                if (value.streamUri != null) value
                else previous.nowPlaying.song?.takeIf { it.id == value.id && it.streamUri != null }
                    ?: plex.track(source, value.id) ?: value
            }
            // Playback callbacks can carry metadata captured before a vote finished.
            val displayedSong = persistedSong?.let { current ->
                previous.nowPlaying.song?.takeIf { it.id == current.id }
                    ?.let { current.copy(rating = it.rating) } ?: current
            }
            val value = JSONObject()
                .put("song", displayedSong?.let(::encodeSong) ?: JSONObject.NULL)
                .put("isPlaying", isPlaying)
                .put("isAutoQueue", isAutoQueue)
                .put("position", if (previous.nowPlaying.song?.id == persistedSong?.id) previous.positionSeconds else 0.0)
            storage.write(mapOf("local.playback" to value.toString()))
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
            if (source.canWriteToPlex && submission) plex.scrobble(source, id)
        }
        override suspend fun recordEvent(song: Song, event: String, progress: Double) {
            if (event == "complete" || event == "skip") recentPlays.record(song.id, nowMillis())
            try {
                if (source.canWriteToPlex && AutomaticPlexRatings(storage).enabled && event in setOf("complete", "skip")) {
                    LocalCoreEvents.ratingMutex.withLock {
                        if (AutomaticPlexRatings(storage).enabled) {
                            val current = plex.track(source, song.id)
                            if (current != null) {
                                val adjusted = adjustPersonalRating(current.rating, event, progress, current.viewCount,
                                    MusicTuningStore(storage).read())
                                if (adjusted != (current.rating ?: 5.0) && AutomaticPlexRatings(storage).enabled) {
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
        override suspend fun vote(up: Boolean) = LocalCoreEvents.ratingMutex.withLock {
            check(source.canWriteToPlex) { "Ratings are unavailable on a shared read-only Plex server" }
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

fun harmonicastCore(api: AppStorage): HarmonicastCore = LocalHarmonicastCore(api.profile.personalSource, api.storage)

data class JukeboxSelection(val songs: List<Song>, val nextMixIndex: Int)

internal fun chooseJukeboxTracks(
    pools: PlexJukeboxPools,
    count: Int,
    ratedShare: Int,
    mixIndex: Int,
    tuning: MusicTuning = MusicTuning(),
    random: () -> Double = Math::random,
): JukeboxSelection {
    val chosen = mutableListOf<Song>()
    val used = mutableSetOf<String>()
    var cursor = mixIndex.coerceAtLeast(0)
    val ratedSlots = ratedShare.coerceIn(0, 10)
    fun pick(pool: List<Song>): Song? {
        val candidates = pool.filterNot { it.id in used }
        if (candidates.isEmpty()) return null
        val weighted = candidates.map { it to selectionWeight(it.rating, tuning) }
        var target = random().coerceIn(0.0, 0.999999) * weighted.sumOf { it.second }
        return weighted.firstOrNull { (_, weight) -> target.also { target -= weight } <= weight }?.first
            ?: weighted.last().first
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
        val song = pick(primary)
            ?: (if (ratedSlots in 1..9) pick(secondary) else null)
            ?: pick(strictFallback)
            ?: break
        chosen += song
        used += song.id
        cursor++
    }
    return JukeboxSelection(chosen, cursor)
}

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
