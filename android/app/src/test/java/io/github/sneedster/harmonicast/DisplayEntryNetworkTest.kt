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
            assertEquals(template, page.substringAfter("\r\n\r\n"))
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
