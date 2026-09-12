package io.github.sneedster.harmonicast

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AcquisitionNetworkTest {
    // Accept complete requests, then drop the response to simulate an uncertain
    // connection failure after the server may already have handled the operation.
    private fun exercise(method: String, drops: Int, status: Int = 200): Pair<Result<JSONObject>, Int> {
        val count = AtomicInteger()
        ServerSocket(0).use { server ->
            server.soTimeout = 1000
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
                        val n = count.incrementAndGet()
                        if (n > drops) {
                            val body = "{\"ok\":true}"
                            it.getOutputStream().write(("HTTP/1.1 $status Test\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body").toByteArray())
                            it.getOutputStream().flush()
                        }
                    }
                }
            }
            val result = runBlocking { runCatching {
                AcquisitionNetwork().call("http://127.0.0.1:${server.localPort}/test", method, emptyMap(), if (method == "POST") JSONObject() else null)
            } }
            worker.join(3000)
            return result to count.get()
        }
    }
    @Test fun readRecoversOnceAfterDroppedResponse() {
        val (result, count) = exercise("GET", 1)
        assertTrue(result.getOrThrow().getBoolean("ok")); assertEquals(2, count)
    }
    @Test fun repeatedReadFailureStopsAfterTwoAttempts() {
        val (result, count) = exercise("GET", 2)
        assertTrue(result.isFailure); assertEquals(2, count)
    }
    @Test fun uncertainSubmissionIsNeverReplayedByTransport() {
        val (result, count) = exercise("POST", 1)
        assertTrue(result.isFailure); assertEquals(1, count)
    }
    @Test fun authenticationRejectionIsNotATransportRetry() {
        val (result, count) = exercise("GET", 0, 401)
        assertEquals(401, (result.exceptionOrNull() as AcquisitionFailure).status); assertEquals(1, count)
    }
    @Test fun errorsDistinguishTimeoutFromLostLoginWithoutExposingDetails() {
        val text = safeAcquisitionError(SocketTimeoutException("secret-url-and-token"))
        assertTrue(text.contains("timed out")); assertFalse(text.contains("secret"))
        assertFalse(safeAcquisitionError(java.io.IOException("secret")).contains("secret"))
    }
}
