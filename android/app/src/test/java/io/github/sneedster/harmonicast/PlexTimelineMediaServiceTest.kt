package io.github.sneedster.harmonicast

import android.content.Context
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import java.lang.reflect.Proxy
import java.net.URI
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PlexTimelineMediaServiceTest {
    @Test fun playPauseAndStopReachPlexWithoutMarkingPlayedTwice() = withService { fixture ->
        fixture.playing = true
        fixture.sync()
        fixture.playing = false
        fixture.sync()
        fixture.playbackState = Player.STATE_ENDED
        fixture.sync()

        val timelines = fixture.requests.filter { it.path == "/:/timeline" }
        assertEquals("Service playback changes must reach Plex's timeline endpoint", 3, timelines.size)
        assertTrue(timelines[0].rawQuery.contains("state=playing"))
        assertTrue(timelines[1].rawQuery.contains("state=paused"))
        assertTrue(timelines[2].rawQuery.contains("state=stopped"))
        assertTrue(timelines.all { it.rawQuery.contains("duration=180000") })
        assertTrue(fixture.requests.none { it.path == "/:/scrobble" })
    }

    @Test fun trackChangeStopsOldTrackBeforeReportingNewTrack() = withService { fixture ->
        fixture.playing = true
        fixture.sync()
        fixture.item = fixture.item.buildUpon().setMediaId("plex:machine:2").build()
        fixture.position = 0
        fixture.sync()

        val timelines = fixture.requests.filter { it.path == "/:/timeline" }
        assertEquals(3, timelines.size)
        assertTrue(timelines[0].rawQuery.contains("ratingKey=1"))
        assertTrue(timelines[1].rawQuery.contains("ratingKey=1"))
        assertTrue(timelines[1].rawQuery.contains("state=stopped"))
        assertTrue(timelines[2].rawQuery.contains("ratingKey=2"))
        assertTrue(timelines[2].rawQuery.contains("state=playing"))
    }

    @Test fun repeatedLocalUpdatesDoNotFloodPlex() = withService { fixture ->
        fixture.playing = true
        repeat(20) { fixture.sync() }
        assertEquals(1, fixture.requests.count { it.path == "/:/timeline" })
    }

    @Test fun knownTrackDurationSurvivesMediaSessionSerialization() {
        val context: Context = RuntimeEnvironment.getApplication()
        val song = Song("plex:machine:1", "Track", "Artist", duration = 180)
        val item = SessionMediaItems.track(context, song, "https://plex.invalid/audio", null)
        val remote = MediaItem.fromBundle(item.toBundle())
        assertEquals(180_000L, remote.mediaMetadata.extras!!.getLong("harmonicast.durationMs"))
        assertNull(remote.localConfiguration)
    }

    @Test fun completionUsesTimelineInsteadOfASecondMarkPlayedRequest() = withService { fixture ->
        fixture.playing = true
        fixture.sync()
        fixture.position = 180_000L
        fixture.playbackState = Player.STATE_ENDED
        HarmonicastMediaService::class.java.getDeclaredMethod("advance", String::class.java, Boolean::class.javaPrimitiveType).apply {
            isAccessible = true
            invoke(fixture.service, "ended", false)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Plex timeline reporting owns play counting; completion must not mark played again",
            fixture.requests.none { it.path == "/:/scrobble" })
        assertTrue(fixture.requests.any { it.path == "/:/timeline" && it.rawQuery.contains("state=stopped") })
    }

    @Test fun unknownPlayerDurationUsesKnownTrackMetadata() = withService { fixture ->
        fixture.duration = androidx.media3.common.C.TIME_UNSET
        fixture.playing = true
        fixture.sync()
        assertTrue(fixture.requests.any { it.path == "/:/timeline" && it.rawQuery.contains("duration=180000") })
    }

    @Test fun slowTimelineDeliveryDoesNotBlockLocalPauseAndKeepsDeliveryOrder() = withService { fixture ->
        val gate = CompletableDeferred<Unit>()
        fixture.timelineGate = gate
        fixture.playing = true
        fixture.sync()
        fixture.playing = false
        fixture.sync()
        assertFalse(runBlocking { fixture.core.playback.snapshot().nowPlaying.isPlaying })
        assertEquals(1, fixture.requests.count { it.path == "/:/timeline" })
        gate.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        val reports = fixture.requests.filter { it.path == "/:/timeline" }
        assertEquals(2, reports.size)
        assertTrue(reports[0].rawQuery.contains("state=playing"))
        assertTrue(reports[1].rawQuery.contains("state=paused"))
    }

    @Test fun offlineSampleBehindBlockedRequestIsNeverDeliveredAfterReconnect() = withService { fixture ->
        val gate = CompletableDeferred<Unit>()
        fixture.timelineGate = gate
        fixture.playing = true
        fixture.sync()
        fixture.storage.write(mapOf("local.offlinePlayback" to "true"))
        fixture.item = fixture.item.buildUpon().setMediaId("plex:machine:2").build()
        fixture.sync()
        fixture.storage.write(mapOf("local.offlinePlayback" to "false"))
        gate.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("An offline track sampled behind an in-flight request must never reach Plex after reconnect",
            fixture.requests.any { it.path == "/:/timeline" && it.rawQuery.contains("ratingKey=2") })
        assertEquals("Ineligible samples must not enqueue even a stop", 1,
            fixture.requests.count { it.path == "/:/timeline" })
    }

    @Test fun offlineBoundaryInvalidatesPreviouslyQueuedEligibleObservations() = withService { fixture ->
        val gate = CompletableDeferred<Unit>()
        fixture.timelineGate = gate
        fixture.playing = true
        fixture.sync()
        fixture.playing = false
        fixture.sync()
        fixture.storage.write(mapOf("local.offlinePlayback" to "true"))
        fixture.sync()
        fixture.storage.write(mapOf("local.offlinePlayback" to "false"))
        gate.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("A restored gate must not revive the previous eligibility epoch's queued pause", 1,
            fixture.requests.count { it.path == "/:/timeline" })
    }

    @Test fun eligibleSameTrackResumeStartsFreshSessionWithoutOfflineListeningCredit() = withService { fixture ->
        fixture.playing = true
        fixture.sync()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
        fixture.storage.write(mapOf("local.offlinePlayback" to "true"))
        repeat(6) {
            fixture.sync()
            ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
        }
        fixture.storage.write(mapOf("local.offlinePlayback" to "false"))
        fixture.sync()
        val resumed = fixture.requests.filter { it.path == "/:/timeline" }
        assertEquals("Resuming the same playing track must report a fresh session immediately", 2, resumed.size)
        assertTrue("Offline listening must never be credited on resume", resumed.last().rawQuery.contains("playbackTime=0&"))
        assertNotEquals(fixture.timelineSessions.first(), fixture.timelineSessions.last())
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
        fixture.playing = false
        fixture.sync()
        assertTrue("Only the subsequent eligible interval counts",
            fixture.requests.last { it.path == "/:/timeline" }.rawQuery.contains("playbackTime=2000&"))
    }

    @Test fun joinedGuestSamplesInvalidateBacklogWithoutLeakingGuestTrackOnLeave() = withService { fixture ->
        val gate = CompletableDeferred<Unit>()
        val guest = Any()
        fixture.timelineGate = gate
        fixture.playing = true
        fixture.sync()
        fixture.playing = false
        fixture.sync()
        try {
            NearbyGuestParticipation.update(guest, true)
            fixture.item = fixture.item.buildUpon().setMediaId("plex:machine:2").build()
            fixture.playing = true
            fixture.sync()
        } finally { NearbyGuestParticipation.update(guest, false) }
        gate.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Leaving a guest session must not revive old or guest observations", 1,
            fixture.requests.count { it.path == "/:/timeline" })
        fixture.sync()
        assertTrue(fixture.requests.last { it.path == "/:/timeline" }.rawQuery.contains("ratingKey=2"))
        assertTrue(fixture.requests.last { it.path == "/:/timeline" }.rawQuery.contains("playbackTime=0&"))
        assertNotEquals(fixture.timelineSessions.first(), fixture.timelineSessions.last())
    }

    @Test fun changedSelectedSourceSamplesCannotReviveWhenOriginalSourceReturns() = withService { fixture ->
        val gate = CompletableDeferred<Unit>()
        fixture.timelineGate = gate
        fixture.playing = true
        fixture.sync()
        val profile = HomeProfileStore(fixture.storage)
        val original = profile.personalSource!!
        profile.savePersonalSource(original.copy(machineIdentifier = "other"))
        fixture.item = fixture.item.buildUpon().setMediaId("plex:machine:2").build()
        fixture.sync()
        profile.savePersonalSource(original)
        gate.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, fixture.requests.count { it.path == "/:/timeline" })
        fixture.sync()
        assertTrue(fixture.requests.last { it.path == "/:/timeline" }.rawQuery.contains("ratingKey=2"))
        assertTrue(fixture.requests.last { it.path == "/:/timeline" }.rawQuery.contains("playbackTime=0&"))
        assertNotEquals(fixture.timelineSessions.first(), fixture.timelineSessions.last())
    }

    @Test fun replacingCoreDiscardsBacklogAndStartsFreshSameTrackSession() = withService { fixture ->
        val gate = CompletableDeferred<Unit>()
        fixture.timelineGate = gate
        fixture.playing = true
        fixture.sync()
        fixture.playing = false
        fixture.sync()
        fixture.replaceCore()
        gate.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Queued records belong to the old playback target even when source identity matches", 1,
            fixture.requests.count { it.path == "/:/timeline" })
        fixture.playing = true
        fixture.sync()
        assertEquals(2, fixture.requests.count { it.path == "/:/timeline" })
        assertTrue(fixture.requests.last().rawQuery.contains("playbackTime=0&"))
        assertNotEquals(fixture.timelineSessions.first(), fixture.timelineSessions.last())
    }

    @Test fun deliveryTimeEligibilityLossDropsQueuedSamplesAndResetsSession() = withService { fixture ->
        val gate = CompletableDeferred<Unit>()
        fixture.timelineGate = gate
        fixture.playing = true
        fixture.sync()
        fixture.playing = false
        fixture.sync()
        fixture.storage.write(mapOf("local.offlinePlayback" to "true"))
        // No ineligible sample: the worker must independently recheck its live gate.
        gate.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, fixture.requests.count { it.path == "/:/timeline" })
        fixture.storage.write(mapOf("local.offlinePlayback" to "false"))
        fixture.playing = true
        fixture.sync()
        assertEquals(2, fixture.requests.count { it.path == "/:/timeline" })
        assertNotEquals(fixture.timelineSessions.first(), fixture.timelineSessions.last())
    }

    private class Fixture(val service: HarmonicastMediaService, context: Context, val storage: ProfileStorage) {
        val requests = mutableListOf<URI>()
        val timelineSessions = mutableListOf<String>()
        lateinit var core: HarmonicastCore
        lateinit var makeCore: () -> HarmonicastCore
        var timelineGate: CompletableDeferred<Unit>? = null
        var playing = false
        var playbackState = Player.STATE_READY
        var position = 42_000L
        var duration = 180_000L
        var item = SessionMediaItems.track(context,
            Song("plex:machine:1", "Track", "Artist", duration = 180), "https://plex.invalid/audio", null)
        val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, _ ->
            when (method.name) {
                "getCurrentMediaItem" -> item
                "getCurrentPosition" -> position
                "getDuration" -> duration
                "isPlaying", "getPlayWhenReady" -> playing
                "getPlaybackState" -> playbackState
                "toString" -> "Timeline fixture player"
                "hashCode" -> System.identityHashCode(this)
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    else -> null
                }
            }
        } as ExoPlayer
        fun replaceCore() {
            core = makeCore()
            HarmonicastMediaService::class.java.getDeclaredField("core").apply { isAccessible = true; set(service, core) }
        }
        fun sync() {
            HarmonicastMediaService::class.java.getDeclaredMethod("syncCurrentPlaybackState", Boolean::class.javaPrimitiveType).apply {
                isAccessible = true
                invoke(service, playing)
            }
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun withService(block: (Fixture) -> Unit) {
        val context: Context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("timeline-service-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val api = AppStorage(prefs, context)
        val source = PersonalPlexSource("fixture", "https://plex.invalid", "machine", "Server", "7", "Music")
        api.profile.savePersonalSource(source)
        val service = Robolectric.buildService(HarmonicastMediaService::class.java).get()
        val fixture = Fixture(service, context, api.storage)
        val http = object : PlexHttp {
            override suspend fun request(url: String, method: String, headers: Map<String, String>, form: Map<String, String>): String {
                fixture.requests += URI(url)
                if (URI(url).path == "/:/timeline") {
                    fixture.timelineSessions += headers.getValue("X-Plex-Session-Identifier")
                    fixture.timelineGate?.await()
                }
                return """{"MediaContainer":{"Metadata":[]}}"""
            }
        }
        fixture.makeCore = { LocalHarmonicastCore(source, api.storage, LocalPlexClient(api.storage, http)) }
        val core = fixture.makeCore()
        fixture.core = core
        runBlocking { core.playback.publish(Song("plex:machine:1", "Track", "Artist", duration = 180,
            streamUri = "https://plex.invalid/audio"), true, false) }
        for ((name, value) in listOf("api" to api, "core" to core, "player" to fixture.player)) {
            HarmonicastMediaService::class.java.getDeclaredField(name).apply { isAccessible = true; set(service, value) }
        }
        try { block(fixture) }
        finally {
            (HarmonicastMediaService::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(service) as CoroutineScope).cancel()
        }
    }
}
