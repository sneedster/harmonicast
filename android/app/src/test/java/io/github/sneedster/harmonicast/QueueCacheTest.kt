package io.github.sneedster.harmonicast

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.testing.SynchronousExecutor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.io.RandomAccessFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class QueueCacheTest {
    private lateinit var context: Context
    private lateinit var store: OfflineStore
    private val source = PersonalPlexSource("secret", "https://plex", "cache-machine", "Server", "7", "Music", "account-secret")
    private fun song(id: Int) = Song("plex:cache-machine:$id", "Track $id", "Artist", albumArtist = "Artist", streamUri = "https://plex/part/$id?X-Plex-Token=secret")
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        context.getSharedPreferences("queue_cache", Context.MODE_PRIVATE).edit().clear().commit()
        store = OfflineStore(context)
        store.clearAll()
    }
    private fun complete(song: Song, saved: Boolean = false, size: Long = 4096) {
        val partial = store.partial(source, song.id)
        RandomAccessFile(partial, "rw").use { it.setLength(size) }
        store.commit(source, song, partial, store.version(source, song.id, saved), saved)
    }
    @Test fun rotatingWindowPreservesSavedTracksAndPromotesCachedDownloads() {
        val tracks = (1..14).map(::song)
        store.syncQueueCache(source, tracks)
        tracks.take(12).forEach { complete(it) }
        assertEquals(12, store.cachedSongs(source).size)
        assertTrue(store.songs(source).isEmpty())
        store.enqueue(source, listOf(tracks.first()), wifiOnly = true)
        assertEquals(tracks.first().id, store.songs(source).single().id)
        assertEquals(11, store.cachedSongs(source).size)
        store.syncQueueCache(source, tracks.drop(2))
        assertNotNull(store.uri(source, tracks.first().id))
        assertNull(store.uri(source, tracks[1].id))
        assertFalse(encodeSong(store.cachedSongs(source).first()).toString().contains("secret"))
        store.clearCache(source)
        assertTrue(store.cachedSongs(source).isEmpty())
        assertNotNull(store.uri(source, tracks.first().id))
    }
    @Test fun prunedOrCancelledWorkCannotRestoreOldCacheFiles() {
        val old = song(1)
        store.syncQueueCache(source, listOf(old))
        val version = store.version(source, old.id, false)
        val oldWork = WorkManager.getInstance(context).getWorkInfosByTag(OfflineStore.cacheTag(source)).get()
        assertEquals(1, store.currentCacheWorks(source, oldWork).size)
        val partial = store.partial(source, old.id).apply { writeBytes(ByteArray(1024)) }
        store.syncQueueCache(source, listOf(song(2)))
        try { store.commit(source, old, partial, version, saved = false); fail("Pruned work must not commit") }
        catch (_: IllegalStateException) { }
        assertNull(store.uri(source, old.id))
        assertTrue(store.currentCacheWorks(source, oldWork).isEmpty())
        val nextVersion = store.version(source, song(2).id, false)
        store.resetCacheWork(source)
        assertNotEquals(nextVersion, store.version(source, song(2).id, false))
    }
    @Test fun mobileCacheDefaultIsIndependentOfWifiSavedDownloads() {
        assertTrue(store.cacheSettings.enabled)
        assertEquals(12, store.cacheSettings.count)
        assertFalse(store.cacheSettings.wifiOnly)
        store.syncQueueCache(source, listOf(song(1)))
        store.enqueue(source, listOf(song(2)), wifiOnly = true)
        val manager = WorkManager.getInstance(context)
        assertEquals(NetworkType.CONNECTED, manager.getWorkInfosByTag(OfflineStore.cacheTag(source)).get().single().constraints.requiredNetworkType)
        assertEquals(NetworkType.UNMETERED, manager.getWorkInfosByTag(OfflineStore.tag(source)).get().single().constraints.requiredNetworkType)
    }
    @Test fun normalAutomaticQueueUsesCachedMetadataWithoutPlexAndStillChecksLocalRepeats() = runBlocking {
        val api = AppStorage(context.getSharedPreferences("cache-playback-test", Context.MODE_PRIVATE), context)
        api.profile.savePersonalSource(source)
        api.storage.write(mapOf("local.offlinePlayback" to "false", ReplayWindow.SETTING_KEY to "7"))
        val tracks = (1..3).map(::song)
        store.syncQueueCache(source, tracks)
        tracks.forEach { complete(it) }
        val rejecting = object : PlexHttp {
            override suspend fun request(url: String, method: String, headers: Map<String, String>, form: Map<String, String>): String = throw IOException("No signal")
        }
        val core = LocalHarmonicastCore(source, api.storage, LocalPlexClient(api.storage, rejecting), store)
        // Install an automatic tail rather than manual requests.
        api.storage.write(mapOf("local.queue" to org.json.JSONArray().apply { tracks.forEach { put(encodeSong(it.copy(isManual = false))) } }.toString()))
        val first = core.queue.dequeue().song!!
        assertEquals(tracks[0].id, first.id)
        core.playback.publish(first, true, true)
        api.storage.write(mapOf("local.queue" to org.json.JSONArray().apply {
            put(encodeSong(first.copy(isManual = false))); put(encodeSong(tracks[1].copy(isManual = false)))
        }.toString()))
        assertEquals(tracks[1].id, core.queue.dequeue().song!!.id)
        assertTrue(core.library.streamUrl(first).startsWith("file:"))
        assertEquals("false", api.storage.read("local.offlinePlayback"))
    }
    @Test fun automaticMixKeepsElevenUpcomingTracksWithoutDuplicatingCurrentOrRequests() = runBlocking {
        val api = AppStorage(context.getSharedPreferences("cache-refill-test", Context.MODE_PRIVATE), context)
        api.profile.savePersonalSource(source)
        val http = object : PlexHttp {
            override suspend fun request(url: String, method: String, headers: Map<String, String>, form: Map<String, String>): String {
                val requested = Regex("/library/metadata/(\\d+)").find(url)?.groupValues?.get(1)?.toInt()
                val ids = if (requested == null) (1..30).toList() else listOf(requested)
                val entries = org.json.JSONArray().apply { ids.forEach { id -> put(org.json.JSONObject()
                    .put("ratingKey", id.toString()).put("type", "track").put("librarySectionID", "7")
                    .put("title", "Track $id").put("grandparentTitle", "Artist").put("userRating", 8)
                    .put("Media", org.json.JSONArray().put(org.json.JSONObject().put("Part", org.json.JSONArray()
                        .put(org.json.JSONObject().put("key", "/audio/$id")))))) } }
                return org.json.JSONObject().put("MediaContainer", org.json.JSONObject().put("Metadata", entries)).toString()
            }
        }
        val core = LocalHarmonicastCore(source, api.storage, LocalPlexClient(api.storage, http), store)
        core.queue.enableAutomaticPlayback()
        assertEquals(12, core.queue.songs().size)
        val current = core.queue.dequeue().song!!
        core.playback.publish(current, true, true)
        val second = core.queue.dequeue().song!!
        core.playback.publish(second, true, true)
        core.queue.enableAutomaticPlayback()
        assertEquals(11, core.queue.songs().size)
        assertEquals(11, core.queue.songs().map { it.id }.toSet().size)
        assertFalse(core.queue.songs().any { it.id == second.id || it.id == current.id })
        core.queue.add(song(99))
        core.queue.enableAutomaticPlayback()
        assertEquals(song(99).id, core.queue.songs().first().id)
        assertEquals(12, core.queue.songs().size)
    }
    @Test fun fullCacheBudgetRejectsNewAudioWithoutLosingPlayableFiles() {
        val tracks = (1..3).map(::song)
        store.syncQueueCache(source, tracks)
        complete(tracks[0], size = OfflineStore.MAX_TRACK)
        complete(tracks[1], size = OfflineStore.MAX_TRACK)
        try { complete(tracks[2], size = 1); fail("512 MB cache limit must apply") }
        catch (_: IllegalStateException) { }
        assertEquals(2, store.cachedSongs(source).size)
        assertEquals(OfflineStore.MAX_CACHE, store.cachedBytes(source))
    }
}
