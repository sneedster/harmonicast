package io.github.sneedster.harmonicast

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class Song(val id: String, val title: String, val artist: String, val album: String = "", val duration: Int = 0, val coverArt: String = "", val rating: Int? = null, val addedByEmail: String = "", val isManual: Boolean = true, val isRadio: Boolean = false)
data class NowPlaying(val song: Song? = null, val isPlaying: Boolean = false)
data class ArtistDiscovery(val name: String, val bio: String, val genres: List<String>, val similarArtists: List<String>, val albumName: String, val albumYear: Int?, val albumSummary: String)
data class PlexServer(val machineIdentifier: String, val name: String)
data class PlexLibrary(val key: String, val title: String)

class Api(private val prefs: android.content.SharedPreferences) {
    private val http = OkHttpClient()
    // Always fetch directly from prefs so we get updates made by other processes
    val base: String get() = prefs.getString("base", "") ?: ""
    val token: String get() = prefs.getString("token", "") ?: ""
    
    fun setBase(value: String) {
        prefs.edit().putString("base", value.trimEnd('/')).commit()
        android.util.Log.d("HarmonicastApi", "Base URL saved: $value")
    }
    
    fun setToken(value: String) {
        prefs.edit().putString("token", value).commit()
        android.util.Log.d("HarmonicastApi", "Token saved, length: ${value.length}")
    }
    
    // Using a more standard request building
    private fun buildRequest(path: String, method: String = "GET", body: JSONObject? = null): Request {
        val t = prefs.getString("token", "") ?: ""
        val b = prefs.getString("base", "") ?: ""
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

    suspend fun json(path: String, method: String = "GET", body: JSONObject? = null): String = withContext(Dispatchers.IO) {
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
    fun websocket(onMessage: (String) -> Unit, onDisconnected: () -> Unit = {}): WebSocket {
        val t = prefs.getString("token", "") ?: ""
        val b = prefs.getString("base", "") ?: ""
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
