package io.github.sneedster.harmonicast

import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import kotlin.concurrent.thread

class SharedPlexTransportTest {
    @Test fun boundedTransportAcceptsShortBodiesRejectsLargeBodiesAndDoesNotFollowRedirects() = runBlocking {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val destinationHits = AtomicInteger()
        val worker = thread(isDaemon = true) {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val reader = socket.getInputStream().bufferedReader()
                    val path = reader.readLine().split(' ')[1]
                    while (!reader.readLine().isNullOrBlank()) { }
                    val body = when (path) { "/small" -> "small"; "/large" -> "A".repeat(128); else -> "" }
                    if (path == "/destination") destinationHits.incrementAndGet()
                    val status = if (path == "/redirect") "302 Found\r\nLocation: /destination" else "200 OK"
                    socket.getOutputStream().write(("HTTP/1.1 $status\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body").toByteArray())
                }
            } catch (_: java.io.IOException) { }
        }
        try {
            val base = "http://127.0.0.1:${server.localPort}"
            val transport = OkHttpPlexHttp(OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build(), 16)
            assertEquals("small", transport.request("$base/small"))
            assertTrue(runCatching { transport.request("$base/large") }.isFailure)
            val redirect = runCatching { transport.request("$base/redirect", headers = mapOf("X-Plex-Token" to "private")) }.exceptionOrNull()
            assertEquals(302, (redirect as PlexRequestFailure).status)
            assertEquals(0, destinationHits.get())
        } finally { server.close(); worker.join(3000) }
    }
}
