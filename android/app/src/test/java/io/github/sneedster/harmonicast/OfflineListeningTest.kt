package io.github.sneedster.harmonicast

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OfflineListeningTest {
    private val source = PersonalPlexSource("server-secret", "https://plex", "machine", "Server", "7", "Music", "account-secret")
    private val song = Song("plex:machine:1", "Song", "Artist", streamUri = "https://plex/part/1?X-Plex-Token=server-secret")
    @Test fun completeDownloadsAreIsolatedAndPlayWithoutAnyPlexRequests() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = OfflineStore(context)
        val partial = store.partial(source, song.id).apply { writeBytes(ByteArray(4096) { 42 }) }
        assertNull(store.uri(source, song.id))
        store.commit(source, song, partial)
        val cached = store.songs(source).single()
        assertNull(cached.streamUri); assertNull(cached.artworkUri)
        assertFalse(encodeSong(cached).toString().contains("secret"))
        assertTrue(store.uri(source, song.id)!!.startsWith("file:"))
        assertNull(store.uri(source.copy(accountToken = "different-account"), song.id))
        assertNull(store.uri(source.copy(libraryKey = "8"), song.id))
        val api = AppStorage(context.getSharedPreferences("offline-test", Context.MODE_PRIVATE), context)
        api.profile.savePersonalSource(source)
        api.storage.write(mapOf("local.offlinePlayback" to "true"))
        val rejecting = object : PlexHttp {
            override suspend fun request(url: String, method: String, headers: Map<String, String>, form: Map<String, String>): String = error("Offline playback must not contact Plex")
        }
        val core = LocalHarmonicastCore(source, api.storage, LocalPlexClient(api.storage, rejecting), offline = store)
        core.queue.addAll(listOf(cached))
        val next = core.queue.dequeueWithAutomaticFallback().song!!
        core.playback.publish(next, true, false)
        core.playback.recordEvent(next, "complete", 1.0)
        core.playback.scrobble(next.id, true)
        assertNotNull(core.library.streamUrl(next))
        assertEquals(next.id, core.playback.snapshot().nowPlaying.song!!.id)
        assertNull(core.queue.dequeueWithAutomaticFallback().song)
    }
    @Test fun soundProfilesRoundTripBypassPreampAndAllBands() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val profiles = SoundProfileStore(context)
        val eq = EqSettings(enabled = true, preampDb = -3.0, points = EqSettings.defaults.mapIndexed { index, band -> band.copy(gain = index.toDouble()) })
        profiles.save("Headphones", eq)
        assertEquals(eq, SoundProfileStore(context).read().single().settings)
        profiles.save("headphones", eq.copy(enabled = false))
        assertEquals(1, profiles.read().size)
        assertFalse(profiles.read().single().settings.enabled)
        profiles.remove("headphones"); assertTrue(profiles.read().isEmpty())
    }
}
