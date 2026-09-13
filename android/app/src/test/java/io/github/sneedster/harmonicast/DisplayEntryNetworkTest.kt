package io.github.sneedster.harmonicast

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.Socket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DisplayEntryNetworkTest {
    @Test fun previousRoomLinksLoadCurrentPageButCannotAuthorizeIt() {
        val context = RuntimeEnvironment.getApplication()
        val previous = GuestRoomGateway(context, AcquisitionFixture().core(),
            requestedPort = 0, bindAddress = "127.0.0.1")
        val oldRoom = try { previous.start() } finally { previous.stop() }
        val current = GuestRoomGateway(context, AcquisitionFixture().core(),
            requestedPort = 0, bindAddress = "127.0.0.1")
        try {
            val room = current.start()
            fun get(path: String, token: String = ""): String =
                Socket("127.0.0.1", room.port).use { socket ->
                    socket.soTimeout = 3000
                    socket.getOutputStream().write(("GET $path HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer $token\r\n\r\n").toByteArray())
                    socket.getOutputStream().flush()
                    socket.getInputStream().bufferedReader().readText()
                }
            assertTrue(get("/display").contains("Room ${room.roomCode}"))
            assertTrue(get("/join").contains(room.roomCode))
            assertTrue(get("/v1/queue", oldRoom.displayUrl.substringAfter("#cap=")).startsWith("HTTP/1.1 401"))
            assertTrue(get("/v1/queue", oldRoom.joinUrl.substringAfter("#cap=")).startsWith("HTTP/1.1 401"))
            assertTrue(get("/v1/queue", room.displayUrl.substringAfter("#cap=")).startsWith("HTTP/1.1 200"))
            assertTrue(get("/v1/queue", room.joinUrl.substringAfter("#cap=")).startsWith("HTTP/1.1 200"))
        } finally { current.stop() }
    }

    @Test fun guestCodeEntryGrantsOnlyGuestAccessAndHonorsExpiryAndRateLimit() {
        var allowed = true
        val gateway = GuestRoomGateway(RuntimeEnvironment.getApplication(), AcquisitionFixture().core(),
            requestedPort = 0, bindAddress = "127.0.0.1", accessAllowed = { allowed })
        try {
            val room = gateway.start()
            fun request(path: String, body: String? = null, token: String = ""): String =
                Socket("127.0.0.1", room.port).use { socket ->
                    socket.soTimeout = 3000
                    val payload = buildString {
                        append("${if (body == null) "GET" else "POST"} $path HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer $token\r\n")
                        if (body != null) append("Content-Length: ${body.length}\r\n")
                        append("\r\n${body.orEmpty()}")
                    }
                    socket.getOutputStream().write(payload.toByteArray())
                    socket.getOutputStream().flush()
                    socket.getInputStream().bufferedReader().readText()
                }
            assertEquals("http://127.0.0.1:${room.port}", room.guestEntryUrl)
            val page = request("/enter")
            assertTrue(page.startsWith("HTTP/1.1 200"))
            assertTrue(page.contains("four-letter room code"))
            assertFalse(page.contains(room.joinUrl.substringAfter("#cap=")))
            assertFalse(page.contains("__ENTRY_"))
            assertTrue(request("/v1/guest/open", room.displayEntryCode).startsWith("HTTP/1.1 401"))
            val joined = request("/v1/guest/open", room.roomCode.lowercase())
            assertTrue(joined.startsWith("HTTP/1.1 200"))
            val token = JSONObject(joined.substringAfter("\r\n\r\n")).getString("capability")
            assertEquals(room.joinUrl.substringAfter("#cap="), token)
            assertNotEquals(room.displayUrl.substringAfter("#cap="), token)
            assertTrue(request("/v1/queue", token = token).startsWith("HTTP/1.1 200"))
            assertTrue(request("/v1/display/player/skip", "", token).startsWith("HTTP/1.1 404"))
            allowed = false
            assertTrue(request("/v1/guest/open", room.roomCode).startsWith("HTTP/1.1 403"))
        } finally { gateway.stop() }
        val capability = RoomCapability.create(nowMillis = 0, lifetimeMillis = 120_000)
        repeat(5) { assertEquals(401, capability.exchangeGuestCode("0000", 0).status) }
        assertEquals(429, capability.exchangeGuestCode(capability.roomCode, 0).status)
        assertEquals(200, capability.exchangeDisplayCode(capability.displayEntryCode, 0).status)
        assertEquals(200, capability.exchangeGuestCode(capability.roomCode, 60_000).status)
        assertEquals(401, capability.exchangeGuestCode(capability.roomCode, 120_000).status)
        capability.revoke()
        assertEquals(401, capability.exchangeGuestCode(capability.roomCode, 60_001).status)
    }

    @Test fun typedEntryLoadsWithoutSecretsAndExchangesOnlyForDisplayAccess() {
        var allowed = true
        val gateway = GuestRoomGateway(RuntimeEnvironment.getApplication(), AcquisitionFixture().core(),
            requestedPort = 0, bindAddress = "127.0.0.1", accessAllowed = { allowed })
        try {
            val room = gateway.start()
            fun request(path: String, body: String? = null, token: String? = null): String =
                Socket("127.0.0.1", room.port).use { socket ->
                    socket.soTimeout = 3000
                    val headers = buildString {
                        append("${if (body == null) "GET" else "POST"} $path HTTP/1.1\r\nHost: localhost\r\n")
                        if (body != null) append("Content-Length: ${body.length}\r\n")
                        if (token != null) append("Authorization: Bearer $token\r\n")
                        append("\r\n")
                        append(body.orEmpty())
                    }
                    socket.getOutputStream().write(headers.toByteArray())
                    socket.getOutputStream().flush()
                    socket.getInputStream().bufferedReader().readText()
                }
            assertEquals("http://127.0.0.1:${room.port}/open", room.displayEntryUrl)
            val page = request("/open")
            assertTrue(page.startsWith("HTTP/1.1 200"))
            assertTrue(page.contains("Display code"))
            val template = RuntimeEnvironment.getApplication().assets.open("display/open.html")
                .bufferedReader().use { it.readText() }
            assertEquals(GuestWebPage.renderEntry(template, guest = false), page.substringAfter("\r\n\r\n"))
            assertFalse(page.contains(room.displayUrl.substringAfter("#cap=")))
            assertTrue(request("/v1/display/open", room.roomCode).startsWith("HTTP/1.1 401"))
            val accepted = request("/v1/display/open", room.displayEntryCode)
            assertTrue(accepted.startsWith("HTTP/1.1 200"))
            val token = JSONObject(accepted.substringAfter("\r\n\r\n")).getString("capability")
            assertEquals(room.displayUrl.substringAfter("#cap="), token)
            assertTrue(request("/v1/queue", token = token).startsWith("HTTP/1.1 200"))
            assertTrue(request("/v1/requests", "{}", token).startsWith("HTTP/1.1 404"))
            allowed = false
            assertTrue(request("/v1/display/open", room.displayEntryCode).startsWith("HTTP/1.1 403"))
        } finally { gateway.stop() }
    }
}
