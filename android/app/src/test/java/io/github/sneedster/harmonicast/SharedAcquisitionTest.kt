package io.github.sneedster.harmonicast

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

internal class SharedGrabberStub : AcquisitionHttp {
    var role = "peon"
    var multiUser = true
    var password = "test-password"
    var accountId = "shared-account"
    var token = ""
    var logins = 0
    var posts = 0
    var forceChange = false
    var jobReads = 0
    val calls = mutableListOf<Pair<String, Map<String, String>>>()
    var loginStarted: CompletableDeferred<Unit>? = null
    var releaseLogin: CompletableDeferred<Unit>? = null
    override suspend fun call(url: String, method: String, headers: Map<String, String>, body: JSONObject?): JSONObject {
        calls += url to headers
        if (url.endsWith("/api/config")) return JSONObject().put("version", "test").put("users_exist", multiUser)
            .put("auth_mode", "session").put("singles_path_example", "/music/Singles")
        if (url.endsWith("/api/auth/login")) {
            loginStarted?.complete(Unit); releaseLogin?.await()
            if (body?.optString("password") != password) throw AcquisitionFailure(401, "Bad login")
            token = "session-${++logins}"
            return JSONObject().put("token", token)
        }
        if (url.endsWith("/api/auth/logout")) return JSONObject().put("ok", true)
        if (headers["Authorization"] != "Bearer $token" || token.isEmpty()) throw AcquisitionFailure(401, "Login required")
        if (url.endsWith("/api/auth/me")) return JSONObject().put("id", accountId).put("username", "guests").put("role", role).put("force_password_change", forceChange)
        if (url.endsWith("/api/users")) { if (role != "admin") throw AcquisitionFailure(403, "Admin only"); return JSONObject() }
        if (url.contains("/api/bulk-imports")) return JSONObject().put("imports", JSONArray())
        if (url.endsWith("/api/bulk-import-async")) { posts++; return JSONObject().put("import_id", "job-1") }
        if (url.contains("/api/bulk-import/") && url.endsWith("/status")) { jobReads++; return JSONObject().put("status", "pending").put("complete", false) }
        error("Unexpected service route")
    }
}

internal class SharedPlexStub(val source: PersonalPlexSource) : PlexHttp {
    var denied = false
    var offline = false
    var duplicates = false
    var locked = true
    var wrongSection = false
    var summary = SharedAcquisitionRecord.publish(
        SharedAcquisitionRecord.parse(SharedPlexSetup.placeholder(source, UUID.randomUUID().toString()).toString(), source), source,
        AcquisitionConnection("https://shared.example", "guests", "shared-account", "not-published", "test-password", role = "peon"), true).encoded()
    var reads = 0
    val headers = mutableListOf<Map<String, String>>()
    override suspend fun request(url: String, method: String, headers: Map<String, String>, form: Map<String, String>): String {
        this.headers += headers
        if (offline) throw java.io.IOException("offline")
        val path = java.net.URI(url).path
        val body = when (path) {
            "/" -> JSONObject().put("machineIdentifier", source.machineIdentifier)
            "/library/sections" -> JSONObject().put("Directory", JSONArray()
                .put(JSONObject().put("key", "1").put("type", "artist").put("title", "Music"))
                .apply { if (!denied) put(JSONObject().put("key", "6").put("type", "artist").put("title", "Harmonicast")) })
            "/library/sections/6/all" -> JSONObject().put("size", if (duplicates) 2 else 1).put("totalSize", if (duplicates) 2 else 1)
                .put("Metadata", JSONArray().put(JSONObject().put("ratingKey", "42")))
            "/library/metadata/42" -> {
                reads++
                if (denied) throw PlexRequestFailure(403)
                JSONObject().put("Metadata", JSONArray().put(JSONObject().put("ratingKey", "42").put("type", "album")
                    .put("librarySectionID", if (wrongSection) "1" else "6").put("summary", summary)
                    .put("Field", JSONArray().put(JSONObject().put("name", "summary").put("locked", locked)))))
            }
            else -> error("Unexpected Plex route")
        }
        return JSONObject().put("MediaContainer", body).toString()
    }
}

