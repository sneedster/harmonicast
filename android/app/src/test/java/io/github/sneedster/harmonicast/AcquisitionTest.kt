package io.github.sneedster.harmonicast

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal class AcquisitionMemory : ProfileStorage {
    val values = ConcurrentHashMap<String, String>()
    override fun read(key: String) = values[key]
    override fun write(values: Map<String, String>) { this.values.putAll(values) }
}
internal class TestSecretCipher : SecretCipher {
    override fun seal(value: String) = java.util.Base64.getEncoder().encodeToString(value.toByteArray())
    override fun open(value: String) = String(java.util.Base64.getDecoder().decode(value))
}
internal class GrabberStub : AcquisitionHttp {
    val logins = AtomicInteger()
    val submissions = AtomicInteger()
    var validToken = ""
    var anonymous = false
    var rejectPassword = false
    var uncertain = false
    var errorStatus = 0
    var userId = "user1"
    var forcePasswordChange = false
    var lastBody: JSONObject? = null
    var loginStarted: CompletableDeferred<Unit>? = null
    var releaseLogin: CompletableDeferred<Unit>? = null
    var postStarted: CompletableDeferred<Unit>? = null
    var releasePost: CompletableDeferred<Unit>? = null
    val calls = mutableListOf<Pair<String, Map<String, String>>>()
    var job = JSONObject().put("status", "pending").put("complete", false)
    override suspend fun call(url: String, method: String, headers: Map<String, String>, body: JSONObject?): JSONObject {
        synchronized(calls) { calls += url to headers }
        if (url.endsWith("/api/config")) return JSONObject().put("version", "test").put("auth_required", true)
        if (url.endsWith("/api/auth/login")) {
            logins.incrementAndGet(); loginStarted?.complete(Unit); releaseLogin?.await()
            if (rejectPassword) throw AcquisitionFailure(401, "Sign in again")
            validToken = "token-${logins.get()}"
            return JSONObject().put("token", validToken).put("user", JSONObject().put("username", "listener"))
        }
        if (url.endsWith("/api/auth/logout")) return JSONObject().put("ok", true)
        if (!anonymous && !headers.containsKey("X-API-Key") && headers["Authorization"] != "Bearer $validToken") throw AcquisitionFailure(401, "Sign in again")
        if (url.endsWith("/api/auth/me")) return JSONObject().put("id", userId).put("username", "listener").put("force_password_change", forcePasswordChange)
        if (url.contains("/api/bulk-imports")) return JSONObject().put("imports", JSONArray())
        if (url.endsWith("/api/bulk-import-async")) {
            submissions.incrementAndGet(); lastBody = body; postStarted?.complete(Unit); releasePost?.await()
            if (uncertain) throw IOException("response lost")
            if (errorStatus != 0) throw AcquisitionFailure(errorStatus, "Rejected")
            return JSONObject().put("import_id", "import-${submissions.get()}").put("status", "pending")
        }
        return job
    }
}
internal class AcquisitionFixture {
    val storage = AcquisitionMemory()
    val network = GrabberStub()
    val account = MusicGrabberAccount(storage, TestSecretCipher(), network, readSpacingMillis = 0)
    var source: PersonalPlexSource? = PersonalPlexSource("plex-secret", "https://plex", "machine", "Plex", "7", "Music")
    var inPlex = false
    var plexUnavailable = false
    val recordingId = "11111111-1111-1111-1111-111111111111"
    val plex = LocalPlexClient(storage, object : PlexHttp {
        override suspend fun request(url: String, method: String, headers: Map<String, String>, form: Map<String, String>): String {
            if (plexUnavailable) throw java.io.IOException("private Plex URL")
            val metadata = JSONArray()
            if (inPlex) metadata.put(JSONObject().put("type", "track").put("ratingKey", "1").put("librarySectionID", "7")
                .put("title", "Track").put("grandparentTitle", "Artist").put("duration", 180000))
            return JSONObject().put("MediaContainer", JSONObject().put("Metadata", metadata)).toString()
        }
    })
    fun core() = LocalHarmonicastCore(source, storage, plex)
    val catalog = MusicBrainzCatalog(object : AcquisitionHttp {
        override suspend fun call(url: String, method: String, headers: Map<String, String>, body: JSONObject?) = JSONObject()
            .put("id", recordingId).put("title", "Track").put("length", 180000)
            .put("artist-credit", JSONArray().put(JSONObject().put("name", "Artist")))
            .put("releases", JSONArray().put(JSONObject().put("status", "Official").put("title", "Album").put("release-group", JSONObject().put("primary-type", "Album"))))
    }, 0)
    fun coordinator() = AcquisitionCoordinator(account, catalog, storage, { source }, { core() }, { if (inPlex) listOf(Song("plex:machine:1", "Track", "Artist", duration = 180)) else emptyList() }, false)
    suspend fun connect(remember: Boolean = true) { account.save(account.validate(AcquisitionLogin("https://grabber/base/", "listener", "password-secret", remember))) }
}

