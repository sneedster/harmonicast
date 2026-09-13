package io.github.sneedster.harmonicast

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class RoomShareState(
    val enabled: Boolean = false,
    val nearbyAvailable: Boolean = false,
    val roomCode: String = "",
    val joinUrl: String = "",
    val displayUrl: String = "",
    val appJoinUrl: String = "",
    val port: Int = 0,
    val expiresAtMillis: Long = 0,
    val error: String = "",
    val checkingAccess: Boolean = false,
    val guestEntryUrl: String = "",
    val displayEntryUrl: String = "",
    val displayEntryCode: String = "",
)

enum class RoomTransportKind(val wireName: String) {
    NEARBY("nearby"),
    LAN("lan");

    companion object {
        fun fromWireName(value: String) = entries.firstOrNull { it.wireName == value }
    }
}

data class RoomEndpoint(val kind: RoomTransportKind, val address: String)

/** Transport-neutral invitation. The capability authorizes a room, never the owner's Plex account. */
data class RoomJoinPayload(
    val roomCode: String,
    val capability: String,
    val expiresAtMillis: Long,
    val endpoints: List<RoomEndpoint>,
    val version: Int = CURRENT_VERSION,
) {
    companion object { const val CURRENT_VERSION = 1 }
}

object RoomJoinPayloadCodec {
    fun encode(payload: RoomJoinPayload): String {
        require(payload.version == RoomJoinPayload.CURRENT_VERSION)
        require(payload.roomCode.isNotBlank() && payload.capability.isNotBlank() && payload.endpoints.isNotEmpty())
        val json = JSONObject()
            .put("v", payload.version)
            .put("room", payload.roomCode)
            .put("cap", payload.capability)
            .put("exp", payload.expiresAtMillis)
            .put("endpoints", JSONArray().apply {
                payload.endpoints.forEach { endpoint ->
                    put(JSONObject().put("kind", endpoint.kind.wireName).put("address", endpoint.address))
                }
            })
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.toString().toByteArray(StandardCharsets.UTF_8))
    }

    fun decode(encoded: String, nowMillis: Long = System.currentTimeMillis()): RoomJoinPayload? = runCatching {
        val json = JSONObject(String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8))
        if (json.optInt("v", -1) != RoomJoinPayload.CURRENT_VERSION) return null
        val endpointsJson = json.optJSONArray("endpoints") ?: return null
        val endpoints = buildList {
            for (index in 0 until endpointsJson.length()) {
                val item = endpointsJson.optJSONObject(index) ?: continue
                val kind = RoomTransportKind.fromWireName(item.optString("kind")) ?: continue
                val address = item.optString("address").trim()
                if (address.isNotEmpty()) add(RoomEndpoint(kind, address))
            }
        }
        val payload = RoomJoinPayload(
            roomCode = json.optString("room").trim(),
            capability = json.optString("cap").trim(),
            expiresAtMillis = json.optLong("exp", 0),
            endpoints = endpoints,
        )
        payload.takeIf {
            it.roomCode.isNotEmpty() && it.capability.isNotEmpty() &&
                it.expiresAtMillis > nowMillis && it.endpoints.isNotEmpty()
        }
    }.getOrNull()

    fun deepLink(payload: RoomJoinPayload) = "harmonicast://join?p=${encode(payload)}"
}

data class GuestApiRequest(
    val method: String,
    val path: String,
    val bearer: String?,
    val query: Map<String, String> = emptyMap(),
    val body: String = "",
    val participantId: String = "",
)

data class GuestApiResponse(
    val status: Int,
    val body: String,
    val contentType: String = "application/json; charset=utf-8",
)

object GuestWebPage {
    fun renderEntry(template: String, guest: Boolean): String = template
        .replace("__ENTRY_KIND__", if (guest) "guest" else "display")
        .replace("__ENTRY_TITLE__", if (guest) "Join room" else "Open room display")
        .replace("__ENTRY_LOCATION__", if (guest) "Rooms → Invite guests" else "Rooms → Open room display")
        .replace("__ENTRY_DESCRIPTION__", if (guest) "four-letter room code" else "four-digit display code")
        .replace("__ENTRY_LABEL__", if (guest) "Room code" else "Display code")
        .replace("__ENTRY_TYPE__", if (guest) "text" else "password")
        .replace("__ENTRY_INPUTMODE__", if (guest) "text" else "numeric")
        .replace("__ENTRY_BUTTON__", if (guest) "Join room" else "Open display")