class SharedAcquisitionTest {
    private val source = PersonalPlexSource("plex-resource-secret", "https://plex.example", "machine", "Server", "1", "Music", "plex-account-secret", false)
    @After fun resetLease() { SharedAcquisitionAccess.clear() }
    private fun manager(store: AcquisitionMemory, plex: SharedPlexStub, mg: SharedGrabberStub,
        current: () -> PersonalPlexSource? = { source }) = SharedAcquisition(store, TestSecretCipher(), current, plex, mg, readSpacingMillis = 0)

    @Test fun strictRecordRejectsAmbiguityUnknownFieldsWrongSourceAndUnsafeEndpoints() {
        val valid = SharedPlexStub(source).summary
        assertTrue(SharedAcquisitionRecord.parse(valid, source).enabled)
        assertTrue(SharedAcquisitionRecord.parse(valid.replace("/", "\\/"), source).enabled)
        for (bad in listOf(valid.replace("\"version\":1", "\"version\":1,\"\\u0076ersion\":1"),
            valid.replace("\"version\":1", "\"version\":1.0"), valid.replace("\"version\":1", "\"version\":2"),
            valid.replace("https://shared.example", "http://shared.example"), valid + "{}",
            valid.replace("\"type\":", "\"extra\":true,\"type\":"), "{".repeat(17000))) {
            assertTrue(runCatching { SharedAcquisitionRecord.parse(bad, source) }.isFailure)
        }
        assertTrue(runCatching { SharedAcquisitionRecord.parse(valid, source.copy(libraryKey = "2")) }.isFailure)
    }

    @Test fun restrictedLoginRequiresSessionEnforcementAndNonAdminAndNeverSendsPlexTokens() = runBlocking {
        val mg = SharedGrabberStub()
        val account = MusicGrabberAccount(AcquisitionMemory(), TestSecretCipher(), mg, readSpacingMillis = 0, restricted = true)
        val input = AcquisitionLogin("https://shared.example", "guests", "test-password")
        assertEquals("peon", account.validate(input).role)
        mg.role = "admin"
        assertTrue(runCatching { account.validate(input) }.isFailure)
        mg.role = "peon"; mg.multiUser = false
        assertTrue(runCatching { account.validate(input) }.isFailure)
        assertTrue(runCatching { account.validate(input.copy(url = "http://shared.example")) }.isFailure)
        assertTrue(runCatching { account.validate(input.copy(apiKeyMode = true)) }.isFailure)
        assertTrue(mg.calls.none { it.second.keys.any { key -> key.startsWith("X-Plex") } })
    }

    @Test fun discoveryEncryptsSeparateCredentialsAndRevocationBlocksReadsAndClearsUsableLogin() = runBlocking {
        val store = AcquisitionMemory().apply { write(mapOf("acquisition.credentials" to "owner-untouched")) }
        val plex = SharedPlexStub(source); val mg = SharedGrabberStub(); val shared = manager(store, plex, mg)
        assertTrue(shared.refresh(true))
        assertTrue(PlexAccessPolicy.forSource(source).canSubmitAcquisition)
        assertFalse(PlexAccessPolicy.forSource(source).canWriteToPlex)
        assertEquals("owner-untouched", store.read("acquisition.credentials"))
        assertFalse(store.read("sharedAcquisition.acquisition.credentials")!!.contains("test-password"))
        assertTrue(mg.calls.none { it.second.values.any { v -> v.contains("plex-resource-secret") || v.contains("plex-account-secret") } })
        val before = plex.reads; assertTrue(shared.refresh(true)); assertTrue(plex.reads > before)
        plex.denied = true
        assertFalse(shared.refresh(true)); assertNull(shared.account.connection())
        assertFalse(PlexAccessPolicy.forSource(source).canSubmitAcquisition)
    }

