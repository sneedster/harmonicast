package io.github.sneedster.harmonicast

import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/** JSON transport is injectable so wire compatibility can be checked independently. */
interface RemoteApi {
    fun observe(onMessage: (String) -> Unit, onDisconnected: () -> Unit): CoreSubscription
    val base: String
    val token: String
    suspend fun json(path: String, method: String = "GET", body: JSONObject? = null): String
}

/** Adapter for the existing deployment; both phone UI and Auto use the home core. */
class RemoteHarmonicastCore(private val api: RemoteApi) : HarmonicastCore {
    override fun observe(onEvent: (CoreEvent) -> Unit, onDisconnected: () -> Unit) = api.observe(
        onMessage = { text ->
            val type = runCatching { JSONObject(text).optString("type") }.getOrNull()
            onEvent(when (type) {
                "queue" -> CoreEvent.QUEUE_CHANGED
                "force_skip" -> CoreEvent.FORCE_SKIP
                "player_session" -> CoreEvent.PLAYER_SESSION_CHANGED
                else -> CoreEvent.CHANGED
            })
        }, onDisconnected = onDisconnected,
    )
    override val library: MusicLibrary = object : MusicLibrary {
        override suspend fun search(query: String) = decodeSongs(JSONArray(api.json("search?q=${encode(query)}")))
        override suspend fun track(id: String): Song? {
            val item = JSONObject(api.json("plex/tracks/${encode(id)}"))
            return decodeSong(item).copy(id = item.optString("id", id)).takeIf { it.title.isNotBlank() }
        }
        override suspend fun artist(query: String): LibraryArtistBrowse? =
            JSONObject(api.json("search/artist?q=${encode(query)}")).optJSONObject("artist")?.let {
                LibraryArtistBrowse(it.optString("name", query), decodeSongs(it.optJSONArray("songs") ?: JSONArray()))
            }
        override suspend fun discovery(song: Song): ArtistDiscovery {
            val item = JSONObject(api.json("plex/tracks/${encode(song.id)}/discovery"))
            fun strings(key: String) = item.optJSONArray(key)?.let { a ->
                List(a.length()) { a.optString(it) }.filter { it.isNotBlank() }
            } ?: emptyList()
            val album = item.optJSONObject("album")
            return ArtistDiscovery(item.optString("name", song.artist), item.optString("bio"),
                strings("genres"), strings("similarArtists"), album?.optString("name").orEmpty(),
                album?.takeIf { it.has("year") && !it.isNull("year") }?.optInt("year"),
                album?.optString("summary").orEmpty())
        }
        override fun streamUrl(song: Song) = "${api.base}/api/stream/${encode(song.id)}?token=${encode(api.token)}"
        override fun artworkUrl(song: Song): String? = song.coverArt.takeIf { it.isNotEmpty() }?.let {
            "${api.base}/api/cover-art/${encode(it)}?token=${encode(api.token)}&size=300"
        }
    }
    override val queue: MusicQueue = object : MusicQueue {
        override suspend fun dequeue(): QueueSelection {
            val state = JSONObject(api.json("queue/dequeue", "POST", JSONObject()))
            return QueueSelection(state.optJSONObject("song")?.let(::decodeSong), state.optBoolean("isManual", true))
        }
        override suspend fun songs() = decodeSongs(JSONArray(api.json("queue")))
        override suspend fun add(song: Song) { api.json("queue", "POST", JSONObject().put("song", encodeSong(song))) }
        override suspend fun addAll(songs: List<Song>, next: Boolean) {
            val ordered = if (next) songs.asReversed() else songs
            ordered.forEach { song ->
                api.json("queue", "POST", JSONObject().put("song", encodeSong(song)).put("next", next))
            }
        }
        override suspend fun remove(id: String) { api.json("queue/${encode(id)}", "DELETE", JSONObject()) }
        override suspend fun clear() { api.json("queue", "DELETE", JSONObject()) }
        override suspend fun radio() = JSONObject(api.json("queue/similar", "POST", JSONObject())).optInt("added", 0)
        override suspend fun ratedTrackShare() = JSONObject(api.json("settings")).optInt("ratedTrackShare", 8).coerceIn(0, 10)
        override suspend fun setRatedTrackShare(value: Int) {
            api.json("settings", "PUT", JSONObject().put("ratedTrackShare", value.coerceIn(0, 10)))
        }
        override suspend fun enableAutomaticPlayback() { api.json("jukebox", "POST", JSONObject().put("enabled", true)) }
    }
    override val playback: PlaybackState = object : PlaybackState {
        override suspend fun snapshot(): PlaybackSnapshot {
            val state = JSONObject(api.json("now-playing"))
            return PlaybackSnapshot(
                NowPlaying(state.optJSONObject("song")?.let(::decodeSong), state.optBoolean("isPlaying")),
                state.optDouble("playbackPosition", 0.0).coerceAtLeast(0.0),
                state.optBoolean("isAutoQueue", false),
            )
        }
        override suspend fun claim() { api.json("player/claim", "POST", JSONObject()) }
        override suspend fun skip() { api.json("player/skip", "POST", JSONObject()) }
        override suspend fun isActivePlayer() = JSONObject(api.json("player/status")).optBoolean("isActivePlayer", false)
        override suspend fun publish(song: Song?, isPlaying: Boolean, isAutoQueue: Boolean) {
            val body = JSONObject().put("song", song?.let {
                JSONObject().put("id", it.id).put("title", it.title).put("artist", it.artist)
                    .put("album", it.album).put("coverArt", it.coverArt)
            } ?: JSONObject.NULL).put("isPlaying", isPlaying)
            if (song != null) body.put("isAutoQueue", isAutoQueue)
            api.json("now-playing", "POST", body)
        }
        override suspend fun savePosition(seconds: Double) {
            api.json("now-playing/position", "PUT", JSONObject().put("position", seconds))
        }
        override suspend fun scrobble(id: String, submission: Boolean) {
            api.json("scrobble", "POST", JSONObject().put("id", id).put("submission", submission))
        }
        override suspend fun recordEvent(song: Song, event: String, progress: Double) {
            api.json("stats/play-event", "POST", JSONObject().put("song_id", song.id)
                .put("title", song.title).put("artist", song.artist).put("album", song.album)
                .put("event", event).put("progress", progress.coerceIn(0.0, 1.0)))
        }

    }
    override val guests: GuestControl = object : GuestControl {
        override suspend fun policy(): GuestPolicy {
            val state = JSONObject(api.json("connection"))
            return GuestPolicy(state.optBoolean("isHost"), state.optBoolean("isActivePlayer"),
                state.optBoolean("configured"), state.optBoolean("needsPlexSetup"), state.optBoolean("isSetupOwner"))
        }
        override suspend fun vote(up: Boolean) { api.json("vote", "POST", JSONObject().put("vote", if (up) "up" else "down")) }
    }
}

