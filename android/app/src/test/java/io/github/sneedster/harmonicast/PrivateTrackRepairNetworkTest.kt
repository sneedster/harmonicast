package io.github.sneedster.harmonicast

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PrivateTrackRepairNetworkTest {
    private fun exercise(redirect: Boolean): Int {
        val count = AtomicInteger()
        ServerSocket(0).use { server ->
            server.soTimeout = 600
            val worker = thread {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (_: Exception) { break }
                    socket.use {
                        it.soTimeout = 2000
                        val reader = it.getInputStream().bufferedReader()
                        var length = 0
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                        }
                        repeat(length) { reader.read() }
                        count.incrementAndGet()
                        if (redirect) {
                            it.getOutputStream().write("HTTP/1.1 302 Found\r\nLocation: /another-track\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            it.getOutputStream().flush()
                        }
                    }
                }
            }
            val result = runBlocking { runCatching {
                OkHttpPlexHttp().request("http://127.0.0.1:${server.localPort}/library/metadata/42", "DELETE")
            } }
            worker.join(3000)
            assertTrue(result.isFailure)
        }
        return count.get()
    }
    @Test fun uncertainDeleteIsNeverReplayed() { assertEquals(1, exercise(false)) }
    @Test fun deleteNeverFollowsRedirectToAnotherResource() { assertEquals(1, exercise(true)) }
}
