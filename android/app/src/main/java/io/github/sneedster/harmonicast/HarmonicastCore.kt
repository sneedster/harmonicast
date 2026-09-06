package io.github.sneedster.harmonicast

/** In-process contracts. Remote transport and JSON belong in adapters, not callers. */
interface MusicLibrary {
    suspend fun search(query: String): List<Song>
    suspend fun track(id: String): Song?
    suspend fun artist(query: String): LibraryArtistBrowse?
    suspend fun discovery(song: Song): ArtistDiscovery
    suspend fun playlists(): List<PlexPlaylist> = emptyList()
    suspend fun playlistTracks(id: String): List<Song> = emptyList()
    fun streamUrl(song: Song): String
    fun artworkUrl(song: Song): String?
}

interface MusicQueue {
    suspend fun songs(): List<Song>
    suspend fun dequeue(): QueueSelection
    suspend fun add(song: Song)
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

/** The remote server remains authoritative for these permissions. */
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