internal fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
internal fun decodeSongs(array: JSONArray): List<Song> = List(array.length()) { decodeSong(array.getJSONObject(it)) }
internal fun decodeSong(item: JSONObject) = Song(
    item.optString("id"), item.optString("title"), item.optString("artist"), item.optString("album"),
    item.optInt("duration"), item.optString("coverArt"),
    if (item.has("rating") && !item.isNull("rating")) item.optDouble("rating").coerceIn(0.0, 10.0) else null,
    item.optString("addedByEmail"), item.optBoolean("isManual", true), item.optBoolean("isRadio", false),
    if (item.has("year") && !item.isNull("year")) item.optInt("year").takeIf { it > 0 } else null,
    item.optString("streamUri").takeIf { it.isNotBlank() },
    item.optString("artworkUri").takeIf { it.isNotBlank() },
    item.optInt("viewCount").coerceAtLeast(0),
)
internal fun encodeSong(song: Song) = JSONObject().put("id", song.id).put("title", song.title)
    .put("artist", song.artist).put("album", song.album).put("year", song.year)
    .put("duration", song.duration).put("coverArt", song.coverArt)
    .put("rating", song.rating ?: JSONObject.NULL).put("addedByEmail", song.addedByEmail)
    .put("isManual", song.isManual).put("isRadio", song.isRadio)
    .put("streamUri", song.streamUri).put("artworkUri", song.artworkUri)
    .put("viewCount", song.viewCount)
