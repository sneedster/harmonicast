package io.github.sneedster.harmonicast

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject
import org.json.JSONArray
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OfflineDownloadWorkerTest {
    private fun exercise(type: String, bytes: ByteArray, status: Int = 200, saved: Boolean = true): Pair<ListenableWorker.Result, List<Song>> = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val server = ServerSocket(0)
        val base = "http://127.0.0.1:${server.localPort}"
        val source = PersonalPlexSource("test-token-${server.localPort}", base, "machine", "Server", "7", "Music")
        val api = AppStorage(context.getSharedPreferences("harmonicast", Context.MODE_PRIVATE))
        api.profile.savePersonalSource(source)
        val song = Song("plex:machine:1", "Test track", "Artist")
        val metadata = JSONObject().put("MediaContainer", JSONObject().put("Metadata", JSONArray().put(JSONObject()
            .put("ratingKey", "1").put("type", "track").put("librarySectionID", "7").put("title", song.title)
            .put("grandparentTitle", song.artist).put("Media", JSONArray().put(JSONObject().put("Part", JSONArray()
                .put(JSONObject().put("key", "/audio")))))))).toString().toByteArray()
        val serving = thread(isDaemon = true) {
            try { repeat(2) { server.accept().use { socket ->
                socket.soTimeout = 3000
                val reader = socket.getInputStream().bufferedReader()
                val path = reader.readLine().split(' ')[1]
                while (!reader.readLine().isNullOrEmpty()) { }
                val audio = path.startsWith("/audio")
                val body = if (audio) bytes else metadata
                val header = "HTTP/1.1 ${if (audio) status else 200} OK\r\nContent-Type: ${if (audio) type else "application/json"}\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().write(header.toByteArray() + body)
            } } } catch (_: Exception) { }
        }
        val store = OfflineStore(context)
        try {
            if (!saved) {
                androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context,
                    androidx.work.Configuration.Builder().setExecutor(androidx.work.testing.SynchronousExecutor()).build())
                store.syncQueueCache(source, listOf(song))
            }
            val worker = TestListenableWorkerBuilder<OfflineDownloadWorker>(context).setInputData(workDataOf(
                "source" to OfflineStore.scope(source), "track" to song.id, "title" to song.title,
                "saved" to saved, "generation" to store.version(source, song.id, saved))).build()
            val result = worker.doWork()
            if (!saved) assertTrue(store.songs(source).isEmpty())
            result to (if (saved) store.songs(source) else store.cachedSongs(source))
        } finally { server.close(); serving.join(1000) }
    }
    @Test fun successfulAudioDownloadBecomesPrivatePlayableMetadata() {
        val audio = "RIFF".toByteArray() + ByteArray(2048)
        val (result, tracks) = exercise("audio/wav", audio)
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals("Test track", tracks.single().title)
        assertNull(tracks.single().streamUri)
    }
    @Test fun automaticPreloadCompletesWithoutBecomingASavedDownload() {
        val (result, tracks) = exercise("audio/wav", "RIFF".toByteArray() + ByteArray(2048), saved = false)
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals("Test track", tracks.single().title)
        assertNull(tracks.single().streamUri)
    }
    @Test fun errorPageNeverBecomesAnOfflineTrack() {
        val (result, tracks) = exercise("text/html", "<html>Login required</html>".toByteArray())
        assertTrue(result is ListenableWorker.Result.Failure)
        assertTrue(tracks.isEmpty())
    }
    @Test fun expiredPlexAccessFailsWithoutAcceptingAudio() {
        val (result, tracks) = exercise("audio/wav", ByteArray(10), 403)
        assertTrue(result is ListenableWorker.Result.Failure)
        assertTrue(tracks.isEmpty())
    }
}
