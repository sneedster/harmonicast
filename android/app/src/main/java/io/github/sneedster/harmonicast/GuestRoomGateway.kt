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
    val appJoinUrl: String = "",
    val port: Int = 0,
    val expiresAtMillis: Long = 0,
    val error: String = "",
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
)

data class GuestApiResponse(
    val status: Int,
    val body: String,
    val contentType: String = "application/json; charset=utf-8",
)

object GuestWebPage {
    fun render(template: String, roomCode: String) = template
        .replace("__ROOM_CODE__", escapeHtml(roomCode))

    private fun escapeHtml(value: String) = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}

/** One room-scoped capability. It is memory-only and dies with the gateway. */
class RoomCapability private constructor(
    val roomCode: String,
    val bearer: String,
    val expiresAtMillis: Long,
    private val idleTimeoutMillis: Long,
    private var lastUsedAtMillis: Long,
) {
    @Volatile private var revoked = false

    @Synchronized fun authorize(candidate: String?, nowMillis: Long): Boolean {
        if (revoked || nowMillis >= expiresAtMillis || nowMillis - lastUsedAtMillis >= idleTimeoutMillis) return false
        val supplied = candidate?.toByteArray(StandardCharsets.UTF_8) ?: return false
        val expected = bearer.toByteArray(StandardCharsets.UTF_8)
        if (!MessageDigest.isEqual(supplied, expected)) return false
        lastUsedAtMillis = nowMillis
        return true
    }

    fun revoke() { revoked = true }

    companion object {
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        fun create(
            nowMillis: Long = System.currentTimeMillis(),
            lifetimeMillis: Long = 4 * 60 * 60 * 1_000L,
            idleTimeoutMillis: Long = 30 * 60 * 1_000L,
            random: SecureRandom = SecureRandom(),
        ): RoomCapability {
            val secret = ByteArray(32).also(random::nextBytes)
            val code = buildString(4) { repeat(4) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
            return RoomCapability(
                code,
                Base64.getUrlEncoder().withoutPadding().encodeToString(secret),
                nowMillis + lifetimeMillis,
                idleTimeoutMillis,
                nowMillis,
            )
        }
    }
}

/** Allowlisted guest surface. No owner token, stream URL, settings, or player claim is serialized. */
class GuestRoomRouter(
    private val core: HarmonicastCore,
    private val capability: RoomCapability,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun route(request: GuestApiRequest): GuestApiResponse {
        if (!capability.authorize(request.bearer, nowMillis())) return json(401, JSONObject().put("error", "Room capability is invalid or expired"))
        return try {
            when (request.method to request.path) {
                "GET" to "/v1/status" -> json(200, JSONObject()
                    .put("roomCode", capability.roomCode)
                    .put("expiresAt", capability.expiresAtMillis))
                "GET" to "/v1/now-playing" -> {
                    val state = core.playback.snapshot()
                    json(200, JSONObject().put("song", state.nowPlaying.song?.let(::guestSong) ?: JSONObject.NULL)
                        .put("isPlaying", state.nowPlaying.isPlaying)
                        .put("position", state.positionSeconds))
                }
                "GET" to "/v1/queue" -> json(200, JSONArray().apply { core.queue.songs().forEach { put(guestSong(it)) } })
                "GET" to "/v1/search" -> {
                    val query = request.query["q"].orEmpty().trim()
                    if (query.isBlank()) json(400, JSONObject().put("error", "Search query is required"))
                    else json(200, JSONArray().apply { core.library.search(query).forEach { put(guestSong(it)) } })
                }
                "POST" to "/v1/requests" -> {
                    val id = JSONObject(request.body.ifBlank { "{}" }).optString("songId")
                    val song = id.takeIf { it.isNotBlank() }?.let { core.library.track(it) }
                        ?: return json(404, JSONObject().put("error", "Track was not found"))
                    core.queue.add(song.copy(isManual = true))
                    json(202, JSONObject().put("accepted", true).put("song", guestSong(song)))
                }
                "POST" to "/v1/votes" -> {
                    val direction = JSONObject(request.body.ifBlank { "{}" }).optString("direction")
                    if (direction !in setOf("up", "down")) json(400, JSONObject().put("error", "Vote must be up or down"))
                    else {
                        core.guests.vote(direction == "up")
                        json(202, JSONObject().put("accepted", true))
                    }
                }
                else -> json(404, JSONObject().put("error", "Guest operation is not available"))
            }
        } catch (e: Exception) {
            json(502, JSONObject().put("error", "Guest operation failed"))
        }
    }

    private fun json(status: Int, body: Any) = GuestApiResponse(status, body.toString())

    private fun guestSong(song: Song) = JSONObject()
        .put("id", song.id)
        .put("title", song.title)
        .put("artist", song.artist)
        .put("album", song.album)
        .put("duration", song.duration)
        .put("year", song.year ?: JSONObject.NULL)
        .put("isManual", song.isManual)
        .put("isRadio", song.isRadio)
}

/** Small HTTP adapter owned by the media service and active only while sharing is enabled. */
class GuestRoomGateway(
    context: Context,
    private val core: HarmonicastCore,
    private val requestedPort: Int = 8788,
    private val bindAddress: String? = null,
) {
    private val guestPageTemplate = context.assets.open("guest/index.html")
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

    fun start(): RoomShareState {
        if (running) return snapshot()
        val room = RoomCapability.create()
        // The LAN adapter is a development bridge. Bind only the selected local/loopback
        // interface so it can never become an all-interface internet control listener.
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(bindAddress ?: localDevelopmentAddress(), requestedPort))
        }
        capability = room
        server = socket
        running = true
        thread(name = "harmonicast-room", isDaemon = true) {
            while (running) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                try {
                    workers.execute { client.use(::serve) }
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
        runCatching { server?.close() }
        server = null
    }

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
        return RoomShareState(
            enabled = true,
            roomCode = room.roomCode,
            joinUrl = browserJoin,
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
        if (first[0] == "GET" && uri.path in setOf("", "/", "/join")) {
            writeResponse(
                client,
                GuestApiResponse(
                    200,
                    GuestWebPage.render(guestPageTemplate, capability?.roomCode.orEmpty()),
                    "text/html; charset=utf-8",
                ),
            )
            return
        }
        val bearer = headers["authorization"]?.takeIf { it.startsWith("Bearer ", true) }?.substring(7)?.trim()
        val response = runBlocking {
            GuestRoomRouter(core, capability ?: return@runBlocking GuestApiResponse(401, "{\"error\":\"Room closed\"}"))
                .route(GuestApiRequest(first[0], uri.path, bearer, parseQuery(uri.rawQuery), body))
        }
        writeResponse(client, response)
    }

    private fun writeResponse(client: java.net.Socket, response: GuestApiResponse) {
        val bytes = response.body.toByteArray(StandardCharsets.UTF_8)
        val reason = when (response.status) { 200 -> "OK"; 202 -> "Accepted"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; else -> "Bad Gateway" }
        client.getOutputStream().bufferedWriter(StandardCharsets.UTF_8).use { out ->
            out.write("HTTP/1.1 ${response.status} $reason\r\nContent-Type: ${response.contentType}\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; img-src 'none'\r\nConnection: close\r\n\r\n")
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
}
