package io.github.sneedster.harmonicast

import kotlinx.coroutines.runBlocking
import android.content.Context
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlexTimelineClientTest {
    private val source = PersonalPlexSource("token", "https://plex/", "machine", "Server", "7", "Music")
    private val timeline = PlexPlaybackTimeline("plex:machine:42", PlexPlaybackState.PLAYING, 12_345, 180_000, 6_000, "session-one")

    @Test fun timelineUsesNativeGetWithMetadataKeyAndClientSessionHeaders() = runBlocking {
        val storage = MemoryStorage()
        val http = RecordingHttp()
        val client = LocalPlexClient(storage, http)
        client.reportTimeline(source, timeline)
        val call = http.calls.single()
        val url = call.url.toHttpUrl()
        assertEquals("GET", call.method)
        assertEquals("/:/timeline", url.encodedPath)
        assertEquals("42", url.queryParameter("ratingKey"))
        assertEquals("/library/metadata/42", url.queryParameter("key"))
        assertTrue(call.url.contains("key=%2Flibrary%2Fmetadata%2F42"))
        assertEquals("playing", url.queryParameter("state"))
        assertEquals("12345", url.queryParameter("time"))
        assertEquals("180000", url.queryParameter("duration"))
        assertEquals("6000", url.queryParameter("playbackTime"))
        assertEquals("session-one", call.headers["X-Plex-Session-Identifier"])
        assertEquals(client.clientIdentifier, call.headers["X-Plex-Client-Identifier"])
        assertEquals("Harmonicast", call.headers["X-Plex-Product"])
        assertEquals("token", call.headers["X-Plex-Token"])
        assertTrue(call.form.isEmpty())
    }

    @Test fun onlyCurrentOwnedOnlinePersonalSourceReportsTimeline() = runBlocking {
        val storage = MemoryStorage()
        val http = RecordingHttp()
        val profile = HomeProfileStore(storage)
        profile.savePersonalSource(source)
        val core = LocalHarmonicastCore(source, storage, LocalPlexClient(storage, http))
        core.playback.reportTimeline(timeline)
        assertEquals(1, http.calls.size)
        storage.write(mapOf("local.offlinePlayback" to "true"))
        core.playback.reportTimeline(timeline)
        assertEquals(1, http.calls.size)
        storage.write(mapOf("local.offlinePlayback" to "false"))
        val guest = Any()
        try {
            NearbyGuestParticipation.update(guest, true)
            core.playback.reportTimeline(timeline)
            assertEquals(1, http.calls.size)
        } finally { NearbyGuestParticipation.update(guest, false) }
        for (selected in listOf(source.copy(machineIdentifier = "other"), source.copy(libraryKey = "8"),
            source.copy(accountToken = "other"), source.copy(canWriteToPlex = false))) {
            profile.savePersonalSource(selected)
            core.playback.reportTimeline(timeline)
            assertEquals("Must reject changed source $selected", 1, http.calls.size)
        }
        profile.savePersonalSource(source.copy(canWriteToPlex = false))
        LocalHarmonicastCore(source.copy(canWriteToPlex = false), storage, LocalPlexClient(storage, http))
            .playback.reportTimeline(timeline)
        assertEquals(1, http.calls.size)
        profile.clearPersonalSource()
        core.playback.reportTimeline(timeline)
        LocalHarmonicastCore(null, storage, LocalPlexClient(storage, http)).playback.reportTimeline(timeline)
        assertEquals(1, http.calls.size)
        profile.savePersonalSource(source)
        core.playback.reportTimeline(timeline)
        assertEquals(2, http.calls.size)
        assertTrue(http.calls.all { it.method == "GET" && it.url.contains("/:/timeline?") })
    }

    @Test fun disconnectedCoreDoesNotReportOrQueueTimelineForReconnect() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        shadowOf(connectivity).setActiveNetworkInfo(null)
        val offline = OfflineStore(context)
        assertFalse(offline.connected())
        val storage = MemoryStorage()
        val http = RecordingHttp()
        val core = LocalHarmonicastCore(source, storage, LocalPlexClient(storage, http), offline)
        core.playback.reportTimeline(timeline)
        assertTrue(http.calls.isEmpty())
        assertNull(storage.read("local.playbackHistory"))
    }

    @Test fun clientRejectsForeignAndNonnumericIdsBeforeAnyRequest() = runBlocking {
        val http = RecordingHttp()
        val client = LocalPlexClient(MemoryStorage(), http)
        for (id in listOf("plex:other:42", "plex:machine:42&state=playing", "plex:machine:", "42")) {
            assertTrue(runCatching { client.reportTimeline(source, timeline.copy(id = id)) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertTrue(http.calls.isEmpty())
        for (state in PlexPlaybackState.entries) client.reportTimeline(source, timeline.copy(state = state))
        assertEquals(listOf("playing", "paused", "stopped"), http.calls.map { it.url.toHttpUrl().queryParameter("state") })
        assertTrue(http.calls.all { it.headers["X-Plex-Session-Identifier"] == timeline.sessionIdentifier })
    }

    private class MemoryStorage : ProfileStorage {
        private val values = mutableMapOf<String, String>()
        override fun read(key: String) = values[key]
        override fun write(values: Map<String, String>) { this.values.putAll(values) }
    }
    private data class Call(val url: String, val method: String, val headers: Map<String, String>, val form: Map<String, String>)
    private class RecordingHttp : PlexHttp {
        val calls = mutableListOf<Call>()
        override suspend fun request(url: String, method: String, headers: Map<String, String>, form: Map<String, String>): String {
            calls += Call(url, method, headers, form)
            return ""
        }
    }
}
