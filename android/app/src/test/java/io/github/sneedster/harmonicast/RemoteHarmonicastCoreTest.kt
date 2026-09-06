package io.github.sneedster.harmonicast

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RemoteHarmonicastCoreTest {
    private class FakeApi : RemoteApi {
        override val base = "https://home.example"
        override val token = "owner token"
        data class Call(val path: String, val method: String, val body: JSONObject?)
        val calls = mutableListOf<Call>()
        var response = "{}"
        var failure: Exception? = null
        var listener: ((String) -> Unit)? = null
        var closed = false
        override fun observe(onMessage: (String) -> Unit, onDisconnected: () -> Unit): CoreSubscription {
            listener = onMessage
            return CoreSubscription { closed = true }
        }
        override suspend fun json(path: String, method: String, body: JSONObject?): String {
            calls.add(Call(path, method, body))
            failure?.let { throw it }
            return response
        }
    }

    @Test fun searchKeepsQueueAndMetadataFlagsAndEncodesQuery() = runBlocking {
        val api = FakeApi().apply { response = """[{"id":"a/b","title":"Song","artist":"Artist","isRadio":true,"isManual":false,"rating":9,"year":2001}]""" }
        val core = RemoteHarmonicastCore(api)
        val song = core.library.search("A & B").single()
        assertEquals("search?q=A+%26+B", api.calls.single().path)
        assertTrue(song.isRadio)
        assertFalse(song.isManual)
        assertEquals(9.0, song.rating!!, 0.0)
        assertEquals(2001, song.year)
        assertEquals("https://home.example/api/stream/a%2Fb?token=owner+token", core.library.streamUrl(song))
        assertNull(core.library.artworkUrl(song))
    }

    @Test fun resumeRetainsPositionAndAutomaticQueueOwnership() = runBlocking {
        val api = FakeApi().apply { response = """{"song":{"id":"a","title":"Song"},"isPlaying":true,"playbackPosition":12.25,"isAutoQueue":true}""" }
        val state = RemoteHarmonicastCore(api).playback.snapshot()
        assertEquals("a", state.nowPlaying.song?.id)
        assertTrue(state.nowPlaying.isPlaying)
        assertTrue(state.isAutoQueue)
        assertEquals(12.25, state.positionSeconds, 0.0)
    }

    @Test fun dequeueUsesEnvelopeManualFlagAndHandlesEmptyQueue() = runBlocking {
        val api = FakeApi().apply { response = """{"song":{"id":"a"},"isManual":false}""" }
        val core = RemoteHarmonicastCore(api)
        assertFalse(core.queue.dequeue().isManual)
        assertEquals("POST", api.calls.single().method)
        assertEquals("queue/dequeue", api.calls.single().path)
        api.response = """{"song":null}"""
        assertNull(core.queue.dequeue().song)
    }

    @Test fun playbackWritesKeepExistingServerPayloadsAndSeconds() = runBlocking {
        val api = FakeApi()
        val playback = RemoteHarmonicastCore(api).playback
        playback.publish(Song("a", "Song", "Artist"), true, true)
        assertTrue(api.calls.last().body!!.getBoolean("isAutoQueue"))
        assertEquals("a", api.calls.last().body!!.getJSONObject("song").getString("id"))
        playback.savePosition(12.25)
        assertEquals("now-playing/position", api.calls.last().path)
        assertEquals("PUT", api.calls.last().method)
        assertEquals(12.25, api.calls.last().body!!.getDouble("position"), 0.0)
        playback.publish(null, false)
        assertTrue(api.calls.last().body!!.isNull("song"))
        assertFalse(api.calls.last().body!!.getBoolean("isPlaying"))
        playback.scrobble("a", true)
        assertTrue(api.calls.last().body!!.getBoolean("submission"))
    }

    @Test fun eventsPreserveQueueAndSessionDistinctionAndCanBeClosed() {
        val api = FakeApi()
        val events = mutableListOf<CoreEvent>()
        val subscription = RemoteHarmonicastCore(api).observe({ events.add(it) }, {})
        listOf("queue", "player_session", "force_skip", "now-playing").forEach {
            api.listener!!("""{"type":"$it"}""")
        }
        assertEquals(listOf(CoreEvent.QUEUE_CHANGED, CoreEvent.PLAYER_SESSION_CHANGED, CoreEvent.FORCE_SKIP, CoreEvent.CHANGED), events)
        subscription.close()
        assertTrue(api.closed)
    }

    @Test fun remoteFailuresPropagateInsteadOfBecomingAnEmptyQueue() = runBlocking {
        val failure = IllegalStateException("Authentication required")
        val core = RemoteHarmonicastCore(FakeApi().apply { this.failure = failure })
        try {
            core.queue.songs()
            fail("Expected remote failure")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
    }
}
