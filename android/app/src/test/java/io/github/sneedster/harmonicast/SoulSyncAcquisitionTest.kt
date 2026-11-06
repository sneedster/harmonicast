package io.github.sneedster.harmonicast

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

internal class SoulSyncStub : AcquisitionHttp {
    val calls = mutableListOf<Triple<String, String, Map<String, String>>>()
    var posts = 0
    var body: JSONObject? = null
    var wishlistGone = false
    var secondPage = false
    var malformedWishlist = false
    var processFailure: Int? = null
    var processingPosts = 0
    var trackId = ""
    var uncertain = false
    var malformed = false
    var reject = false
    var omitWishlistId = false
    override suspend fun call(url: String, method: String, headers: Map<String, String>, body: JSONObject?): JSONObject {
        calls += Triple(url, method, headers)
        if (reject) throw AcquisitionFailure(403, "Sign in again or check account access")
        if (malformed) return JSONObject().put("success", true)
        val data = when {
            url.endsWith("/api/v1/system/status") -> JSONObject().put("services", JSONObject()).put("uptime_seconds", 123)
            url.endsWith("/api/v1/wishlist") && method == "POST" -> {
                posts++; this.body = body
                trackId = body!!.getJSONObject("track_data").getString("id")
                if (uncertain) throw IOException("response lost")
                JSONObject().put("track", if (omitWishlistId) JSONObject() else JSONObject().put("track_id", trackId))
            }
            url.endsWith("/api/v1/wishlist/process") -> {
                processingPosts++
                processFailure?.let { throw AcquisitionFailure(it, "Processor busy or unavailable") }
                JSONObject().put("message", "Wishlist processing started")
            }
            url.contains("/api/v1/wishlist?limit=200&page=") -> {
                if (malformedWishlist) return JSONObject().put("success", true).put("data", JSONObject())
                val first = url.endsWith("page=1")
                val rows = org.json.JSONArray()
                if (!wishlistGone && (!secondPage || !first)) rows.put(JSONObject().put("track_id", trackId))
                return JSONObject().put("success", true).put("data", JSONObject().put("tracks", rows))
                    .put("pagination", JSONObject().put("has_next", secondPage && first))
            }
            else -> error("Unexpected provider endpoint: $url")
        }
        return JSONObject().put("success", true).put("data", data)
    }
}
internal class SoulSyncFixture {
    val base = AcquisitionFixture()
    val network = SoulSyncStub()
    val account = AcquisitionAccount(base.storage, TestSecretCipher(), network, readSpacingMillis = 0)
    fun coordinator() = AcquisitionCoordinator(account, base.catalog, base.storage, { base.source }, { base.core() }, { emptyList() }, false)
    suspend fun connect(key: String = "test-soul-key") {
        account.save(account.validate(AcquisitionLogin("https://soulsync/base/", apiKey = key, providerId = "soulsync")))
    }
}
class SoulSyncAcquisitionTest {
    @Test fun connectionUsesBearerEncryptedStorageAndProviderIdentityWithoutLogout() = runBlocking {
        val f = SoulSyncFixture(); f.connect()
        val connection = f.account.connection()!!
        assertEquals("soulsync", connection.providerId)
        assertTrue(connection.identity.startsWith("soulsync|https://soulsync/base|"))
        assertFalse(connection.identity.contains("test-soul-key"))
        assertFalse(f.base.storage.values.getValue("acquisition.credentials").contains("test-soul-key"))
        assertTrue(f.account.check(true))
        f.account.disconnect()
        assertTrue(f.network.calls.all { it.third == mapOf("Authorization" to "Bearer test-soul-key") })
        assertTrue(f.network.calls.all { it.first.endsWith("/api/v1/system/status") && it.second == "GET" })
    }
    @Test fun legacyCredentialsKeepTheirIdentityAndMusicGrabberProvider() = runBlocking {
        val f = AcquisitionFixture(); f.connect()
        val original = f.account.connection()!!
        val json = original.json().apply { remove("providerId") }
        f.storage.write(mapOf("acquisition.credentials" to TestSecretCipher().seal(json.toString())))
        assertEquals(original.identity, f.account.connection()!!.identity)
        assertEquals(MusicGrabberProvider, f.account.connection()!!.provider)
        assertTrue(f.account.check(true))
    }
    @Test fun missingKeyAndRestrictedAccountAreRejectedBeforeNetworkAccess() = runBlocking {
        val f = SoulSyncFixture()
        assertTrue(runCatching { f.account.validate(AcquisitionLogin("https://soul", providerId = "soulsync")) }.isFailure)
        val restricted = AcquisitionAccount(AcquisitionMemory(), TestSecretCipher(), f.network, restricted = true)
        assertTrue(runCatching { restricted.validate(AcquisitionLogin("https://soul", apiKey = "key", providerId = "soulsync")) }.isFailure)
        assertTrue(f.network.calls.isEmpty())
    }
    @Test fun malformedAndRejectedHealthChecksKeepExistingConnection() = runBlocking {
        val f = SoulSyncFixture(); f.connect(); val original = f.account.connection()
        f.network.malformed = true
        assertFalse(f.account.check(true)); assertEquals(original, f.account.connection())
        f.network.malformed = false; f.network.reject = true
        assertFalse(f.account.check(true)); assertEquals(original, f.account.connection())
        assertEquals(0, f.network.posts)
    }
    @Test fun structuredSubmissionDoesNotInheritMusicGrabberHyphenLimits() = runBlocking {
        val f = SoulSyncFixture(); f.connect()
        val track = CatalogEntry(f.base.recordingId, "Track", "Artist-With-Hyphens", "recording", durationMs = 180000)
        SoulSyncProvider.validateRecording(track)
        SoulSyncProvider.submit(f.account, track, f.account.connection()!!.identity)
        val submitted = f.network.body!!.getJSONObject("track_data")
        assertEquals("Artist-With-Hyphens", submitted.getJSONArray("artists").getJSONObject(0).getString("name"))
        assertEquals("Track", submitted.getString("name"))
        assertEquals(180000, submitted.getInt("duration_ms"))
        assertTrue(submitted.getString("id").startsWith("harmonicast:"))
        assertEquals("manual", f.network.body!!.getString("source_type"))
        assertFalse(f.network.body!!.has("notify_url"))
        assertEquals(1, f.network.processingPosts)
        assertTrue(f.network.calls.none { "/api/v1/request" in it.first })
    }
    @Test fun completionWaitsForPlexAndQueueReceiptSurvivesCoordinatorRestart() = runBlocking {
        val f = SoulSyncFixture(); f.connect(); val c = f.coordinator()
        assertEquals("acquiring", c.submit(f.base.recordingId).status)
        c.advance(); assertEquals("acquiring", c.requests.value.single().status)
        f.network.wishlistGone = true; c.advance()
        assertEquals("acquiring", c.requests.value.single().status)
        assertTrue(c.requests.value.single().message.contains("cleared"))
        assertTrue(f.base.core().queue.songs().isEmpty())
        f.base.inPlex = true; f.coordinator().advance(); f.coordinator().advance()
        assertEquals(1, f.base.core().queue.songs().size)
        assertEquals("fulfilled", f.coordinator().requests.value.single().status)
        assertEquals(1, f.network.posts)
    }
    @Test fun oldTransferRequestsAreNotReplayedOrMistakenForImportedMusic() = runBlocking {
        val f = SoulSyncFixture(); f.connect(); val c = f.coordinator(); c.submit(f.base.recordingId)
        val legacy = c.requests.value.single().copy(importId = "soul-request-1")
        val progress = SoulSyncProvider.poll(f.account, legacy)
        assertEquals("unconfirmed", progress.status)
        assertTrue(progress.checkPlex)
        assertTrue(progress.message.contains("Older"))
        assertEquals(1, f.network.posts)
        assertTrue(f.network.calls.none { "/api/v1/request" in it.first })
        assertTrue(f.base.core().queue.songs().isEmpty())
    }
    @Test fun timedOutSubmissionRemainsUnconfirmedAcrossRecoveryAndDuplicateTap() = runBlocking {
        val f = SoulSyncFixture(); f.connect(); f.network.uncertain = true
        val c = f.coordinator(); assertEquals("unconfirmed", c.submit(f.base.recordingId).status)
        val restored = f.coordinator(); restored.advance(); restored.submit(f.base.recordingId)
        assertEquals(1, f.network.posts)
        assertTrue(f.base.core().queue.songs().isEmpty())
    }
    @Test fun changedKeyPausesOriginalRequestEvenWhenPlexHasTheTrack() = runBlocking {
        val f = SoulSyncFixture(); f.connect(); val c = f.coordinator(); c.submit(f.base.recordingId)
        val original = f.account.connection()!!.identity
        f.connect("different-test-key"); assertNotEquals(original, f.account.connection()!!.identity)
        f.base.inPlex = true; c.advance()
        assertTrue(c.requests.value.single().message.startsWith("Paused"))
        assertTrue(f.base.core().queue.songs().isEmpty())
    }
    @Test fun malformedSubmissionIsUnconfirmedAndMalformedWishlistDoesNotQueue() = runBlocking {
        val missingId = SoulSyncFixture(); missingId.connect(); missingId.network.omitWishlistId = true
        assertEquals("unconfirmed", missingId.coordinator().submit(missingId.base.recordingId).status)
        missingId.coordinator().advance(); assertEquals(1, missingId.network.posts)
        val f = SoulSyncFixture(); f.connect(); val c = f.coordinator(); c.submit(f.base.recordingId)
        f.network.malformedWishlist = true
        assertTrue(runCatching { c.advance() }.isFailure)
        assertEquals("acquiring", c.requests.value.single().status)
        assertTrue(f.base.core().queue.songs().isEmpty())
    }
    @Test fun paginatedWishlistDoesNotLookFinishedAfterTheFirstPage() = runBlocking {
        val f = SoulSyncFixture(); f.connect(); val c = f.coordinator(); c.submit(f.base.recordingId)
        f.network.secondPage = true; c.advance()
        assertEquals("acquiring", c.requests.value.single().status)
        assertTrue(f.network.calls.any { it.first.endsWith("page=2") })
        assertTrue(f.base.core().queue.songs().isEmpty())
        assertEquals(1, f.network.posts)
    }
    @Test fun acceptedWishlistSurvivesBusyOrUnavailableProcessorWithoutReplay() = runBlocking {
        for (status in listOf(409, 503)) {
            val f = SoulSyncFixture(); f.connect(); f.network.processFailure = status
            val c = f.coordinator(); c.submit(f.base.recordingId)
            assertEquals("acquiring", c.requests.value.single().status)
            f.coordinator().advance(); f.coordinator().advance()
            assertEquals(1, f.network.posts)
            assertEquals(1, f.network.processingPosts)
            assertTrue(f.base.core().queue.songs().isEmpty())
        }
    }
    @Test fun plexMatchCanFulfillWhileNativeQualityRequestIsStillWishlisted() = runBlocking {
        val f = SoulSyncFixture(); f.connect(); val c = f.coordinator(); c.submit(f.base.recordingId)
        f.base.inPlex = true; c.advance()
        assertEquals("fulfilled", c.requests.value.single().status)
        assertEquals(1, f.base.core().queue.songs().size)
        assertEquals(1, f.network.posts)
    }
}