    @Test fun transientFailureBlocksSubmissionsButOptOutPersistsAcrossRestart() = runBlocking {
        val store = AcquisitionMemory(); val plex = SharedPlexStub(source); val mg = SharedGrabberStub()
        val shared = manager(store, plex, mg)
        assertTrue(shared.refresh(true)); plex.offline = true
        assertFalse(shared.refresh(true)); assertNotNull(shared.account.connection())
        assertFalse(SharedAcquisitionAccess.available(source))
        plex.offline = false; assertTrue(shared.refresh(true))
        shared.optOut(); assertNull(shared.account.connection())
        val restarted = manager(store, plex, mg)
        assertFalse(restarted.refresh(true)); assertTrue(restarted.state.value.optedOut)
        assertTrue(restarted.reconnect())
    }

    @Test fun rotationsKeepJobIdentityButDifferentAccountsAndConfigurationsDoNot() = runBlocking {
        val store = AcquisitionMemory(); val plex = SharedPlexStub(source); val mg = SharedGrabberStub(); val shared = manager(store, plex, mg)
        assertTrue(shared.refresh(true)); val identity = shared.account.connection()!!.identity
        val j = JSONObject(plex.summary)
        mg.password = "new-password"; j.put("revision", 3).getJSONObject("musicGrabber").put("password", mg.password)
        plex.summary = j.toString(); assertTrue(shared.refresh(true)); assertEquals(identity, shared.account.connection()!!.identity)
        mg.accountId = "other-account"; j.put("revision", 4).getJSONObject("musicGrabber").put("accountId", mg.accountId)
        plex.summary = j.toString(); assertTrue(shared.refresh(true)); assertNotEquals(identity, shared.account.connection()!!.identity)
        val other = shared.account.connection()!!.identity
        j.put("configurationId", UUID.randomUUID().toString()); plex.summary = j.toString()
        assertTrue(shared.refresh(true)); assertNotEquals(other, shared.account.connection()!!.identity)
    }

    @Test fun staleLoginCannotSaveAfterSourceChangeAndMalformedGrantsNeverLogin() = runBlocking {
        var current: PersonalPlexSource? = source
        val store = AcquisitionMemory(); val plex = SharedPlexStub(source); val mg = SharedGrabberStub()
        val shared = manager(store, plex, mg) { current }
        mg.loginStarted = CompletableDeferred(); mg.releaseLogin = CompletableDeferred()
        val pending = async { shared.refresh(true) }
        mg.loginStarted!!.await(); current = null; shared.invalidate(); mg.releaseLogin!!.complete(Unit)
        assertFalse(pending.await()); assertNull(shared.account.connection())
        current = source; mg.loginStarted = null; mg.releaseLogin = null
        val count = mg.logins
        plex.duplicates = true; assertFalse(shared.refresh(true))
        plex.duplicates = false; plex.wrongSection = true; assertFalse(shared.refresh(true))
        plex.wrongSection = false; plex.locked = false; assertFalse(shared.refresh(true))
        assertEquals(count, mg.logins)
    }

    @Test fun disabledRevisionSurvivesRestartAndRejectsOlderEnabledRecord() = runBlocking {
        val store = AcquisitionMemory(); val plex = SharedPlexStub(source); val mg = SharedGrabberStub()
        val shared = manager(store, plex, mg)
        val enabled = plex.summary
        assertTrue(shared.refresh(true))
        val id = JSONObject(enabled).getString("configurationId")
        plex.summary = SharedPlexSetup.placeholder(source, id).put("revision", 3).toString()
        assertFalse(shared.refresh(true)); assertNull(shared.account.connection())
        plex.summary = enabled
        val restarted = manager(store, plex, mg)
        val logins = mg.logins
        assertFalse(restarted.refresh(true)); assertEquals(logins, mg.logins)
    }