class AcquisitionTest {
    @Test fun joinedOwnerCannotSubmitOrEnablePersonalAcquisition() = runBlocking {
        val f = AcquisitionFixture(); f.connect(); val c = f.coordinator()
        val guest = Any()
        try {
            NearbyGuestParticipation.update(guest, true)
            assertTrue(runCatching { c.submit(f.recordingId) }.isFailure)
            assertTrue(runCatching { c.setRoomAllowed(true) }.isFailure)
            assertEquals(0, f.network.submissions.get())
            assertTrue(c.requests.value.isEmpty())
        } finally { NearbyGuestParticipation.update(guest, false) }
        assertEquals("acquiring", c.submit(f.recordingId).status)
        assertEquals(1, f.network.submissions.get())
    }

    @Test fun healthChecksCacheSuccessAndFailureButAllowExplicitRetry() = runBlocking {
        var clock = 0L
        var reads = 0
        var fail = false
        val stub = GrabberStub()
        val http = object : AcquisitionHttp {
            override suspend fun call(url: String, method: String, headers: Map<String, String>, body: JSONObject?): JSONObject {
                if (url.contains("/api/bulk-imports")) {
                    reads++
                    if (fail) throw IOException("offline")
                }
                return stub.call(url, method, headers, body)
            }
        }
        val account = MusicGrabberAccount(AcquisitionMemory(), TestSecretCipher(), http, { clock }, 0)
        account.save(account.validate(AcquisitionLogin("https://grabber", "listener", "password")))
        reads = 0
        clock = 299_999
        assertTrue(account.check()); assertEquals(0, reads)
        clock = 300_000
        assertTrue(account.check()); assertEquals(1, reads)
        fail = true
        assertFalse(account.check(true)); assertEquals(2, reads)
        clock += 59_999
        assertFalse(account.check()); assertEquals(2, reads)
        clock++
        assertFalse(account.check()); assertEquals(3, reads)
        fail = false
        assertTrue(account.check(true)); assertEquals(4, reads)
    }

    @Test fun simultaneousExplicitHealthChecksShareOneRequest() = runBlocking {
        val stub = GrabberStub()
        var gate: CompletableDeferred<Unit>? = null
        val started = CompletableDeferred<Unit>()
        var reads = 0
        val http = object : AcquisitionHttp {
            override suspend fun call(url: String, method: String, headers: Map<String, String>, body: JSONObject?): JSONObject {
                if (url.contains("/api/bulk-imports")) {
                    reads++
                    gate?.let { started.complete(Unit); it.await() }
                }
                return stub.call(url, method, headers, body)
            }
        }
        val account = MusicGrabberAccount(AcquisitionMemory(), TestSecretCipher(), http, readSpacingMillis = 0)
        account.save(account.validate(AcquisitionLogin("https://grabber", "listener", "password")))
        reads = 0; gate = CompletableDeferred()
        val first = async { account.check(true) }
        started.await()
        val others = List(7) { async(start = CoroutineStart.UNDISPATCHED) { account.check(true) } }
        gate!!.complete(Unit)
        assertTrue(first.await()); assertTrue(others.awaitAll().all { it })
        assertEquals(1, reads)
    }

