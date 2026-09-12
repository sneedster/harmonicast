package io.github.sneedster.harmonicast

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.Socket
import java.net.ServerSocket
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GuestRoomAccessNetworkTest {
    @Test fun plexHttpPreservesDenialStatusWithoutExposingResponseBody() = runBlocking {
        ServerSocket(0).use { server ->
            val worker = thread(isDaemon = true) {
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val body = "private-server-details"
                    socket.getOutputStream().write(("HTTP/1.1 403 Forbidden\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body").toByteArray())
                    socket.getOutputStream().flush()
                }
            }
            val error = runCatching {
                OkHttpPlexHttp().request("http://127.0.0.1:${server.localPort}/", "GET", mapOf("X-Plex-Token" to "shared-secret"), emptyMap())
            }.exceptionOrNull()
            worker.join(3000)
            assertTrue(error is PlexRequestFailure)
            assertEquals(403, (error as PlexRequestFailure).status)
            assertEquals("Plex request failed (403)", error.message)
        }
    }

    @Test fun sharedGatewayRequiresVerificationAndRejectsOldLanAndNearbyCapabilitiesAfterRevocation() = runBlocking {
        val fixture = AcquisitionFixture()
        fixture.source = fixture.source!!.copy(canWriteToPlex = false)
        var visible = true
        val guard = PlexRoomAccessGuard(fixture.source!!, { fixture.source }, { visible })
        val gateway = GuestRoomGateway(RuntimeEnvironment.getApplication(), fixture.core(),
            requestedPort = 0, bindAddress = "127.0.0.1", accessAllowed = guard::permitsRequests)
        try {
            assertThrows(IllegalStateException::class.java) { gateway.start() }
            assertEquals(PlexRoomAccess.AVAILABLE, guard.refresh())
            val room = gateway.start()
            assertTrue(room.enabled)
            assertTrue(room.port > 0)
            val bearer = room.joinUrl.substringAfter("#cap=")
            fun request(): String = Socket("127.0.0.1", room.port).use { socket ->
                socket.soTimeout = 3000
                socket.getOutputStream().write(("GET /v1/queue HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer $bearer\r\n\r\n").toByteArray())
                socket.getOutputStream().flush()
                socket.getInputStream().bufferedReader().readText()
            }
            assertTrue(request().startsWith("HTTP/1.1 200"))
            assertEquals(200, gateway.routeNearby(GuestApiRequest("GET", "/v1/queue", null)).status)
            visible = false
            assertEquals(PlexRoomAccess.DENIED, guard.refresh())
            assertTrue(request().startsWith("HTTP/1.1 403"))
            assertEquals(403, gateway.routeNearby(GuestApiRequest("GET", "/v1/queue", null)).status)
            assertFalse(request().contains("plex-secret"))
        } finally { guard.close(); gateway.stop() }
        assertFalse(gateway.isActive())
        assertEquals(401, gateway.routeNearby(GuestApiRequest("GET", "/v1/queue", null)).status)
    }
}