    @Test fun expiredServiceSessionRelogsOnceAndRejectsRolePromotion() = runBlocking {
        val mg = SharedGrabberStub(); val account = MusicGrabberAccount(AcquisitionMemory(), TestSecretCipher(), mg, readSpacingMillis = 0, restricted = true)
        account.save(account.validate(AcquisitionLogin("https://shared.example", "guests", "test-password")))
        mg.token = "expired"
        account.call("/api/bulk-imports?limit=1")
        assertEquals(2, mg.logins)
        mg.role = "admin"
        assertTrue(runCatching { account.call("/api/bulk-import-async", "POST", JSONObject()) }.isFailure)
        assertEquals(0, mg.posts)
    }

    @Test fun recipientSubmissionUsesDelegatedAccountAndOldJobsPauseAfterAccountReplacement() = runBlocking {
        val f = AcquisitionFixture(); f.connect(); f.source = source
        val plex = SharedPlexStub(source); val mg = SharedGrabberStub()
        val shared = manager(f.storage, plex, mg) { f.source }
        val coordinator = AcquisitionCoordinator(f.account, f.catalog, f.storage, { f.source }, { f.core() }, { emptyList() }, false, shared)
        val request = coordinator.submit(f.recordingId)
        assertEquals("acquiring", request.status); assertEquals(1, mg.posts)
        assertTrue(plex.reads >= 2); assertEquals(0, f.network.submissions.get())
        assertEquals("user1", f.account.connection()!!.accountId)
        assertTrue(request.connection.contains(sharedSourceIdentity(source)))
        mg.accountId = "replacement-account"
        plex.summary = JSONObject(plex.summary).apply { put("revision", 3); getJSONObject("musicGrabber").put("accountId", mg.accountId) }.toString()
        assertTrue(shared.refresh(true)); coordinator.advance()
        assertEquals(0, mg.jobReads)
        assertTrue(coordinator.requests.value.single().message.startsWith("Paused"))
        plex.denied = true
        assertTrue(runCatching { coordinator.submit(f.recordingId) }.isFailure)
        assertEquals(1, mg.posts); assertEquals(0, f.network.submissions.get())
    }

    @Test fun freshCheckBeforePostStopsRevokedLibraryAndRoomPermission() = runBlocking {
        for (revokeLibrary in listOf(true, false)) {
            SharedAcquisitionAccess.clear()
            val f = AcquisitionFixture(); f.source = source
            val plex = SharedPlexStub(source); val mg = SharedGrabberStub()
            lateinit var coordinator: AcquisitionCoordinator
            val shared = SharedAcquisition(f.storage, TestSecretCipher(), { f.source }, plex, mg,
                onUnavailable = { coordinator.roomAllowed.value = false }, readSpacingMillis = 0)
            val catalog = MusicBrainzCatalog(object : AcquisitionHttp {
                override suspend fun call(url: String, method: String, headers: Map<String, String>, body: JSONObject?): JSONObject {
                    if (revokeLibrary) plex.denied = true else plex.summary = JSONObject(plex.summary).apply {
                        put("revision", 3); put("allowRoomAcquisition", false)
                    }.toString()
                    return JSONObject().put("id", f.recordingId).put("title", "Track").put("length", 180000)
                        .put("artist-credit", JSONArray().put(JSONObject().put("name", "Artist")))
                        .put("releases", JSONArray().put(JSONObject().put("status", "Official").put("title", "Album")
                            .put("release-group", JSONObject().put("primary-type", "Album"))))
                }
            }, 0)
            coordinator = AcquisitionCoordinator(f.account, catalog, f.storage, { f.source }, { f.core() }, { emptyList() }, false, shared)
            coordinator.roomId.value = "room"; coordinator.setRoomAllowed(true)
            val request = coordinator.submit(f.recordingId, "Guest", "room")
            assertEquals("failed", request.status); assertEquals(0, mg.posts)
            assertFalse(coordinator.roomAllowed.value)
        }
    }
}