    @Test fun loginFetchesAccountAndStoresEncryptedCredentialsWithoutKeyFallback() = runBlocking {
        val f = AcquisitionFixture(); f.connect()
        assertEquals("user1", f.account.connection()!!.accountId)
        assertEquals("https://grabber/base", f.account.connection()!!.url)
        assertFalse(f.storage.values.toString().contains("password-secret"))
        assertFalse(f.network.calls.any { it.second.containsKey("X-API-Key") })
        assertTrue(f.network.calls.any { it.first.endsWith("/api/auth/me") })
    }
    @Test fun expiredSessionReauthenticatesOnceAcrossConcurrentReads() = runBlocking {
        val f = AcquisitionFixture(); f.connect(); f.network.validToken = "expired"
        coroutineScope { repeat(8) { launch { f.account.call("/api/bulk-imports?limit=1") } } }
        assertEquals(2, f.network.logins.get())
    }
    @Test fun forgottenOrRejectedPasswordRequiresSignInWithoutRepeatedLoginAttempts() = runBlocking {
        val f = AcquisitionFixture(); f.connect(false); f.network.validToken = "expired"
        repeat(3) { assertFalse(f.account.check(true)) }; assertEquals(1, f.network.logins.get())
        f.connect(); f.network.validToken = "expired"; f.network.rejectPassword = true
        repeat(3) { assertFalse(f.account.check(true)) }; assertEquals(3, f.network.logins.get())
        assertTrue(f.account.state.value.message.contains("Sign in"))
    }
    @Test fun failedValidationLeavesSavedConnectionAndRevokesUnusedSession() = runBlocking {
        val f = AcquisitionFixture(); f.connect(); val saved = f.account.connection()
        f.network.forcePasswordChange = true
        assertTrue(runCatching { f.account.validate(AcquisitionLogin("https://other", "listener", "different")) }.isFailure)
        assertEquals(saved, f.account.connection())
        assertTrue(f.network.calls.any { it.first == "https://other/api/auth/logout" })
    }
    @Test fun keyModeUsesOnlyApiKeyAndDisconnectRevokesSession() = runBlocking {
        val f = AcquisitionFixture(); f.connect()
        val key = f.account.validate(AcquisitionLogin("https://grabber", apiKeyMode = true, apiKey = "key-secret")); f.account.save(key)
        assertTrue(f.network.calls.any { it.first.contains("bulk-imports") && it.second["X-API-Key"] == "key-secret" })
        assertTrue(f.network.calls.any { it.first.endsWith("/auth/logout") })
        assertEquals("", f.account.connection()!!.password)
        f.account.disconnect(); assertNull(f.account.connection())
    }
    @Test fun anonymousModeRequiresSuccessfulReadOnlyAccessCheck() = runBlocking {
        val f = AcquisitionFixture()
        assertTrue(runCatching { f.account.validate(AcquisitionLogin("https://grabber", apiKeyMode = true)) }.isFailure)
        f.network.anonymous = true
        val candidate = f.account.validate(AcquisitionLogin("https://grabber", apiKeyMode = true))
        f.account.save(candidate)
        assertTrue(f.account.check(true)); assertEquals(0, f.network.logins.get())
    }