    fun render(template: String, roomCode: String) = template
        .replace("__ROOM_CODE__", escapeHtml(roomCode))

    private fun escapeHtml(value: String) = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}

/** Guest and display capabilities for one room. Both are memory-only and die with the gateway. */
class RoomCapability private constructor(
    val roomCode: String,
    val bearer: String,
    val displayBearer: String,
    val displayEntryCode: String,
    val expiresAtMillis: Long,
    private val idleTimeoutMillis: Long,
    private var lastUsedAtMillis: Long,
) {
    @Volatile private var revoked = false
    private var guestEntryAttempts = 0
    private var guestEntryWindowAtMillis = lastUsedAtMillis
    private var entryAttempts = 0
    private var entryWindowAtMillis = lastUsedAtMillis

    /** The advertised room code grants guest permissions only, never display controls. */
    @Synchronized fun exchangeGuestCode(candidate: String, nowMillis: Long): GuestApiResponse {
        fun error(status: Int, message: String) = GuestApiResponse(status, JSONObject().put("error", message).toString())
        if (!activeAt(nowMillis)) return error(401, "Room closed. Open a new room on the host.")
        if (nowMillis - guestEntryWindowAtMillis >= 60_000) {
            guestEntryAttempts = 0
            guestEntryWindowAtMillis = nowMillis
        }
        if (guestEntryAttempts >= 5) return error(429, "Too many attempts. Wait one minute and try again.")
        guestEntryAttempts++
        val normalized = candidate.trim().replace(" ", "").replace("-", "").uppercase(java.util.Locale.ROOT)
        if (!authorize(normalized, roomCode, nowMillis)) return error(401, "Code does not match. Check the four-letter room code on the host.")
        return GuestApiResponse(200, JSONObject().put("capability", bearer).toString())
    }

    /** Short codes are separate from the publicly advertised room name. */
    @Synchronized fun exchangeDisplayCode(candidate: String, nowMillis: Long): GuestApiResponse {
        fun error(status: Int, message: String) = GuestApiResponse(status, JSONObject().put("error", message).toString())
        if (!activeAt(nowMillis)) return error(401, "Room closed. Open a new room on the host.")
        if (nowMillis - entryWindowAtMillis >= 60_000) {
            entryAttempts = 0
            entryWindowAtMillis = nowMillis
        }
        if (entryAttempts >= 5) return error(429, "Too many attempts. Wait one minute and try again.")
        entryAttempts++
        val normalized = candidate.trim().replace(" ", "").replace("-", "")
        if (!authorize(normalized, displayEntryCode, nowMillis)) return error(401, "Code does not match. Check the display code on the host.")
        return GuestApiResponse(200, JSONObject().put("capability", displayBearer).toString())
    }

    @Synchronized fun authorize(candidate: String?, nowMillis: Long): Boolean {
        return authorize(candidate, bearer, nowMillis)
    }

    @Synchronized fun authorizeDisplay(candidate: String?, nowMillis: Long): Boolean {
        return authorize(candidate, displayBearer, nowMillis)
    }

    private fun authorize(candidate: String?, expectedValue: String, nowMillis: Long): Boolean {
        if (!activeAt(nowMillis)) return false
        val supplied = candidate?.toByteArray(StandardCharsets.UTF_8) ?: return false
        val expected = expectedValue.toByteArray(StandardCharsets.UTF_8)
        if (!MessageDigest.isEqual(supplied, expected)) return false
        lastUsedAtMillis = nowMillis
        return true
    }

    @Synchronized fun isActive(nowMillis: Long) = activeAt(nowMillis)

    @Synchronized fun touch(nowMillis: Long): Boolean {
        if (!activeAt(nowMillis)) return false
        lastUsedAtMillis = nowMillis
        return true
    }

    private fun activeAt(nowMillis: Long) =
        !revoked && nowMillis < expiresAtMillis && nowMillis - lastUsedAtMillis < idleTimeoutMillis

    fun revoke() { revoked = true }

    companion object {
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        fun create(
            nowMillis: Long = System.currentTimeMillis(),
            lifetimeMillis: Long = 4 * 60 * 60 * 1_000L,
            idleTimeoutMillis: Long = 30 * 60 * 1_000L,
            random: SecureRandom = SecureRandom(),
        ): RoomCapability {
            fun secret() = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(ByteArray(32).also(random::nextBytes))
            val code = buildString(4) { repeat(4) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
            return RoomCapability(
                code,
                secret(),
                secret(),
                buildString { repeat(4) { append(random.nextInt(10)) } },
                nowMillis + lifetimeMillis,
                idleTimeoutMillis,
                nowMillis,
            )
        }
    }
}

/** Allowlisted guest/display surface. No owner token, stream URL, settings, or player claim is serialized. */
class GuestRoomRouter internal constructor(
    private val core: HarmonicastCore,
    private val capability: RoomCapability,
    private val displayToggle: () -> Unit = {},
    private val displaySkip: () -> Unit = {},
    private val acquisition: AcquisitionCoordinator? = null,
    private val accessAllowed: () -> Boolean = { true },
    private val displayArtwork: suspend (Song) -> String? = { null },
    private val catalogArtwork: suspend (String) -> String? = { null },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val votes = mutableSetOf<Pair<String, String>>()

    suspend fun route(request: GuestApiRequest): GuestApiResponse {
        val now = nowMillis()
        val displayOnly = when {
            capability.authorize(request.bearer, now) -> false
            capability.authorizeDisplay(request.bearer, now) -> true
            else -> return json(401, JSONObject().put("error", "Room capability is invalid or expired"))
        }
        if (!accessAllowed()) return json(403, JSONObject().put("error", "Room source is no longer available"))
        if (displayOnly && (request.method to request.path) !in DISPLAY_OPERATIONS) {
            return json(404, JSONObject().put("error", "Display operation is not available"))
        }
        if (!displayOnly && request.path.startsWith("/v1/display/")) {
            return json(404, JSONObject().put("error", "Guest operation is not available"))
        }
        val response = try {
            when (request.method to request.path) {
                "GET" to "/v1/status" -> json(200, JSONObject()
                    .put("roomCode", capability.roomCode)
                    .put("expiresAt", capability.expiresAtMillis)
                    .put("acquisitionAllowed", acquisition?.roomAllowed?.value == true)
                    .put("acquisitionAvailable", acquisition?.checkAccess() == true))
                "GET" to "/v1/acquisition/artwork" -> {
                    val service = acquisition ?: return json(403, JSONObject().put("error", "Acquisition unavailable"))
                    if (!service.roomAllowed.value || !service.checkAccess()) return json(403, JSONObject().put("error", "Acquisition is disabled or unavailable"))
                    val key = request.query["key"].orEmpty()
                    require(catalogArtworkUrl(key) != null) { "Invalid artwork selection" }
                    json(200, JSONObject().put("image", catalogArtwork(key) ?: JSONObject.NULL))
                }
                "GET" to "/v1/acquisition/catalog" -> {
                    val service = acquisition ?: return json(403, JSONObject().put("error", "Acquisition unavailable"))
                    if (!service.roomAllowed.value || !service.checkAccess()) return json(403, JSONObject().put("error", "Acquisition is disabled or unavailable"))
                    val mode = request.query["mode"] ?: "search"
                    val query = request.query["q"].orEmpty()
                    // The artist entry point applies to recognized local artists.
                    if (mode == "artist" && core.library.artist(query)?.name?.equals(query, true) != true)
                        return json(200, CatalogPage(emptyList()).json())
                    json(200, service.browseCatalog(query, mode, request.query["parent"].orEmpty(), request.query["offset"]?.toIntOrNull() ?: 0).json())
                }
                "GET" to "/v1/acquisition/entry" -> {
                    val service = acquisition
                    val available = service?.roomAllowed?.value == true && service.checkAccess()
                    val query = request.query["q"].orEmpty().take(200)
                    json(200, JSONObject().put("available", available)
                        .put("artist", available && query.isNotBlank() && core.library.artist(query)?.name?.equals(query, true) == true))
                }
                "POST" to "/v1/acquisition/requests" -> {
                    val service = acquisition ?: return json(403, JSONObject().put("error", "Acquisition unavailable"))
                    if (!service.roomAllowed.value) return json(403, JSONObject().put("error", "Music acquisition is disabled in this room"))
                    val id = JSONObject(request.body).getString("recordingId")
                    json(202, service.submit(id, participant(request), "${capability.roomCode}:${capability.expiresAtMillis}").json(true))
                }
                "GET" to "/v1/acquisition/requests" -> json(200, JSONObject().put("items", JSONArray().apply {
                    acquisition?.visible(participant(request), acquisition.roomId.value)?.forEach { put(it.json(true)) }
                }))
                "GET" to "/v1/artwork" -> {
                    val song = (if (!request.query["album"].isNullOrBlank()) core.library.albumTracks(request.query.getValue("album")).firstOrNull()
                        else core.library.track(request.query["id"].orEmpty()))
                        ?: return json(404, JSONObject().put("error", "Track was not found"))
                    json(200, JSONObject().put("image", displayArtwork(song) ?: JSONObject.NULL))
                }
                "GET" to "/v1/picks" -> {
                    val count = request.query["count"]?.toIntOrNull()?.coerceIn(2, 48) ?: 12
                    var sampleError: String? = null
                    var recentError: String? = null
                    val sample = try { core.library.randomTracks(100).distinctBy(Song::id) }
                        catch (e: Exception) { sampleError = "These picks could not be loaded. Refresh picks to retry."; emptyList() }
                    val recent = try { core.library.recentTracks().distinctBy(Song::id) }
                        catch (e: Exception) { recentError = "Recent additions could not be loaded. Refresh picks to retry."; emptyList() }
                    fun score(song: Song, underplayed: Boolean) = (song.rating ?: 5.0) * 3 +
                        (if (underplayed) -1 else 1) * kotlin.math.ln(1.0 + song.viewCount.coerceAtLeast(0))
                    val shelves = JSONArray()
                    for ((title, songs) in listOf(
                        "Crowd favorites" to sample.sortedByDescending { score(it, false) },
                        "Underplayed gems" to sample.sortedByDescending { score(it, true) },
                        "Recently added" to recent,
                        "Wild cards" to sample.shuffled(),
                    )) shelves.put(JSONObject().put("title", title)
                        .put("error", (if (title == "Recently added") recentError else sampleError) ?: JSONObject.NULL)
                        .put("songs", JSONArray().apply { songs.take(count).forEach { put(guestSong(it)) } }))
                    json(200, JSONObject().put("shelves", shelves))
                }
                "GET" to "/v1/now-playing" -> {
                    val state = core.playback.snapshot()
                    json(200, JSONObject().put("song", state.nowPlaying.song?.let(::guestSong) ?: JSONObject.NULL)
                        .put("isPlaying", state.nowPlaying.isPlaying)
                        .put("position", state.positionSeconds))
                }
                "GET" to "/v1/queue" -> json(200, JSONArray().apply { core.queue.songs().forEach { put(guestSong(it)) } })
                "GET" to "/v1/library/search" -> {
                    val query = request.query["q"].orEmpty().trim()
                    require(query.isNotBlank() && query.length <= 200) { "Enter a song, album, or artist" }
                    val artists = core.library.browse(BrowseKind.ARTISTS, BrowseOrder.TITLE, query = query).entries
                    val exact = artists.firstOrNull { it.title.trim().equals(query, true) } ?: artists.singleOrNull()
                    if (exact != null) {
                        val albums = mutableListOf<LibraryEntry>()
                        var offset = 0
                        do {
                            val page = core.library.browse(BrowseKind.ALBUMS, BrowseOrder.TITLE, offset, exact.id)
                            albums += page.entries
                            val next = page.nextOffset ?: break
                            check(next > offset && next <= 10000) { "Album listing is unavailable" }
                            offset = next
                        } while (true)
                        val sorted = albums.distinctBy { it.id }.sortedWith(compareBy<LibraryEntry> { it.year ?: Int.MAX_VALUE }.thenBy { it.title.lowercase() })
                        json(200, JSONObject().put("kind", "albums").put("artist", exact.title).put("items", JSONArray().apply {
                            sorted.forEach { put(JSONObject().put("id", it.id).put("title", it.title).put("artist", exact.title).put("year", it.year ?: JSONObject.NULL)) }
                        }))
                    } else json(200, JSONObject().put("kind", "tracks").put("items", JSONArray().apply {
                        core.library.search(query).sortedWith(compareBy<Song> { it.year ?: Int.MAX_VALUE }.thenBy { it.album.lowercase() }).forEach { put(guestSong(it)) }
                    }))
                }
                "GET" to "/v1/library/album" -> json(200, JSONArray().apply {
                    core.library.albumTracks(request.query["id"].orEmpty()).forEach { put(guestSong(it)) }
                })
                "GET" to "/v1/search" -> {
                    val query = request.query["q"].orEmpty().trim()
                    if (query.isBlank()) json(400, JSONObject().put("error", "Search query is required"))
                    else json(200, JSONArray().apply { core.library.search(query).sortedWith(compareBy<Song> { it.year?.takeIf { year -> year > 0 } ?: Int.MAX_VALUE }.thenBy { it.album.lowercase() }).forEach { put(guestSong(it)) } })
                }
                "POST" to "/v1/display/queue" -> {
                    val id = JSONObject(request.body.ifBlank { "{}" }).optString("songId")
                    val song = id.takeIf { it.isNotBlank() }?.let { core.library.track(it) }
                        ?: return json(404, JSONObject().put("error", "Track was not found"))
                    if (!accessAllowed()) return json(403, JSONObject().put("error", "Room source is no longer available"))
                    core.queue.addGuest(song.copy(isManual = true, addedByEmail = "Room display")) { acquisition?.pending("Room display") ?: 0 }
                    json(202, JSONObject().put("accepted", true).put("song", guestSong(song)))
                }
                "POST" to "/v1/display/player/toggle" -> {
                    displayToggle()
                    json(202, JSONObject().put("accepted", true))
                }
                "POST" to "/v1/display/player/skip" -> {
                    displaySkip()
                    json(202, JSONObject().put("accepted", true))
                }
                "POST" to "/v1/requests" -> {
                    val participant = participant(request)
                    val waiting = core.queue.songs().count { it.isManual && it.addedByEmail == participant }
                    if (waiting >= MAX_REQUESTS_PER_PARTICIPANT) {
                        return json(429, JSONObject().put("error", "You already have $MAX_REQUESTS_PER_PARTICIPANT songs in the queue"))
                    }
                    val id = JSONObject(request.body.ifBlank { "{}" }).optString("songId")
                    val song = id.takeIf { it.isNotBlank() }?.let { core.library.track(it) }
                        ?: return json(404, JSONObject().put("error", "Track was not found"))
                    if (!accessAllowed()) return json(403, JSONObject().put("error", "Room source is no longer available"))
                    core.queue.addGuest(song.copy(isManual = true, addedByEmail = participant)) { acquisition?.pending(participant) ?: 0 }
                    json(202, JSONObject().put("accepted", true).put("song", guestSong(song)))
                }
                "POST" to "/v1/votes" -> {
                    val direction = JSONObject(request.body.ifBlank { "{}" }).optString("direction")
                    if (direction !in setOf("up", "down")) json(400, JSONObject().put("error", "Vote must be up or down"))
                    else {
                        val songId = core.playback.snapshot().nowPlaying.song?.id
                            ?: return json(409, JSONObject().put("error", "Nothing is playing"))
                        val voteKey = participant(request) to songId
                        val accepted = synchronized(votes) { votes.add(voteKey) }
                        if (!accepted) json(409, JSONObject().put("error", "You already voted on this track"))
                        else try {
                            if (!accessAllowed()) return json(403, JSONObject().put("error", "Room source is no longer available"))
                            core.guests.roomVote(direction == "up")
                            json(202, JSONObject().put("accepted", true))
                        } catch (error: Exception) {
                            synchronized(votes) { votes.remove(voteKey) }
                            throw error
                        }
                    }
                }
                else -> json(404, JSONObject().put("error", "Guest operation is not available"))
            }
        } catch (e: AcquisitionFailure) {
            json(e.status, JSONObject().put("error", safeAcquisitionError(e)))
        } catch (e: IllegalArgumentException) {
            json(400, JSONObject().put("error", safeAcquisitionError(e)))
        } catch (e: Exception) {
            // Do not log exception messages or URLs: Plex requests may contain credentials.
            runCatching { android.util.Log.w("HarmonicastRoom", "${request.method} ${request.path}: ${e.javaClass.simpleName}" +
                ((e as? PlexRequestFailure)?.let { " status=${it.status}" } ?: "")) }
            json(502, JSONObject().put("error", if (request.path == "/v1/picks") "Could not read library picks. Check the Plex connection and retry." else "Guest operation failed"))
        }
        return if (accessAllowed()) response
        else json(403, JSONObject().put("error", "Room source is no longer available"))
    }

    private fun json(status: Int, body: Any) = GuestApiResponse(status, body.toString())

    private fun participant(request: GuestApiRequest) = if (request.bearer == capability.displayBearer) "Room display" else request.participantId.trim().take(64).ifBlank { "Guest" }

    private fun guestSong(song: Song) = JSONObject()
        .put("id", song.id)
        .put("title", song.title)
        .put("artist", song.artist)
        .put("album", song.album)
        .put("duration", song.duration)
        .put("year", song.year ?: JSONObject.NULL)
        .put("isManual", song.isManual)
        .put("isRadio", song.isRadio)

    private companion object {
        const val MAX_REQUESTS_PER_PARTICIPANT = 5
        val DISPLAY_OPERATIONS = setOf(
            "GET" to "/v1/artwork",
            "GET" to "/v1/picks",
            "GET" to "/v1/status",
            "GET" to "/v1/acquisition/catalog",
            "GET" to "/v1/acquisition/artwork",
            "GET" to "/v1/acquisition/entry",
            "GET" to "/v1/acquisition/requests",
            "POST" to "/v1/acquisition/requests",
            "GET" to "/v1/now-playing",
            "GET" to "/v1/queue",
            "GET" to "/v1/search",
            "GET" to "/v1/library/search",
            "GET" to "/v1/library/album",
            "POST" to "/v1/display/queue",
            "POST" to "/v1/display/player/toggle",
            "POST" to "/v1/display/player/skip",
        )
    }
}

/** Small HTTP adapter owned by the media service and active only while sharing is enabled. */
class GuestRoomGateway(
    context: Context,
    private val core: HarmonicastCore,
    private val requestedPort: Int = 8788,
    private val bindAddress: String? = null,
    private val displayToggle: () -> Unit = {},
    private val displaySkip: () -> Unit = {},
    private val accessAllowed: () -> Boolean = { true },
) {
    private val acquisition = AcquisitionRuntime.get(context)
    private val libraryScript = context.assets.open("room/library.js").bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    private val guestPageTemplate = context.assets.open("guest/index.html")
        .bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    private val displayPageTemplate = context.assets.open("display/index.html")
        .bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    private val displayEntryTemplate = context.assets.open("display/open.html")
        .bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    private val workers = ThreadPoolExecutor(
        2,
        4,
        30,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(16),
    ) { runnable -> Thread(runnable, "harmonicast-room-client").apply { isDaemon = true } }
    @Volatile private var running = false
    private var server: ServerSocket? = null
    private var capability: RoomCapability? = null
    private var router: GuestRoomRouter? = null

    fun start(): RoomShareState {
        check(accessAllowed()) { "Room source is no longer available" }
        if (running) return snapshot()
        val room = RoomCapability.create()
        // The LAN adapter is a development bridge. Bind only the selected local/loopback
        // interface so it can never become an all-interface internet control listener.
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(bindAddress ?: localDevelopmentAddress(), requestedPort))
        }
        capability = room
        acquisition.roomId.value = "${room.roomCode}:${room.expiresAtMillis}"
        router = GuestRoomRouter(core, room, displayToggle = displayToggle, displaySkip = displaySkip, acquisition = acquisition, accessAllowed = accessAllowed, displayArtwork = ::loadDisplayArtwork, catalogArtwork = { key ->
            catalogArtworkUrl(key)?.let { loadRoomImage(it, catalog = true) }
        })
        server = socket
        running = true
        thread(name = "harmonicast-room", isDaemon = true) {
            while (running) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                try {
                    workers.execute {
                        // Idle or half-open browser sockets are untrusted input. A read timeout
                        // must close only that connection, never crash the Android process and
                        // tear down an active Bluetooth room.
                        runCatching { client.use(::serve) }
                    }
                } catch (_: RejectedExecutionException) {
                    runCatching { client.close() }
                }
            }
        }
        return snapshot()
    }

    fun stop() {
        running = false
        capability?.revoke()
        capability = null
        router = null
        runCatching { server?.close() }
        server = null
    }

    /** BLE is itself the proximity bootstrap; it still uses the same allowlisted router. */
    suspend fun routeNearby(request: GuestApiRequest): GuestApiResponse {
        val room = capability ?: return GuestApiResponse(401, "{\"error\":\"Room closed\"}")
        val activeRouter = router ?: return GuestApiResponse(401, "{\"error\":\"Room closed\"}")
        return activeRouter.route(request.copy(bearer = room.bearer))
    }

    fun isActive(nowMillis: Long = System.currentTimeMillis()) = capability?.isActive(nowMillis) == true

    fun touchNearby(nowMillis: Long = System.currentTimeMillis()) = capability?.touch(nowMillis) == true

    fun snapshot(): RoomShareState {
        val room = capability ?: return RoomShareState()
        val port = server?.localPort ?: return RoomShareState()
        val host = server?.inetAddress?.hostAddress ?: return RoomShareState()
        val base = "http://$host:$port"
        val appJoin = RoomJoinPayloadCodec.deepLink(
            RoomJoinPayload(
                roomCode = room.roomCode,
                capability = room.bearer,
                expiresAtMillis = room.expiresAtMillis,
                endpoints = listOf(RoomEndpoint(RoomTransportKind.LAN, base)),
            ),
        )
        val browserJoin = "$base/#cap=${room.bearer}"
        val displayJoin = "$base/display#cap=${room.displayBearer}"
        return RoomShareState(
            enabled = true,
            roomCode = room.roomCode,
            joinUrl = browserJoin,
            displayUrl = displayJoin,
            guestEntryUrl = base,
            displayEntryUrl = "$base/open",
            displayEntryCode = room.displayEntryCode,
            appJoinUrl = appJoin,
            port = port,
            expiresAtMillis = room.expiresAtMillis,
        )
    }

    private fun serve(client: java.net.Socket) {
        client.soTimeout = 10_000
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
        val first = reader.readLine()?.split(' ') ?: return
        if (first.size < 2) return
        val headers = mutableMapOf<String, String>()
        var headerCount = 0
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isBlank()) break
            if (line.length > 8_192 || ++headerCount > 50) return
            val split = line.indexOf(':')
            if (split > 0) headers[line.substring(0, split).trim().lowercase()] = line.substring(split + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 65_536) ?: 0
        val body = CharArray(length).also { chars ->
            var offset = 0
            while (offset < chars.size) {
                val count = reader.read(chars, offset, chars.size - offset)
                if (count < 0) break
                offset += count
            }
        }.concatToString()
        val uri = URI(first[1])
        if (first[0] == "GET" && uri.path == "/room-library.js") {
            writeResponse(client, GuestApiResponse(200, libraryScript, "application/javascript; charset=utf-8"))
            return
        }
        if (first[0] == "GET" && uri.path in setOf("", "/", "/join", "/display", "/open", "/enter")) {
            val template = when (uri.path) {
                "/open", "/enter" -> GuestWebPage.renderEntry(displayEntryTemplate, guest = uri.path == "/enter")
                "/display" -> displayPageTemplate
                else -> guestPageTemplate
            }
            writeResponse(
                client,
                GuestApiResponse(
                    200,
                    GuestWebPage.render(template, capability?.roomCode.orEmpty()),
                    "text/html; charset=utf-8",
                ),
            )
            return
        }
        if (first[0] == "POST" && uri.path in setOf("/v1/display/open", "/v1/guest/open")) {
            val response = when {
                !accessAllowed() -> GuestApiResponse(403, "{\"error\":\"Room source is no longer available\"}")
                else -> capability?.let { room ->
                    if (uri.path == "/v1/guest/open") room.exchangeGuestCode(body.take(64), System.currentTimeMillis())
                    else room.exchangeDisplayCode(body.take(64), System.currentTimeMillis())
                } ?: GuestApiResponse(401, "{\"error\":\"Room closed\"}")
            }
            writeResponse(client, response)
            return
        }
        val bearer = headers["authorization"]?.takeIf { it.startsWith("Bearer ", true) }?.substring(7)?.trim()
        val response = runBlocking {
            val activeRouter = router ?: return@runBlocking GuestApiResponse(401, "{\"error\":\"Room closed\"}")
            activeRouter.route(GuestApiRequest(
                first[0],
                uri.path,
                bearer,
                parseQuery(uri.rawQuery),
                body,
                browserParticipant(headers["x-harmonicast-participant"], client.inetAddress.hostAddress.orEmpty()),
            ))
        }
        writeResponse(client, response)
    }

    // Resolve artwork exclusively from a host-owned library track, never a browser URL.
    private val artworkClient = okhttp3.OkHttpClient.Builder()
        .callTimeout(8, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private suspend fun loadDisplayArtwork(song: Song): String? {
        val url = core.library.artworkUrl(song) ?: return null
        return loadRoomImage(url)
    }
    private suspend fun loadRoomImage(url: String, catalog: Boolean = false, redirects: Int = 0): String? {
        if (redirects > 5) return null
        if (catalog) {
            val uri = URI(url)
            val host = uri.host.orEmpty().lowercase()
            if (uri.scheme != "https" || !(host == "coverartarchive.org" || host == "archive.org" || host.endsWith(".archive.org"))) return null
        }
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            artworkClient.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { response ->
                if (catalog && response.code in listOf(301, 302, 303, 307, 308)) {
                    val location = response.header("Location") ?: return@use null
                    val destination = URI(url).resolve(location).toString().replaceFirst("http://", "https://")
                    response.close()
                    return@use loadRoomImage(destination, true, redirects + 1)
                }
                val body = response.body ?: return@use null
                val type = body.contentType()?.let { "${it.type}/${it.subtype}" }
                if (!response.isSuccessful || type !in setOf("image/jpeg", "image/png", "image/webp")) return@use null
                val bytes = body.byteStream().use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (output.size() <= 2_000_000) {
                        val count = input.read(buffer, 0, minOf(buffer.size, 2_000_001 - output.size()))
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                if (bytes.size > 2_000_000) return@use null
                "data:$type;base64," + Base64.getEncoder().encodeToString(bytes)
            }
        }
    }

    private fun writeResponse(client: java.net.Socket, response: GuestApiResponse) {
        val bytes = response.body.toByteArray(StandardCharsets.UTF_8)
        val reason = when (response.status) { 200 -> "OK"; 202 -> "Accepted"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; 409 -> "Conflict"; 429 -> "Too Many Requests"; else -> "Bad Gateway" }
        client.getOutputStream().bufferedWriter(StandardCharsets.UTF_8).use { out ->
            out.write("HTTP/1.1 ${response.status} $reason\r\nContent-Type: ${response.contentType}\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; img-src 'self' data:\r\nConnection: close\r\n\r\n")
            out.write(response.body)
        }
    }

    private fun parseQuery(raw: String?): Map<String, String> = raw?.split('&')?.mapNotNull {
        val pair = it.split('=', limit = 2)
        pair.firstOrNull()?.let { key -> decode(key) to decode(pair.getOrElse(1) { "" }) }
    }?.toMap().orEmpty()

    private fun localDevelopmentAddress(): String = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && it.name.startsWith("wlan", ignoreCase = true) }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
        ?.hostAddress ?: "127.0.0.1"

    private fun decode(value: String) = URLDecoder.decode(value, "UTF-8")

    private fun browserParticipant(supplied: String?, address: String): String {
        val seed = supplied?.trim()?.takeIf { it.length in 8..128 } ?: address
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(StandardCharsets.UTF_8))
        val suffix = digest.take(3).joinToString("") { "%02X".format(it) }
        return "Browser guest $suffix"
    }
}
