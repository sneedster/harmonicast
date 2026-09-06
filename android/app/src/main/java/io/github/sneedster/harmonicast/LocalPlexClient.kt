package io.github.sneedster.harmonicast

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID

data class PlexPin(val id: Long, val code: String, val authToken: String?, val expiresAt: String?)
data class PlexJukeboxPools(val rated: List<Song>, val unrated: List<Song>, val fallback: List<Song>)

interface PlexHttp {
    suspend fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        form: Map<String, String> = emptyMap(),
    ): String
}

class OkHttpPlexHttp(private val client: OkHttpClient = OkHttpClient()) : PlexHttp {
    override suspend fun request(
        url: String,
        method: String,
        headers: Map<String, String>,
        form: Map<String, String>,
    ): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url)
        headers.forEach { (name, value) -> builder.header(name, value) }
        if (method != "GET") {
            val body = FormBody.Builder().apply { form.forEach { (name, value) -> add(name, value) } }.build()
            builder.method(method, body)
        }
        client.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("Plex request failed (${response.code})")
            body
        }
    }
}

/** Direct Plex owner authentication and source discovery for personal mode. */
class LocalPlexClient(
    private val storage: ProfileStorage,
    private val http: PlexHttp = OkHttpPlexHttp(),
) {
    val clientIdentifier: String
        get() = storage.read("home.plex.clientId")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString().also {
            storage.write(mapOf("home.plex.clientId" to it))
        }

    private fun headers(token: String? = null) = buildMap {
        put("Accept", "application/json")
        put("X-Plex-Client-Identifier", clientIdentifier)
        put("X-Plex-Product", "Harmonicast")
        put("X-Plex-Version", "1.0")
        put("X-Plex-Platform", "Android")
        token?.takeIf { it.isNotBlank() }?.let { put("X-Plex-Token", it) }
    }

    suspend fun createPin(): PlexPin = parsePin(JSONObject(http.request(
        "https://plex.tv/api/v2/pins",
        method = "POST",
        headers = headers(),
        form = mapOf("strong" to "true"),
    )))

    suspend fun readPin(pin: PlexPin): PlexPin = parsePin(JSONObject(http.request(
        "https://plex.tv/api/v2/pins/${pin.id}?code=${encodePlex(pin.code)}",
        headers = headers(),
    )))

    fun authorizationUrl(pin: PlexPin): String {
        val query = listOf(
            "clientID" to clientIdentifier,
            "code" to pin.code,
            "context[device][product]" to "Harmonicast",
        ).joinToString("&") { (name, value) -> "${encodePlex(name)}=${encodePlex(value)}" }
        return "https://app.plex.tv/auth#?$query"
    }

    suspend fun ownedServers(token: String): List<PlexServer> {
        val resources = JSONArray(http.request(
            "https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1",
            headers = headers(token),
        ))
        return List(resources.length()) { resources.getJSONObject(it) }.mapNotNull { item ->
            val provides = item.optString("provides").split(',')
            val machineId = item.optString("clientIdentifier")
            val name = item.optString("name")
            if (!item.optBoolean("owned") || "server" !in provides || machineId.isBlank() || name.isBlank()) return@mapNotNull null
            val rawConnections = item.optJSONArray("connections") ?: JSONArray()
            val connections = List(rawConnections.length()) { rawConnections.getJSONObject(it) }.mapNotNull { connection ->
                normalizeServerUrl(connection.optString("uri"))?.let {
                    PlexConnection(it, connection.optBoolean("local"), connection.optBoolean("relay"))
                }
            }
            PlexServer(machineId, name, connections).takeIf { connections.isNotEmpty() }
        }
    }

    suspend fun connect(token: String, server: PlexServer): String {
        val candidates = server.connections.sortedWith(compareByDescending<PlexConnection> { it.local }.thenBy { it.relay })
        for (candidate in candidates) {
            val identity = runCatching { serverContainer(candidate.uri, token, "/") }.getOrNull() ?: continue
            if (identity.optString("machineIdentifier") == server.machineIdentifier) return candidate.uri
        }
        throw IllegalStateException("Could not reach that Plex server")
    }

    suspend fun musicLibraries(baseUrl: String, token: String): List<PlexLibrary> {
        val directories = serverContainer(baseUrl, token, "/library/sections").optJSONArray("Directory") ?: JSONArray()
        return List(directories.length()) { directories.getJSONObject(it) }.mapNotNull { item ->
            val type = item.opt("type")
            val key = item.optString("key")
            val title = item.optString("title")
            if (type != "artist" && type != 8 || key.isBlank() || title.isBlank()) null
            else PlexLibrary(key, title, item.optString("uuid").takeIf { it.isNotBlank() })
        }
    }

    suspend fun serverContainer(baseUrl: String, token: String, path: String): JSONObject {
        require(path.startsWith('/')) { "Plex API path must be root-relative" }
        val body = JSONObject(http.request("${baseUrl.trimEnd('/')}$path", headers = headers(token)))
        return body.optJSONObject("MediaContainer")
            ?: throw IllegalStateException("Plex server returned an invalid response")
    }

    suspend fun search(source: PersonalPlexSource, query: String): List<Song> {
        val term = query.trim()
        if (term.isBlank()) return emptyList()
        val base = "/library/sections/${source.libraryKey}/search?query=${encodePlex(term)}"
        val direct = songs(source, serverContainer(source.baseUrl, source.token, "$base&type=10&limit=40"))
        val artists = metadataArray(serverContainer(source.baseUrl, source.token, "$base&type=8&limit=8"))
        val albums = metadataArray(serverContainer(source.baseUrl, source.token, "$base&type=9&limit=8"))
        val expanded = mutableListOf<Song>()
        for (artist in artists) {
            val key = artist.optString("ratingKey")
            if (key.matches(Regex("\\d+"))) {
                expanded += songs(source, serverContainer(source.baseUrl, source.token, "/library/metadata/$key/allLeaves?type=10&limit=40"))
            }
        }
        for (album in albums) {
            val key = album.optString("ratingKey")
            if (key.matches(Regex("\\d+"))) {
                expanded += songs(source, serverContainer(source.baseUrl, source.token, "/library/metadata/$key/children?type=10&limit=40"))
            }
        }
        return (direct + expanded).distinctBy(Song::id).take(40)
    }

    suspend fun track(source: PersonalPlexSource, id: String): Song? =
        metadata(source, id)?.let { mapSong(source, it) }

    suspend fun random(source: PersonalPlexSource, limit: Int = 20): List<Song> = songs(
        source,
        serverContainer(
            source.baseUrl,
            source.token,
            "/library/sections/${source.libraryKey}/all?type=10&sort=random&limit=${limit.coerceIn(1, 100)}",
        ),
    )

    suspend fun jukeboxPools(source: PersonalPlexSource, limit: Int = 100): PlexJukeboxPools {
        val bounded = limit.coerceIn(1, 100)
        val path = "/library/sections/${source.libraryKey}/all?type=10&sort=random&limit=$bounded"
        val rated = songs(source, serverContainer(source.baseUrl, source.token, "$path&userRating%3E=1"))
            .filter { (it.rating ?: 0.0) > 1.0 }
        val unrated = songs(source, serverContainer(source.baseUrl, source.token, "$path&userRating=-1"))
            .filter { it.rating == null }
        val fallback = songs(source, serverContainer(source.baseUrl, source.token, path))
        return PlexJukeboxPools(rated, unrated, fallback)
    }

    suspend fun related(source: PersonalPlexSource, id: String, limit: Int = 20): List<Song> {
        val ratingKey = ratingKey(source, id)
        return songs(
            source,
            serverContainer(
                source.baseUrl,
                source.token,
                "/library/metadata/$ratingKey/nearest?limit=${limit.coerceIn(1, 100)}&maxDistance=0.25",
            ),
        ).filter { it.id != id }
    }

    suspend fun playlists(source: PersonalPlexSource): List<PlexPlaylist> {
        val container = serverContainer(source.baseUrl, source.token, "/playlists?playlistType=audio")
        return metadataArray(container).mapNotNull { item ->
            val key = item.optString("ratingKey")
            val title = item.optString("title")
            if (!key.matches(Regex("\\d+")) || title.isBlank()) null
            else PlexPlaylist("plex-playlist:${encodePlex(source.machineIdentifier)}:$key", title, item.optInt("leafCount"))
        }.sortedBy { it.title.lowercase() }
    }

    suspend fun playlistTracks(source: PersonalPlexSource, id: String): List<Song> {
        val prefix = "plex-playlist:${encodePlex(source.machineIdentifier)}:"
        require(id.startsWith(prefix)) { "Plex playlist belongs to another server" }
        val key = id.removePrefix(prefix).takeIf { it.matches(Regex("\\d+")) }
            ?: throw IllegalArgumentException("Invalid Plex playlist id")
        return songs(source, serverContainer(source.baseUrl, source.token, "/playlists/$key/items"))
            .filter { it.id.startsWith("plex:${encodePlex(source.machineIdentifier)}:") && it.streamUri != null }
    }

    suspend fun artist(source: PersonalPlexSource, query: String): LibraryArtistBrowse? {
        val path = "/library/sections/${source.libraryKey}/search?query=${encodePlex(query.trim())}&type=8&limit=8"
        val candidates = metadataArray(serverContainer(source.baseUrl, source.token, path))
        val normalized = normalizeName(query)
        val artist = candidates.firstOrNull {
            normalizeName(it.optString("title")) == normalized && (it.opt("type") == "artist" || it.opt("type") == 8)
        } ?: return null
        val key = artist.optString("ratingKey").takeIf { it.matches(Regex("\\d+")) } ?: return null
        val tracks = serverContainer(source.baseUrl, source.token, "/library/metadata/$key/allLeaves?type=10&limit=300")
        return LibraryArtistBrowse(artist.optString("title"), songs(source, tracks))
    }

    suspend fun discovery(source: PersonalPlexSource, song: Song): ArtistDiscovery {
        val track = metadata(source, song.id) ?: throw IllegalStateException("Plex track was not found")
        val artistKey = track.optString("grandparentRatingKey")
        val albumKey = track.optString("parentRatingKey")
        val artist = if (artistKey.matches(Regex("\\d+"))) {
            metadataArray(serverContainer(source.baseUrl, source.token, "/library/metadata/$artistKey")).firstOrNull()
        } else null
        val album = if (albumKey.matches(Regex("\\d+"))) {
            metadataArray(serverContainer(source.baseUrl, source.token, "/library/metadata/$albumKey")).firstOrNull()
        } else null
        return ArtistDiscovery(
            artist?.optString("title").orEmpty().ifBlank { song.artist },
            artist?.optString("summary").orEmpty(),
            tags(artist?.optJSONArray("Genre")),
            emptyList(),
            album?.optString("title").orEmpty().ifBlank { song.album },
            album?.optInt("year")?.takeIf { it > 0 },
            album?.optString("summary").orEmpty(),
        )
    }

    suspend fun streamUrl(source: PersonalPlexSource, id: String): String {
        val item = metadata(source, id) ?: throw IllegalStateException("Plex track was not found")
        val media = item.optJSONArray("Media") ?: JSONArray()
        for (index in 0 until media.length()) {
            val parts = media.optJSONObject(index)?.optJSONArray("Part") ?: continue
            for (partIndex in 0 until parts.length()) {
                val key = parts.optJSONObject(partIndex)?.optString("key").orEmpty()
                if (key.startsWith('/')) return authenticatedUrl(source, key)
            }
        }
        throw IllegalStateException("Plex track has no playable media")
    }

    suspend fun artworkUrl(source: PersonalPlexSource, id: String): String? {
        val thumb = metadata(source, id)?.optString("thumb").orEmpty()
        return thumb.takeIf { it.startsWith('/') }?.let { authenticatedUrl(source, it) }
    }

    suspend fun scrobble(source: PersonalPlexSource, id: String) {
        val key = ratingKey(source, id)
        http.request(
            authenticatedUrl(source, "/:/scrobble?identifier=com.plexapp.plugins.library&key=$key"),
            method = "PUT",
            headers = headers(source.token),
        )
    }

    suspend fun rate(source: PersonalPlexSource, id: String, rating: Double): Double {
        val key = ratingKey(source, id)
        val quantized = (rating.coerceIn(0.0, 10.0) * 10).toInt().coerceIn(0, 100) / 10.0
        val path = "/:/rate?identifier=com.plexapp.plugins.library&key=$key&rating=$quantized"
        http.request(authenticatedUrl(source, path), method = "PUT", headers = headers(source.token))
        return quantized
    }

    private suspend fun metadata(source: PersonalPlexSource, id: String): JSONObject? {
        val key = ratingKey(source, id)
        val item = metadataArray(serverContainer(source.baseUrl, source.token, "/library/metadata/$key")).firstOrNull()
        return item?.takeIf { it.optString("librarySectionID") == source.libraryKey }
    }

    private fun ratingKey(source: PersonalPlexSource, id: String): String {
        val prefix = "plex:${encodePlex(source.machineIdentifier)}:"
        require(id.startsWith(prefix)) { "Plex track belongs to another server" }
        return id.removePrefix(prefix).takeIf { it.matches(Regex("\\d+")) }
            ?: throw IllegalArgumentException("Invalid Plex track id")
    }

    private fun songs(source: PersonalPlexSource, container: JSONObject) =
        metadataArray(container).mapNotNull { mapSong(source, it) }

    private fun mapSong(source: PersonalPlexSource, item: JSONObject): Song? {
        if (item.opt("type") != "track" && item.opt("type") != 10) return null
        val key = item.optString("ratingKey")
        val title = item.optString("title")
        if (!key.matches(Regex("\\d+")) || title.isBlank()) return null
        val year = item.optInt("parentYear").takeIf { it > 0 } ?: item.optInt("year").takeIf { it > 0 }
        return Song(
            id = "plex:${encodePlex(source.machineIdentifier)}:$key",
            title = title,
            artist = item.optString("grandparentTitle").ifBlank { item.optString("originalTitle", "Unknown artist") },
            album = item.optString("parentTitle"),
            duration = (item.optLong("duration").coerceAtLeast(0) / 1000).toInt(),
            coverArt = "plex:${encodePlex(source.machineIdentifier)}:$key",
            rating = item.optDouble("userRating").takeIf { !item.isNull("userRating") },
            year = year,
            streamUri = firstPart(item)?.let { authenticatedUrl(source, it) },
            artworkUri = item.optString("thumb").takeIf { it.startsWith('/') }?.let { authenticatedUrl(source, it) },
            viewCount = item.optInt("viewCount").coerceAtLeast(0),
        )
    }

    private fun firstPart(item: JSONObject): String? {
        val media = item.optJSONArray("Media") ?: return null
        for (index in 0 until media.length()) {
            val parts = media.optJSONObject(index)?.optJSONArray("Part") ?: continue
            for (partIndex in 0 until parts.length()) {
                val key = parts.optJSONObject(partIndex)?.optString("key").orEmpty()
                if (key.startsWith('/')) return key
            }
        }
        return null
    }

    private fun metadataArray(container: JSONObject): List<JSONObject> {
        val metadata = container.optJSONArray("Metadata") ?: return emptyList()
        return List(metadata.length()) { metadata.optJSONObject(it) }.filterNotNull()
    }

    private fun authenticatedUrl(source: PersonalPlexSource, path: String): String {
        val separator = if ('?' in path) '&' else '?'
        return "${source.baseUrl.trimEnd('/')}$path${separator}X-Plex-Token=${encodePlex(source.token)}"
    }

    private fun tags(values: JSONArray?) = values?.let { array ->
        List(array.length()) { array.optJSONObject(it)?.optString("tag").orEmpty() }.filter { it.isNotBlank() }
    } ?: emptyList()

    private fun parsePin(value: JSONObject): PlexPin {
        val id = value.optLong("id")
        val code = value.optString("code")
        require(id > 0 && code.isNotBlank()) { "Plex returned an invalid PIN" }
        return PlexPin(
            id,
            code,
            value.opt("authToken")
                ?.takeUnless { it == JSONObject.NULL }
                ?.toString()
                ?.takeIf { it.isNotBlank() },
            value.opt("expiresAt")
                ?.takeUnless { it == JSONObject.NULL }
                ?.toString()
                ?.takeIf { it.isNotBlank() },
        )
    }
}

internal fun normalizeServerUrl(raw: String): String? = runCatching {
    val uri = java.net.URI(raw.trim())
    if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) return null
    val path = (uri.path ?: "").replace(Regex("/web/?$"), "")
    java.net.URI(uri.scheme, uri.userInfo, uri.host, uri.port, path.ifBlank { null }, null, null).toString().trimEnd('/')
}.getOrNull()

private fun encodePlex(value: String) = URLEncoder.encode(value, "UTF-8")
private fun normalizeName(value: String) = value.lowercase().filter { it.isLetterOrDigit() }
