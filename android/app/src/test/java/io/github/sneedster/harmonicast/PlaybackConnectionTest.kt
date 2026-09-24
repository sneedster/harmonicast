package io.github.sneedster.harmonicast

import com.google.common.util.concurrent.SettableFuture
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class PlaybackConnectionTest {
    private class Fake { var connected = true; var playing = true; var released = false }
    private val direct = Executor { it.run() }

    @Test fun missingControllerQueuesPauseUntilConnectionCompletes() {
        val future = SettableFuture.create<Fake>()
        var attempts = 0
        val connection = PlaybackConnection({ attempts++; future }, { it.connected }, { it.released = true }, {}, { throw it }, direct)
        connection.connect() // Local startup, before any network discovery.
        connection.connect { it.playing = false }
        assertEquals(1, attempts)
        val player = Fake()
        future.set(player)
        assertFalse(player.playing)
        connection.connect { it.playing = true }
        assertTrue(player.playing)
        assertEquals(1, attempts)
    }
    @Test fun staleControllerIsReleasedAndPauseReachesReplacement() {
        val first = Fake()
        val second = Fake()
        val futures = ArrayDeque(listOf(SettableFuture.create<Fake>().apply { set(first) }, SettableFuture.create<Fake>().apply { set(second) }))
        val connection = PlaybackConnection({ futures.removeFirst() }, { it.connected }, { it.released = true }, {}, { throw it }, direct)
        connection.connect()
        first.connected = false
        connection.connect { it.playing = false }
        assertTrue(first.released)
        assertFalse(second.playing)
    }
    @Test fun failedConnectionCanBeRetriedWithoutReplayingOldCommands() {
        val failed = SettableFuture.create<Fake>()
        val retry = SettableFuture.create<Fake>()
        val futures = ArrayDeque(listOf(failed, retry))
        var errors = 0
        var oldCommands = 0
        val connection = PlaybackConnection({ futures.removeFirst() }, { it.connected }, {}, {}, { errors++ }, direct)
        connection.connect { oldCommands++ }
        failed.setException(IllegalStateException("offline"))
        connection.connect { it.playing = false }
        val player = Fake()
        retry.set(player)
        assertEquals(1, errors)
        assertEquals(0, oldCommands)
        assertFalse(player.playing)
    }
    @Test fun closeReleasesLateConnectionAndDiscardsPendingTransport() {
        val pending = SettableFuture.create<Fake>()
        var published: Fake? = null
        val connection = PlaybackConnection({ pending }, { it.connected }, { it.released = true }, { published = it }, { throw it }, direct)
        connection.connect { it.playing = false }
        connection.close()
        val player = Fake()
        pending.set(player)
        assertTrue(player.released)
        assertTrue(player.playing)
        assertNull(published)
    }
}
