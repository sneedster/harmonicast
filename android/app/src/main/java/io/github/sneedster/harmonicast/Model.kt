package io.github.sneedster.harmonicast


data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val duration: Int = 0,
    val coverArt: String = "",
    val rating: Double? = null,
    val addedByEmail: String = "",
    val isManual: Boolean = true,
    val isRadio: Boolean = false,
    val year: Int? = null,
    val streamUri: String? = null,
    val artworkUri: String? = null,
    val viewCount: Int = 0,
    /** Plex lastViewedAt converted from epoch seconds; null means no known Plex play. */
    val lastPlayedAtMillis: Long? = null,
)
data class NowPlaying(val song: Song? = null, val isPlaying: Boolean = false)
data class ArtistDiscovery(val name: String, val bio: String, val genres: List<String>, val similarArtists: List<String>, val albumName: String, val albumYear: Int?, val albumSummary: String)
data class PlexConnection(val uri: String, val local: Boolean, val relay: Boolean)
data class PlexServer(
    val machineIdentifier: String,
    val name: String,
    val connections: List<PlexConnection> = emptyList(),
    val owned: Boolean = true,
    val accessToken: String? = null,
)
data class PlexLibrary(val key: String, val title: String, val uuid: String? = null)
data class PlexPlaylist(val id: String, val title: String, val trackCount: Int = 0)
data class LibraryArtistBrowse(val name: String, val songs: List<Song>)

class SharedPreferencesProfileStorage(
    private val prefs: android.content.SharedPreferences,
) : ProfileStorage {
    override fun read(key: String) = prefs.getString(key, null)
    override fun write(values: Map<String, String>) {
        val editor = prefs.edit()
        values.forEach { (key, value) -> editor.putString(key, value) }
        check(editor.commit()) { "Could not save home profile" }
    }
}

class AppStorage(prefs: android.content.SharedPreferences) {
    val storage = SharedPreferencesProfileStorage(prefs)
    val profile = HomeProfileStore(storage)
}

data class LibraryLetter(val title: String, val offset: Int, val count: Int)

data class TrackSearchPage(val songs: List<Song>, val total: Int)
