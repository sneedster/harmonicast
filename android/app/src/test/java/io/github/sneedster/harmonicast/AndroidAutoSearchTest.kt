package io.github.sneedster.harmonicast

import android.content.Context
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AndroidAutoSearchTest {
    @Test fun connectedBrowserCanSearchAndFetchMatchingTracks() = withBrowser { browser, changes ->
        assertTrue(browser.availableSessionCommands.contains(SessionCommand.COMMAND_CODE_LIBRARY_SEARCH))
        assertTrue("Search needs permission to fetch the results as well as start the query",
            browser.availableSessionCommands.contains(SessionCommand.COMMAND_CODE_LIBRARY_GET_SEARCH_RESULT))
        assertEquals(SessionResult.RESULT_SUCCESS, await(browser.search("alina baraz", null)).resultCode)
        drainUntil { changes.isNotEmpty() }
        assertEquals(listOf("alina baraz" to 3), changes)
        val first = await(browser.getSearchResult("alina baraz", 0, 2, null))
        assertEquals(SessionResult.RESULT_SUCCESS, first.resultCode)
        assertEquals(listOf("Alone With You", "Electric"), first.value!!.map { it.mediaMetadata.title.toString() })
        val last = await(browser.getSearchResult("alina baraz", 1, 2, null))
        assertEquals(listOf("Fantasy"), last.value!!.map { it.mediaMetadata.title.toString() })
    }

    @Test fun queryWithoutMatchesReportsEmptyResultsSuccessfully() = withBrowser { browser, changes ->
        assertEquals(SessionResult.RESULT_SUCCESS, await(browser.search("missing", null)).resultCode)
        drainUntil { changes.isNotEmpty() }
        assertEquals(listOf("missing" to 0), changes)
        val result = await(browser.getSearchResult("missing", 0, 20, null))
        assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
        assertTrue(result.value!!.isEmpty())
    }

    @Test fun unpagedSearchFetchesTracksWithTheCompatibilityBridgePageSize() = withBrowser { browser, _ ->
        // Media3's legacy browser bridge requests all matches with this page size.
        val result = await(browser.getSearchResult("alina baraz", 0, Int.MAX_VALUE, null))
        assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
        assertEquals(3, result.value!!.size)
    }

    @Test fun voicePlayRequestResolvesArtistNameToAPlayableTrack() = withBrowser { browser, _ ->
        browser.setMediaItem(MediaItem.Builder().setRequestMetadata(
            MediaItem.RequestMetadata.Builder().setSearchQuery("alina baraz").build()
        ).build())
        drainUntil { browser.currentMediaItem?.mediaMetadata?.title == "Alone With You" }
        assertEquals("Alina Baraz", browser.currentMediaItem!!.mediaMetadata.artist.toString())
        assertEquals("plex:machine:1", browser.currentMediaItem!!.mediaId)
    }

    private fun withBrowser(block: (MediaBrowser, MutableList<Pair<String, Int>>) -> Unit) {
        val context: Context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("harmonicast", Context.MODE_PRIVATE).edit().clear().commit()
        val lifecycle = Robolectric.buildService(HarmonicastMediaService::class.java).create()
        val service = lifecycle.get()
        val storage = object : ProfileStorage {
            override fun read(key: String): String? = null
            override fun write(values: Map<String, String>) = Unit
        }
        val http = object : PlexHttp {
            override suspend fun request(url: String, method: String, headers: Map<String, String>, form: Map<String, String>): String {
                val uri = android.net.Uri.parse(url)
                if (uri.path!!.endsWith("/search")) {
                    val artists = if (uri.getQueryParameter("type") == "8")
                        if (uri.getQueryParameter("query") == "alina baraz")
                            """{"type":"artist","ratingKey":"42","title":"Alina Baraz"}""" else "" else ""
                    return """{"MediaContainer":{"Metadata":[$artists]}}"""
                }
                val all = if (uri.getQueryParameter("title") == "alina baraz" || uri.path!!.endsWith("/allLeaves"))
                    listOf("Alone With You", "Electric", "Fantasy") else emptyList()
                val start = uri.getQueryParameter("X-Plex-Container-Start")?.toInt() ?: 0
                val size = uri.getQueryParameter("X-Plex-Container-Size")?.toInt() ?: 40
                val tracks = all.withIndex().drop(start).take(size).joinToString(",") { (index, title) ->
                    """{"type":"track","ratingKey":"${index + 1}","title":"$title","grandparentTitle":"Alina Baraz","Media":[{"Part":[{"key":"/library/parts/${index + 1}/audio.flac"}]}]}"""
                }
                return """{"MediaContainer":{"totalSize":${all.size},"Metadata":[$tracks]}}"""
            }
        }
        val source = PersonalPlexSource("fixture", "https://plex.invalid", "machine", "Server", "7", "Music")
        HarmonicastMediaService::class.java.getDeclaredField("core").apply {
            isAccessible = true
            set(service, LocalHarmonicastCore(source, storage, LocalPlexClient(storage, http)))
        }
        val session = HarmonicastMediaService::class.java.getDeclaredField("mediaLibrarySession").run {
            isAccessible = true
            get(service) as androidx.media3.session.MediaLibraryService.MediaLibrarySession
        }
        val changes = mutableListOf<Pair<String, Int>>()
        var browser: MediaBrowser? = null
        try {
            browser = await(MediaBrowser.Builder(context, session.token).setListener(object : MediaBrowser.Listener {
                override fun onSearchResultChanged(browser: MediaBrowser, query: String, itemCount: Int, params: LibraryParams?) {
                    changes += query to itemCount
                }
            }).buildAsync())
            block(browser, changes)
        } finally {
            browser?.release()
            (HarmonicastMediaService::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(service) as CoroutineScope).cancel()
            lifecycle.destroy()
        }
    }

    private fun drainUntil(done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!done() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(25))
            Thread.sleep(5)
        }
        assertTrue("Media browser callback timed out", done())
    }

    private fun <T> await(future: Future<T>): T {
        drainUntil { future.isDone }
        return future.get(1, TimeUnit.SECONDS)
    }
}