    @Test fun completedTrackUsesNormalQueueAndFulfillmentIsDurable() = runBlocking {
        val f = AcquisitionFixture(); f.connect(); f.inPlex = true
        f.core().queue.add(Song("older", "Older request", "Artist", addedByEmail = "Owner"))
        val c = f.coordinator(); val r = c.submit(f.recordingId)
        assertEquals("acquiring", r.status); assertEquals(1, f.network.submissions.get())
        f.network.job = JSONObject().put("status", "completed").put("complete", true).put("completed", 1)
        c.advance(); assertEquals("fulfilled", c.requests.value.single().status)
        assertEquals(listOf("older", "plex:machine:1"), f.core().queue.songs().map { it.id })
        f.core().queue.addOnce(r.id, Song("plex:machine:1", "Track", "Artist"))
        assertEquals(2, f.core().queue.songs().size)
        f.coordinator().advance(); assertEquals(2, f.core().queue.songs().size)
    }
    @Test fun submissionDoesNotQueryPlexAndSendsOnlyArtistAndTitle() = runBlocking {
        val f = AcquisitionFixture(); f.connect(); f.plexUnavailable = true
        val result = f.coordinator().submit(f.recordingId)
        assertEquals("acquiring", result.status)
        assertEquals(1, f.network.submissions.get())
        assertTrue(f.network.calls.any { it.first.endsWith("/api/bulk-import-async") })
        val body = f.network.lastBody!!
        assertEquals("Artist - Track", body.getString("songs"))
        assertFalse(body.getBoolean("create_playlist")); assertFalse(body.getBoolean("use_playlists_dir"))
        assertEquals(setOf("songs", "create_playlist", "use_playlists_dir"), body.keys().asSequence().toSet())
    }
    @Test fun completedAcquisitionWaitsForPlexRecoveryWithoutResubmission() = runBlocking {
        val f = AcquisitionFixture(); f.connect(); f.plexUnavailable = true
        val c = f.coordinator(); val accepted = c.submit(f.recordingId)
        f.network.job = JSONObject().put("complete", true).put("completed", 1)
        runCatching { c.advance() }
        assertEquals("waiting_for_plex", c.requests.value.single().status)
        assertEquals(accepted.importId, c.requests.value.single().importId)
        assertTrue(c.requests.value.single().message.contains("Plex access"))
        f.plexUnavailable = false; f.inPlex = true; c.advance()
        assertEquals("fulfilled", c.requests.value.single().status)
        assertEquals(1, f.network.submissions.get())
    }
    @Test fun completedImportWithFailedTrackFailsInsteadOfWaitingForever() = runBlocking {
        val f = AcquisitionFixture(); f.connect(); val c = f.coordinator(); c.submit(f.recordingId)
        f.network.job = JSONObject().put("status", "completed").put("complete", true).put("completed", 0).put("failed", 1)
        c.advance(); assertEquals("failed", c.requests.value.single().status); assertTrue(f.core().queue.songs().isEmpty())
    }
    @Test fun delayedPlexIndexAndRoomClosureStillFulfillAcceptedWork() = runBlocking {
        val f = AcquisitionFixture(); f.connect(); val c = f.coordinator()
        c.roomId.value = "room"; c.setRoomAllowed(true)
        c.submit(f.recordingId, "Guest1", "room")
        c.roomId.value = ""; c.setRoomAllowed(false)
        f.network.job = JSONObject().put("status", "completed").put("complete", true).put("completed", 1)
        c.advance(); assertEquals("waiting_for_plex", c.requests.value.single().status)
        f.inPlex = true; c.advance(); c.advance()
        assertEquals("fulfilled", c.requests.value.single().status)
        assertEquals("Guest1", f.core().queue.songs().single().addedByEmail)
    }
    @Test fun pendingRequestsPauseForSharedPlexAndAccountChanges() = runBlocking {
        val f = AcquisitionFixture(); f.connect(); val c = f.coordinator(); c.submit(f.recordingId)
        f.network.job = JSONObject().put("status", "completed").put("complete", true).put("completed", 1); f.inPlex = true
        f.source = f.source!!.copy(canWriteToPlex = false); c.advance()
        assertTrue(f.core().queue.songs().isEmpty()); assertTrue(c.requests.value.single().message.startsWith("Paused"))
        assertTrue(runCatching { c.submit(f.recordingId) }.isFailure)
        assertTrue(runCatching { c.setRoomAllowed(true) }.isFailure)
        f.source = f.source!!.copy(canWriteToPlex = true); f.network.userId = "other"; f.connect(); c.advance()
        assertTrue(f.core().queue.songs().isEmpty())
        f.network.userId = "user1"; f.connect(); c.advance(); assertEquals(1, f.core().queue.songs().size)
    }
    @Test fun uncertainSubmissionIsNotReplayedOnRetryOrRestart() = runBlocking {
        val f = AcquisitionFixture(); f.connect(); f.network.uncertain = true; val c = f.coordinator()
        assertEquals("unconfirmed", c.submit(f.recordingId).status)
        c.submit(f.recordingId); c.advance(); f.coordinator().advance()
        assertEquals(1, f.network.submissions.get())
        assertEquals(1, c.pending("Owner"))
    }
    @Test fun leavingPickerDoesNotCancelAcceptedSubmission() = runBlocking {
        val f = AcquisitionFixture(); f.connect(); val c = f.coordinator()
        f.network.postStarted = CompletableDeferred(); f.network.releasePost = CompletableDeferred()
        val caller = launch { c.submit(f.recordingId) }
        f.network.postStarted!!.await(); caller.cancelAndJoin(); f.network.releasePost!!.complete(Unit)
        withTimeout(3000) { while (c.requests.value.single().status != "acquiring") delay(10) }
        assertEquals(1, f.network.submissions.get())
    }
    @Test fun pendingAcquisitionsReserveGuestSlotsForOrdinaryRequests() = runBlocking {
        val f = AcquisitionFixture(); f.connect(); val c = f.coordinator(); c.roomId.value = "room"; c.setRoomAllowed(true)
        repeat(4) { f.core().queue.add(Song("$it", "Queued", "Artist", addedByEmail = "guest")) }
        c.submit(f.recordingId, "guest", "room")
        val error = runCatching { f.core().queue.addGuest(Song("extra", "Extra", "Artist", addedByEmail = "guest")) { c.pending("guest") } }.exceptionOrNull()
        assertEquals(429, (error as AcquisitionFailure).status)
        assertEquals(4, f.core().queue.songs().size)
    }
    @Test fun matchingAndInputDoNotSilentlySelectWrongTrack() {
        val r = CatalogEntry("id", "Track", "Artist", durationMs = 180000)
        assertTrue(acquisitionMatches(Song("1", "Track (Remastered)", "Artist", duration = 182), r))
        assertFalse(acquisitionMatches(Song("1", "Other", "Artist", duration = 182), r))
        assertFalse(acquisitionMatches(Song("1", "Track", "Other", duration = 182), r))
        assertFalse(acquisitionMatches(Song("1", "Track", "Artist", duration = 240), r))
        assertEquals("Artist - Track", acquisitionLine(r))
        assertTrue(runCatching { acquisitionLine(r.copy(artist = "Jay-Z")) }.isFailure)
        assertTrue(runCatching { acquisitionLine(r.copy(title = "Track\nAnother - Song")) }.isFailure)
    }
    @Test fun pairingExpiresLocksOutAndAcceptsOnlyOneBrowser() {
        var time = 1000L; val p = SetupPairing { time }
        assertNull(p.pair("wrong")); assertTrue(p.active())
        val token = p.pair(p.code)!!; assertTrue(p.authorized(token)); assertFalse(p.authorized("room-token"))
        assertNull(p.pair(p.code)); time += 300001; assertFalse(p.authorized(token))
        val other = SetupPairing(); repeat(5) { other.pair("wrong") }; assertFalse(other.active()); assertNull(other.pair(other.code))
        val closed = SetupPairing(); val t = closed.pair(closed.code)!!; closed.close(); assertFalse(closed.authorized(t))
    }
}

