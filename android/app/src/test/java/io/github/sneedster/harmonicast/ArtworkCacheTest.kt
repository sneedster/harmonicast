package io.github.sneedster.harmonicast

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [EmbeddedPictureShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtworkCacheTest {
    private lateinit var context: Context
    private lateinit var store: OfflineStore
    private val source = PersonalPlexSource("test-token", "https://plex", "art-machine", "Server", "7", "Music", "test-account")
    private fun song(id: Int) = Song("plex:art-machine:$id", "Song $id", "Artist", albumArtist = "Artist",
        streamUri = "https://plex/audio/$id?X-Plex-Token=test-token",
        artworkUri = "https://plex/library/metadata/$id/thumb/1?X-Plex-Token=test-token")
    private fun picture(): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        return try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() } }
        finally { bitmap.recycle() }
    }
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        store = OfflineStore(context)
        store.clearAll()
        EmbeddedPictureShadow.pictures.clear()
    }
    private fun complete(track: Song, saved: Boolean = false) {
        val partial = store.partial(source, track.id).apply { writeBytes(ByteArray(4096)) }
        store.commit(source, track, partial, saved = saved)
    }
    @Test fun cachedPlayerCallbacksRetainArtworkWithoutFetchingPlexAndResolveCurrentCredentials() = runBlocking {
        val api = AppStorage(context.getSharedPreferences("art-playback", Context.MODE_PRIVATE), context)
        api.profile.savePersonalSource(source)
        val rejecting = object : PlexHttp {
            override suspend fun request(url: String, method: String, headers: Map<String, String>, form: Map<String, String>): String = error("Cached callbacks must not fetch metadata")
        }
        val full = song(1)
        store.syncQueueCache(source, listOf(full))
        complete(full)
        val core = LocalHarmonicastCore(source, api.storage, LocalPlexClient(api.storage, rejecting), store)
        val item = SessionMediaItems.track(context, full, core.library.streamUrl(full), core.library.artworkUrl(full))
        val callback = Song(full.id, full.title, full.artist,
            artworkUri = MediaArtworkProvider.source(context, item.mediaMetadata.artworkUri))
        core.playback.publish(callback.copy(artworkUri = null), false, true)
        assertEquals(full.artworkUri, core.library.artworkUrl(core.playback.snapshot().nowPlaying.song!!))
        core.playback.publish(callback, false, true)
        assertEquals(full.artworkUri, core.library.artworkUrl(core.playback.snapshot().nowPlaying.song!!))
        core.playback.savePosition(15.0)
        core.playback.publish(callback.copy(artworkUri = null), true, true)
        assertEquals(full.artworkUri, core.library.artworkUrl(core.playback.snapshot().nowPlaying.song!!))
        assertEquals(15.0, core.playback.snapshot().positionSeconds, 0.0)
        assertEquals("/library/metadata/1/thumb/1", store.song(source, full.id)!!.artworkUri)
        assertFalse(encodeSong(store.song(source, full.id)!!).toString().contains("test-token"))
        val cover = OfflineArtwork.normalize(picture())!!
        store.saveArtwork(source, full, cover, store.version(source, full.id, false), false)
        val local = core.library.artworkUrl(full)!!
        assertTrue(local.startsWith("file:"))
        core.playback.publish(callback.copy(artworkUri = local), false, true)
        store.clearCache(source)
        assertEquals(full.artworkUri, core.library.artworkUrl(core.playback.snapshot().nowPlaying.song!!))
        val moved = LocalHarmonicastCore(source.copy(baseUrl = "https://remote:32400", token = "fresh"), api.storage, offline = store)
        assertEquals("https://remote:32400/library/metadata/1/thumb/1?X-Plex-Token=fresh", moved.library.artworkUrl(full.copy(artworkUri = "/library/metadata/1/thumb/1")))
        assertNull(MediaArtworkProvider.source(context, android.net.Uri.parse(full.artworkUri)))
    }
    @Test fun strippedLegacyCacheDoesNotDiscardIncomingOrPreviousArtworkWithoutStreamUri() = runBlocking {
        val api = AppStorage(context.getSharedPreferences("legacy-art", Context.MODE_PRIVATE), context)
        api.profile.savePersonalSource(source)
        val full = song(1)
        store.syncQueueCache(source, listOf(full))
        complete(full.copy(artworkUri = null)) // v1.1.21's existing audio-only cache.
        val core = LocalHarmonicastCore(source, api.storage, offline = store)
        core.playback.publish(full.copy(streamUri = null), false, true)
        assertEquals(full.artworkUri, core.playback.snapshot().nowPlaying.song!!.artworkUri)
        core.playback.publish(Song(full.id, full.title, full.artist), true, true)
        assertEquals(full.artworkUri, core.playback.snapshot().nowPlaying.song!!.artworkUri)
    }
    @Test fun promotionPruningAndRemovalKeepArtworkWithItsAudioAndIsolateSources() {
        val tracks = listOf(song(1), song(2))
        store.syncQueueCache(source, tracks)
        val cover = OfflineArtwork.normalize(picture())!!
        tracks.forEach { complete(it); store.saveArtwork(source, it, cover, store.version(source, it.id, false), false) }
        assertNotNull(store.artworkUri(source, tracks[0].id))
        assertNull(store.artworkUri(source.copy(accountToken = "different"), tracks[0].id))
        store.enqueue(source, listOf(tracks[0]), true)
        store.clearCache(source)
        assertNotNull(store.artworkUri(source, tracks[0].id))
        assertNull(store.artworkUri(source, tracks[1].id))
        store.remove(source, tracks[0].id)
        assertNull(store.artworkUri(source, tracks[0].id))
        assertTrue(File(context.noBackupFilesDir, "offline_music").walkTopDown().none { it.extension == "jpg" })
    }
    @Test fun cancelledArtworkCannotRecreatePrunedFilesAndMissingArtworkIsThrottled() {
        val full = song(1)
        store.syncQueueCache(source, listOf(full)); complete(full)
        val generation = store.version(source, full.id, false)
        store.saveArtwork(source, full, null, generation, false)
        val manager = androidx.work.WorkManager.getInstance(context)
        manager.cancelUniqueWork(OfflineStore.cacheWorkName(source, full.id)).result.get()
        store.syncQueueCache(source, listOf(full))
        assertTrue(manager.getWorkInfosByTag(OfflineStore.cacheTag(source)).get().all { it.state == androidx.work.WorkInfo.State.CANCELLED })
        store.syncQueueCache(source, emptyList())
        store.saveArtwork(source, full, OfflineArtwork.normalize(picture()), generation, false)
        assertNull(store.uri(source, full.id))
        assertNull(store.artworkUri(source, full.id))
        assertTrue(File(context.noBackupFilesDir, "offline_music").walkTopDown().none { it.extension == "jpg" })
    }
    @Test fun embeddedPictureIsUsedWhenPlexCoverIsAbsentAndInvalidImagesAreIgnored() = runBlocking {
        val audio = File(context.cacheDir, "tagged.audio").apply { writeBytes(byteArrayOf(1)) }
        EmbeddedPictureShadow.pictures[audio.absolutePath] = picture()
        val cover = OfflineArtwork.load(audio, null)!!
        assertNotNull(BitmapFactory.decodeByteArray(cover, 0, cover.size))
        assertNull(OfflineArtwork.normalize("<html>error</html>".toByteArray()))
        assertNull(OfflineArtwork.normalize(ByteArray(4 * 1024 * 1024 + 1)))
        assertNull(OfflineArtwork.load(File(context.cacheDir, "untagged.audio"), null))
    }
    @Test fun unconfiguredPlayerCanResolveMissingArtworkWithoutPlex() {
        val api = AppStorage(context.getSharedPreferences("no-art-source", Context.MODE_PRIVATE), context)
        assertNull(LocalHarmonicastCore(null, api.storage, offline = store).library.artworkUrl(Song("test", "Track", "Artist")))
    }
    @Test fun roomArtworkUsesPrivateCoverPixelsAndRefreshesOpaqueKeys() = runBlocking {
        val api = AppStorage(context.getSharedPreferences("room-art", Context.MODE_PRIVATE), context)
        api.profile.savePersonalSource(source)
        val full = song(1)
        complete(full, saved = true)
        val cover = OfflineArtwork.normalize(picture())!!
        store.saveArtwork(source, full, cover, store.version(source, full.id), true)
        val core = LocalHarmonicastCore(source, api.storage, offline = store)
        val local = core.library.artworkUrl(full)!!
        assertArrayEquals(cover, OfflineArtwork.local(context, local))
        val outside = File(context.cacheDir, "${OfflineStore.digest(full.id)}.cover.jpg").apply { writeBytes(cover) }
        assertNull(OfflineArtwork.local(context, outside.toURI().toString()))
        assertNotEquals(NearbyRoomWire.artworkKey(full), NearbyRoomWire.artworkKey(full.copy(artworkUri = local)))
        val gateway = GuestRoomGateway(context, core, requestedPort = 0, bindAddress = "127.0.0.1")
        try {
            gateway.start()
            val response = gateway.routeNearby(GuestApiRequest("GET", "/v1/artwork", null, mapOf("id" to full.id)))
            assertEquals(response.body, 200, response.status)
            assertTrue(org.json.JSONObject(response.body).getString("image").startsWith("data:image/jpeg;base64,"))
            assertFalse(response.body.contains("file:"))
            assertFalse(response.body.contains("test-token"))
        } finally { gateway.stop() }
    }
    @Test fun failedPlexCoverFallsBackToEmbeddedPicture() = runBlocking {
        val audio = File(context.cacheDir, "fallback.audio").apply { writeBytes(byteArrayOf(1)) }
        EmbeddedPictureShadow.pictures[audio.absolutePath] = picture()
        val server = ServerSocket(0)
        val serving = thread(isDaemon = true) {
            try { server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) { }
                socket.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            } } catch (_: Exception) { }
        }
        try { assertNotNull(OfflineArtwork.load(audio, "http://127.0.0.1:${server.localPort}/thumb")) }
        finally { server.close(); serving.join(1000) }
    }
    @Test fun legacyAudioBackfillsPlexCoverWithoutDownloadingAudioAgain() = runBlocking {
        val server = ServerSocket(0)
        val base = "http://127.0.0.1:${server.localPort}"
        val localSource = source.copy(baseUrl = base)
        val full = song(1).copy(streamUri = "$base/audio", artworkUri = "$base/library/metadata/1/thumb/1?X-Plex-Token=test-token")
        val api = AppStorage(context.getSharedPreferences("harmonicast", Context.MODE_PRIVATE))
        api.profile.savePersonalSource(localSource)
        val partial = store.partial(localSource, full.id).apply { writeBytes(ByteArray(4096) { 42 }) }
        store.commit(localSource, full.copy(artworkUri = null), partial)
        val originalAudio = File(java.net.URI(store.uri(localSource, full.id)!!)).readBytes()
        val pixels = picture()
        val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
        val serving = thread(isDaemon = true) {
            try { repeat(2) { server.accept().use { socket ->
                socket.soTimeout = 3000
                val reader = socket.getInputStream().bufferedReader()
                val path = reader.readLine().split(' ')[1]; paths += path
                while (!reader.readLine().isNullOrEmpty()) { }
                val image = path.contains("/thumb/")
                val metadata = """{"MediaContainer":{"Metadata":[{"type":"track","ratingKey":"1","librarySectionID":"7","title":"Song 1","grandparentTitle":"Artist","thumb":"/library/metadata/1/thumb/1","Media":[{"Part":[{"key":"/audio"}]}]}]}}""".toByteArray()
                val body = if (image) pixels else metadata
                socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: ${if (image) "image/png" else "application/json"}\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray() + body)
            } } } catch (_: Exception) { }
        }
        try {
            val worker = TestListenableWorkerBuilder<OfflineDownloadWorker>(context).setInputData(workDataOf(
                "source" to OfflineStore.scope(localSource), "track" to full.id, "saved" to true,
                "generation" to store.version(localSource, full.id))).build()
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
            assertNotNull(store.artworkUri(localSource, full.id))
            assertArrayEquals(originalAudio, File(java.net.URI(store.uri(localSource, full.id)!!)).readBytes())
            assertEquals(2, paths.size)
            assertTrue(paths.none { it.startsWith("/audio") })
            assertFalse(encodeSong(store.song(localSource, full.id)!!).toString().contains("test-token"))
        } finally { server.close(); serving.join(1000) }
    }
}

/** Models Android's optional embedded-picture API; codec behavior still needs device validation. */
@Implements(MediaMetadataRetriever::class)
class EmbeddedPictureShadow {
    companion object { val pictures = mutableMapOf<String, ByteArray>() }
    private var path = ""
    @Implementation fun setDataSource(path: String) { this.path = path }
    @Implementation fun getEmbeddedPicture(): ByteArray? = pictures[path]
    @Implementation fun release() = Unit
}
