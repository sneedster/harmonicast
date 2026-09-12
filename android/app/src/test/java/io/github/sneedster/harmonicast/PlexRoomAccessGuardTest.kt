package io.github.sneedster.harmonicast

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PlexRoomAccessGuardTest {
    private val source = PersonalPlexSource("shared", "https://plex", "server", "Server", "7", "Music", canWriteToPlex = false)

    @Test fun initialUnavailableAccessCannotOpenRoomButEstablishedRoomSurvivesOutage() = runBlocking {
        var online = false
        val guard = PlexRoomAccessGuard(source, { source }, { if (!online) throw java.io.IOException("secret") else true })
        assertFalse(guard.permitsRequests())
        assertEquals(PlexRoomAccess.UNAVAILABLE, guard.refresh())
        assertFalse(guard.permitsRequests())
        online = true
        assertEquals(PlexRoomAccess.AVAILABLE, guard.refresh())
        assertTrue(guard.permitsRequests())
        online = false
        assertEquals(PlexRoomAccess.UNAVAILABLE, guard.refresh())
        assertTrue(guard.permitsRequests())
    }

    @Test fun authorizationDenialRevokesButServerFailuresDoNot() = runBlocking {
        for (status in listOf(401, 403, 404, 429, 500, 503)) {
            var failure = false
            val guard = PlexRoomAccessGuard(source, { source }, { if (failure) throw PlexRequestFailure(status) else true })
            guard.refresh(); failure = true
            val denied = status in setOf(401, 403)
            assertEquals(if (denied) PlexRoomAccess.DENIED else PlexRoomAccess.UNAVAILABLE, guard.refresh())
            assertEquals(!denied, guard.permitsRequests())
            if (denied) {
                failure = false
                assertEquals(PlexRoomAccess.SOURCE_CHANGED, guard.refresh())
                assertFalse(guard.permitsRequests())
            }
        }
    }

    @Test fun missingLibraryAndSignOutBlockRequests() = runBlocking {
        var selected: PersonalPlexSource? = source
        var visible = true
        val guard = PlexRoomAccessGuard(source, { selected }, { visible })
        guard.refresh()
        selected = null
        assertFalse(guard.permitsRequests())
        selected = source
        visible = false
        assertEquals(PlexRoomAccess.DENIED, guard.refresh())
        assertFalse(guard.permitsRequests())
    }

    @Test fun sourceChangeOrTeardownDuringProbeCannotAuthorizeOldRoom() = runBlocking {
        for (teardown in listOf(false, true)) {
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            var selected = source
            val guard = PlexRoomAccessGuard(source, { selected }, { started.complete(Unit); finish.await(); true })
            val result = async { guard.refresh() }
            started.await()
            if (teardown) guard.close() else selected = source.copy(token = "other-account")
            finish.complete(Unit)
            assertEquals(PlexRoomAccess.SOURCE_CHANGED, result.await())
            assertFalse(guard.permitsRequests())
        }
    }

    @Test fun joiningGuestModeInvalidatesRoom() = runBlocking {
        var joined = false
        val guard = PlexRoomAccessGuard(source, { source }, { true }, { joined })
        guard.refresh(); joined = true
        assertFalse(guard.permitsRequests())
        assertEquals(PlexRoomAccess.SOURCE_CHANGED, guard.refresh())
        joined = false
        assertFalse(guard.permitsRequests())
    }

    @Test fun timeoutFailsClosedAndExternalCancellationPropagates() = runBlocking {
        val guard = PlexRoomAccessGuard(source, { source }, { awaitCancellation() }, timeoutMillis = 10)
        assertEquals(PlexRoomAccess.UNAVAILABLE, guard.refresh())
        assertFalse(guard.permitsRequests())
        val cancelled = async { guard.refresh() }
        cancelled.cancel()
        assertTrue(runCatching { cancelled.await() }.exceptionOrNull() is CancellationException)
    }
}
