package io.github.sneedster.harmonicast

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

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
data class MusicSourceExtension(val id: String, val displayName: String, val available: Boolean)
data class MusicSourceRecording(val id: String, val title: String, val artist: String, val album: String?, val year: String?, val durationMs: Long?, val disambiguation: String?)
data class MusicSourceAlbum(val id: String, val title: String, val year: String?, val type: String?)
data class MusicSourceArtist(val id: String, val name: String)
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

class Api(private val prefs: android.content.SharedPreferences) : RemoteApi {
    private val http = OkHttpClient()
    val storage = SharedPreferencesProfileStorage(prefs)
    val profile = HomeProfileStore(storage)
    override val base: String get() = profile.base
    override val token: String get() = profile.token
    fun setBase(value: String) = profile.setBase(value)
    fun setToken(value: String) = profile.setToken(value)

    // Using a more standard request building
    private fun buildRequest(path: String, method: String = "GET", body: JSONObject? = null): Request {
        val t = token
        val b = base
        android.util.Log.d("HarmonicastApi", "buildRequest path: $path, token length: ${t.length}")
        val url = if (b.isEmpty()) "" else "$b/api/$path"
        val builder = Request.Builder().url(url).header("Authorization", "Bearer $t")
        if (method != "GET") {
            val mediaType = "application/json".toMediaType()
            val requestBody = (body?.toString() ?: "").toRequestBody(mediaType)
            builder.method(method, requestBody)
        }
        return builder.build()
    }

    override suspend fun json(path: String, method: String, body: JSONObject?): String = withContext(Dispatchers.IO) {
        http.newCall(buildRequest(path, method, body)).execute().use { response ->
            val value = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                val message = runCatching { JSONObject(value).optString("error") }
                    .getOrDefault("Request failed (${response.code})")
                android.util.Log.e("HarmonicastApi", "$method /api/$path failed (${response.code}): $message")
                throw IllegalStateException(message.ifBlank { "Request failed (${response.code})" })
            }
            value
        }
    }
    override fun observe(onMessage: (String) -> Unit, onDisconnected: () -> Unit): CoreSubscription {
        val socket = websocket(onMessage, onDisconnected)
        return CoreSubscription { socket.close(1000, null) }
    }
    private fun websocket(onMessage: (String) -> Unit, onDisconnected: () -> Unit = {}): WebSocket {
        val t = token
        val b = base
        return http.newWebSocket(
            Request.Builder().url(b.replaceFirst(Regex("^http"), "ws") + "/ws").header("Sec-WebSocket-Protocol", t).build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) = onMessage(text)
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onDisconnected()
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = onDisconnected()
            }
        )
    }
}
