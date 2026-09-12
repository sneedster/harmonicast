package io.github.sneedster.harmonicast

/** In-process contracts. Remote transport and JSON belong in adapters, not callers. */
interface MusicLibrary {
    suspend fun browse(kind: BrowseKind, order: BrowseOrder, offset: Int = 0, parent: String? = null, query: String = ""): LibraryPage = LibraryPage(emptyList(), null)
    suspend fun albumTracks(id: String): List<Song> = emptyList()
    suspend fun search(query: String): List<Song>
    suspend fun searchForBrowsing(query: String): List<Song> = search(query)
    /** Album-title matches plus releases by matching artists, with shared albums deduplicated. */
    suspend fun searchAlbums(query: String): List<LibraryEntry> {
        val albums = browse(BrowseKind.ALBUMS, BrowseOrder.TITLE, query = query).entries.toMutableList()
        val artists = browse(BrowseKind.ARTISTS, BrowseOrder.TITLE, query = query).entries
        for (artist in artists) {
            var offset = 0
            do {
                val page = browse(BrowseKind.ALBUMS, BrowseOrder.TITLE, offset = offset, parent = artist.id)
                albums += page.entries
                val next = page.nextOffset ?: break
                if (next <= offset) break
                offset = next
            } while (true)
        }
        return albums.distinctBy { it.id }
    }
    suspend fun track(id: String): Song?
    suspend fun artist(query: String): LibraryArtistBrowse?
    suspend fun discovery(song: Song): ArtistDiscovery
    suspend fun playlists(): List<PlexPlaylist> = emptyList()
    suspend fun playlistTracks(id: String): List<Song> = emptyList()
    suspend fun playlistPage(id: String, offset: Int = 0): PlaylistTrackPage = PlaylistTrackPage(emptyList(), null)
    fun streamUrl(song: Song): String
    fun artworkUrl(song: Song): String?
}

interface MusicQueue {
    suspend fun songs(): List<Song>
    suspend fun dequeue(): QueueSelection
    suspend fun add(song: Song)
    suspend fun addOnce(requestId: String, song: Song) = add(song)
    suspend fun addGuest(song: Song, pending: () -> Int = { 0 }) {
        if (songs().count { it.isManual && it.addedByEmail == song.addedByEmail } + pending() >= 5)
            throw AcquisitionFailure(429, "You already have 5 songs queued or being acquired")
        add(song)
    }
    suspend fun addAll(songs: List<Song>, next: Boolean = false) {
        songs.forEach { add(it) }
    }
    suspend fun remove(id: String)
    suspend fun clear()
    suspend fun radio(): Int
    suspend fun enableAutomaticPlayback()
    suspend fun ratedTrackShare(): Int
    suspend fun setRatedTrackShare(value: Int)
}

data class QueueSelection(val song: Song?, val isManual: Boolean = true)

/** Consume the current queue, seeding the automatic mix only when nothing is waiting. */
suspend fun MusicQueue.dequeueWithAutomaticFallback(): QueueSelection {
    val waiting = dequeue()
    if (waiting.song != null) return waiting
    enableAutomaticPlayback()
    return dequeue()
}

data class PlaybackSnapshot(
    val nowPlaying: NowPlaying,
    val positionSeconds: Double = 0.0,
    val isAutoQueue: Boolean = false,
)

interface PlaybackState {
    suspend fun snapshot(): PlaybackSnapshot
    suspend fun claim()
    suspend fun skip()
    suspend fun isActivePlayer(): Boolean
    suspend fun publish(song: Song?, isPlaying: Boolean, isAutoQueue: Boolean = false)
    suspend fun savePosition(seconds: Double)
    suspend fun scrobble(id: String, submission: Boolean)
    suspend fun recordEvent(song: Song, event: String, progress: Double)
}

/** The local playback host owns these permissions. */
data class GuestPolicy(
    val isHost: Boolean,
    val isActivePlayer: Boolean,
    val configured: Boolean,
    val needsPlexSetup: Boolean,
    val isSetupOwner: Boolean,
)

interface GuestControl {
    suspend fun policy(): GuestPolicy
    suspend fun vote(up: Boolean)
    suspend fun roomVote(up: Boolean) = vote(up)
}

enum class CoreEvent { QUEUE_CHANGED, FORCE_SKIP, PLAYER_SESSION_CHANGED, CHANGED }
fun interface CoreSubscription { fun close() }

interface HarmonicastCore {
    fun observe(onEvent: (CoreEvent) -> Unit, onDisconnected: () -> Unit): CoreSubscription
    val library: MusicLibrary
    val queue: MusicQueue
    val playback: PlaybackState
    val guests: GuestControl
}
