package io.github.sneedster.harmonicast

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class GuestRoomGatewayTest {
    private class FakeCore : HarmonicastCore {
        val track = Song(
            id = "plex:server:42",
            title = "Safe Song",
            artist = "Artist",
            streamUri = "https://plex.example/library/parts/42?X-Plex-Token=owner-secret",
            artworkUri = "https://plex.example/photo?X-Plex-Token=owner-secret",
        )
        val queued = mutableListOf<Song>()
        val votes = mutableListOf<Boolean>()
        override fun observe(onEvent: (CoreEvent) -> Unit, onDisconnected: () -> Unit) = CoreSubscription { }
        override val library = object : MusicLibrary {
            override suspend fun search(query: String) = listOf(track)
            override suspend fun track(id: String) = track.takeIf { it.id == id }
            override suspend fun artist(query: String) = null
            override suspend fun discovery(song: Song) = ArtistDiscovery(song.artist, "", emptyList(), emptyList(), "", null, "")
            override fun streamUrl(song: Song) = song.streamUri.orEmpty()
            override fun artworkUrl(song: Song) = song.artworkUri
        }
        override val queue = object : MusicQueue {
            override suspend fun songs() = queued.toList()
            override suspend fun dequeue() = QueueSelection(queued.removeFirstOrNull())
            override suspend fun add(song: Song) { queued += song }
            override suspend fun remove(id: String) { queued.removeAll { it.id == id } }
            override suspend fun clear() = queued.clear()
            override suspend fun radio() = 0
            override suspend fun enableAutomaticPlayback() = Unit
            override suspend fun ratedTrackShare() = 8
            override suspend fun setRatedTrackShare(value: Int) = Unit
        }
        override val playback = object : PlaybackState {
            override suspend fun snapshot() = PlaybackSnapshot(NowPlaying(track, true), 12.0)
            override suspend fun claim() = Unit
            override suspend fun skip() = Unit
            override suspend fun isActivePlayer() = true
            override suspend fun publish(song: Song?, isPlaying: Boolean, isAutoQueue: Boolean) = Unit
            override suspend fun savePosition(seconds: Double) = Unit
            override suspend fun scrobble(id: String, submission: Boolean) = Unit
            override suspend fun recordEvent(song: Song, event: String, progress: Double) = Unit
        }
        override val guests = object : GuestControl {
            override suspend fun policy() = GuestPolicy(true, true, true, false, true)
            override suspend fun vote(up: Boolean) { votes += up }
        }
    }

    @Test fun validCapabilityAllowsOnlyGuestSafeOperationsWithoutPlexCredentials() = runBlocking {
        val core = FakeCore()
        val capability = RoomCapability.create(nowMillis = 1_000, lifetimeMillis = 10_000, idleTimeoutMillis = 5_000)
        val router = GuestRoomRouter(core, capability) { 2_000 }

        val search = router.route(GuestApiRequest("GET", "/v1/search", capability.bearer, mapOf("q" to "safe")))
        assertEquals(200, search.status)
        assertEquals("Safe Song", JSONArray(search.body).getJSONObject(0).getString("title"))
        assertFalse(search.body.contains("owner-secret"))
        assertFalse(search.body.contains("streamUri"))

        val requested = router.route(GuestApiRequest("POST", "/v1/requests", capability.bearer, body = JSONObject().put("songId", core.track.id).toString()))
        assertEquals(202, requested.status)
        assertEquals(listOf(core.track.id), core.queued.map(Song::id))
        assertEquals("Guest", core.queued.single().addedByEmail)

        assertEquals(202, router.route(GuestApiRequest("POST", "/v1/votes", capability.bearer, body = "{\"direction\":\"up\"}")).status)
        assertEquals(listOf(true), core.votes)
        assertEquals(404, router.route(GuestApiRequest("DELETE", "/v1/queue", capability.bearer)).status)
        assertEquals(404, router.route(GuestApiRequest("POST", "/v1/player/claim", capability.bearer)).status)
    }

    @Test fun participantIdentityLimitsRequestsAndPreventsVoteReplay() = runBlocking {
        val core = FakeCore()
        val capability = RoomCapability.create()
        val router = GuestRoomRouter(core, capability)
        val guestOne = "Nearby guest 1"
        val guestTwo = "Nearby guest 2"

        repeat(5) { index -> core.queued += core.track.copy(id = "queued-$index", addedByEmail = guestOne) }
        val limited = router.route(GuestApiRequest(
            "POST", "/v1/requests", capability.bearer,
            body = JSONObject().put("songId", core.track.id).toString(),
            participantId = guestOne,
        ))
        assertEquals(429, limited.status)

        val firstVote = GuestApiRequest(
            "POST", "/v1/votes", capability.bearer,
            body = "{\"direction\":\"up\"}", participantId = guestOne,
        )
        assertEquals(202, router.route(firstVote).status)
        assertEquals(409, router.route(firstVote).status)
        assertEquals(202, router.route(firstVote.copy(participantId = guestTwo)).status)
        assertEquals(listOf(true, true), core.votes)
    }

    @Test fun missingExpiredAndRevokedCapabilitiesAreRejected() = runBlocking {
        val core = FakeCore()
        val capability = RoomCapability.create(nowMillis = 1_000, lifetimeMillis = 10_000, idleTimeoutMillis = 2_000)
        assertEquals(401, GuestRoomRouter(core, capability) { 1_500 }
            .route(GuestApiRequest("GET", "/v1/status", null)).status)
        assertEquals(401, GuestRoomRouter(core, capability) { 3_001 }
            .route(GuestApiRequest("GET", "/v1/status", capability.bearer)).status)

        val fresh = RoomCapability.create(nowMillis = 1_000, lifetimeMillis = 10_000, idleTimeoutMillis = 5_000)
        fresh.revoke()
        assertEquals(401, GuestRoomRouter(core, fresh) { 1_500 }
            .route(GuestApiRequest("GET", "/v1/status", fresh.bearer)).status)
    }

    @Test fun displayCapabilityHasKioskControlsWithoutGuestVotingOrCredentialAccess() = runBlocking {
        val core = FakeCore()
        val capability = RoomCapability.create()
        var toggles = 0
        var skips = 0
        val router = GuestRoomRouter(core, capability, displayToggle = { toggles++ }, displaySkip = { skips++ })

        assertEquals(200, router.route(GuestApiRequest("GET", "/v1/status", capability.displayBearer)).status)
        assertEquals(200, router.route(GuestApiRequest("GET", "/v1/now-playing", capability.displayBearer)).status)
        assertEquals(200, router.route(GuestApiRequest("GET", "/v1/queue", capability.displayBearer)).status)
        assertEquals(200, router.route(GuestApiRequest("GET", "/v1/search", capability.displayBearer, mapOf("q" to "safe"))).status)
        assertEquals(202, router.route(GuestApiRequest("POST", "/v1/display/queue", capability.displayBearer,
            body = JSONObject().put("songId", core.track.id).toString())).status)
        assertEquals(202, router.route(GuestApiRequest("POST", "/v1/display/player/toggle", capability.displayBearer)).status)
        assertEquals(202, router.route(GuestApiRequest("POST", "/v1/display/player/skip", capability.displayBearer)).status)
        assertEquals(404, router.route(GuestApiRequest("POST", "/v1/votes", capability.displayBearer,
            body = "{\"direction\":\"up\"}")).status)
        assertEquals(listOf("Room display"), core.queued.map(Song::addedByEmail))
        assertTrue(core.votes.isEmpty())
        assertEquals(1, toggles)
        assertEquals(1, skips)

        assertEquals(404, router.route(GuestApiRequest("POST", "/v1/display/player/skip", capability.bearer)).status)
    }

    @Test fun nearbyActivityExtendsIdleLifetimeButNeverHardExpiry() {
        val room = RoomCapability.create(nowMillis = 1_000, lifetimeMillis = 10_000, idleTimeoutMillis = 2_000)

        assertTrue(room.touch(2_500))
        assertTrue(room.isActive(4_499))
        assertFalse(room.isActive(4_500))

        val hardLimited = RoomCapability.create(nowMillis = 1_000, lifetimeMillis = 3_000, idleTimeoutMillis = 5_000)
        assertTrue(hardLimited.touch(3_999))
        assertFalse(hardLimited.touch(4_000))
    }

    @Test fun backendFailuresDoNotExposeCredentialBearingMessages() = runBlocking {
        val core = FakeCore()
        val capability = RoomCapability.create()
        val failingCore = object : HarmonicastCore by core {
            override val library = object : MusicLibrary by core.library {
                override suspend fun search(query: String): List<Song> {
                    throw IllegalStateException("request failed with X-Plex-Token=owner-secret")
                }
            }
        }
        val response = GuestRoomRouter(failingCore, capability)
            .route(GuestApiRequest("GET", "/v1/search", capability.bearer, mapOf("q" to "x")))
        assertEquals(502, response.status)
        assertEquals("Guest operation failed", JSONObject(response.body).getString("error"))
        assertFalse(response.body.contains("owner-secret"))
    }

    @Test fun joinPayloadCarriesMultipleTransportCandidatesAndRoundTrips() {
        val payload = RoomJoinPayload(
            roomCode = "MUSE",
            capability = "room-secret",
            expiresAtMillis = 20_000,
            endpoints = listOf(
                RoomEndpoint(RoomTransportKind.NEARBY, "harmonicast-room-MUSE"),
                RoomEndpoint(RoomTransportKind.LAN, "http://192.168.1.9:8788"),
            ),
        )

        val encoded = RoomJoinPayloadCodec.encode(payload)
        assertFalse(encoded.contains("room-secret"))
        assertEquals(payload, RoomJoinPayloadCodec.decode(encoded, nowMillis = 10_000))
        assertEquals("harmonicast://join?p=$encoded", RoomJoinPayloadCodec.deepLink(payload))
    }

    @Test fun joinPayloadRejectsExpiredUnsupportedAndEndpointlessInvitations() {
        val valid = RoomJoinPayload(
            roomCode = "MUSE",
            capability = "room-secret",
            expiresAtMillis = 20_000,
            endpoints = listOf(RoomEndpoint(RoomTransportKind.LAN, "http://192.168.1.9:8788")),
        )
        assertNull(RoomJoinPayloadCodec.decode(RoomJoinPayloadCodec.encode(valid), nowMillis = 20_000))

        fun encodedJson(json: JSONObject) = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toString().toByteArray(StandardCharsets.UTF_8))
        assertNull(RoomJoinPayloadCodec.decode(encodedJson(JSONObject()
            .put("v", 2).put("room", "MUSE").put("cap", "x").put("exp", 20_000)
            .put("endpoints", JSONArray())), nowMillis = 10_000))
        assertNull(RoomJoinPayloadCodec.decode(encodedJson(JSONObject()
            .put("v", 1).put("room", "MUSE").put("cap", "x").put("exp", 20_000)
            .put("endpoints", JSONArray())), nowMillis = 10_000))
    }

    @Test fun guestPageEscapesRoomLabelAndNeverEmbedsTheCapability() {
        val rendered = GuestWebPage.render("<h1>Room __ROOM_CODE__</h1>", "<M&U'S\">")
        assertEquals("<h1>Room &lt;M&amp;U&#39;S&quot;&gt;</h1>", rendered)
        assertFalse(rendered.contains("room-secret"))
    }
}