class AcquisitionRoomTest {
    @Test fun roomPermissionDefaultsOffAndAllGuestSurfacesUseHostAuthority() = runBlocking {
        val f = AcquisitionFixture(); f.connect(); val c = f.coordinator()
        val cap = RoomCapability.create(); c.roomId.value = "${cap.roomCode}:${cap.expiresAtMillis}"
        val router = GuestRoomRouter(f.core(), cap, acquisition = c)
        val body = JSONObject().put("recordingId", f.recordingId).toString()
        fun request(bearer: String) = GuestApiRequest("POST", "/v1/acquisition/requests", bearer, body = body, participantId = "guest")
        assertEquals(403, router.route(request(cap.bearer)).status)
        assertEquals(403, router.route(request(cap.displayBearer)).status)
        assertEquals(401, router.route(request("setup-token")).status)
        assertEquals(0, f.network.submissions.get())
        c.setRoomAllowed(true)
        val reply = router.route(request(cap.bearer)); assertEquals(202, reply.status)
        assertFalse(reply.body.contains("token-")); assertFalse(reply.body.contains("https://grabber")); assertFalse(reply.body.contains("password-secret"))
        assertEquals(202, router.route(request(cap.displayBearer)).status)
        c.setRoomAllowed(false)
        assertEquals(403, router.route(request(cap.bearer)).status)
        val status = router.route(GuestApiRequest("GET", "/v1/acquisition/requests", cap.bearer, participantId = "guest"))
        assertEquals(1, JSONObject(status.body).getJSONArray("items").length())
        val other = router.route(GuestApiRequest("GET", "/v1/acquisition/requests", cap.bearer, participantId = "other"))
        assertEquals(0, JSONObject(other.body).getJSONArray("items").length())
        cap.revoke(); assertEquals(401, router.route(request(cap.bearer)).status)
    }
    @Test fun legacyRoomStatusIsUnavailableAndNewStatusCarriesOnlyCapability() {
        val old = NearbyRoomWire.decodeStatus("""{"room":"ABCD","title":"Track"}""".toByteArray())
        assertFalse(old.acquisitionAvailable)
        val status = NearbyRoomWire.status("ABCD", PlaybackSnapshot(NowPlaying()), true)
        assertTrue(NearbyRoomWire.decodeStatus(status).acquisitionAvailable)
        assertFalse(String(status).contains("key"))
    }
}
