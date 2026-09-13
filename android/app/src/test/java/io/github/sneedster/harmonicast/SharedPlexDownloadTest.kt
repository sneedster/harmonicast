package io.github.sneedster.harmonicast

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.Socket
import java.net.URI
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedPlexDownloadTest {
    private data class Response(val headers: String, val body: ByteArray) {
        val code get() = headers.lineSequence().first().split(' ')[1].toInt()
    }
    private fun request(base: String, path: String = "/", method: String = "GET", host: String = URI(base).authority): Response {
        val uri = URI(base)
        return Socket(uri.host, uri.port).use { socket ->
            socket.soTimeout = 10000
            socket.getOutputStream().apply {
                write("$method $path HTTP/1.1\r\nHost: $host\r\nContent-Length: 0\r\n\r\n".toByteArray()); flush()
            }
            val bytes = socket.getInputStream().readBytes()
            val boundary = bytes.toString(Charsets.ISO_8859_1).indexOf("\r\n\r\n")
            require(boundary >= 0)
            Response(bytes.copyOfRange(0, boundary).toString(Charsets.UTF_8), bytes.copyOfRange(boundary + 4, bytes.size))
        }
    }

    @Test fun computerDownloadsExactPackagedZipWithoutMusicGrabberOrPairing() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val gateway = AcquisitionSetupGateway(context, testBindAddress = "127.0.0.1")
        try {
            gateway.start()
            val base = gateway.state.value.url
            assertEquals("", gateway.state.value.code)
            val page = request(base)
            assertEquals(200, page.code)
            assertTrue(page.body.toString(Charsets.UTF_8).contains("Download setup ZIP"))
            val zip = request(base, "/harmonicast-plex-setup.zip")
            assertEquals(200, zip.code)
            assertTrue(zip.headers.contains("Content-Type: application/zip"))
            assertTrue(zip.headers.contains("Content-Disposition: attachment; filename=\"harmonicast-plex-setup.zip\""))
            assertTrue(zip.headers.contains("Content-Length: ${zip.body.size}"))
            assertArrayEquals(context.assets.open("shared-plex/harmonicast-plex-setup.zip").use { it.readBytes() }, zip.body)
            val entries = mutableMapOf<String, ByteArray>()
            ZipInputStream(zip.body.inputStream()).use { stream ->
                while (true) {
                    val entry = stream.nextEntry ?: break
                    entries[entry.name] = stream.readBytes()
                }
            }
            assertEquals(setOf("README.txt", "Harmonicast/", "Harmonicast/Shared Access Setup/",
                "Harmonicast/Shared Access Setup/01-setup.flac"), entries.keys)
            assertArrayEquals(context.assets.open("shared-plex/01-setup.flac").use { it.readBytes() },
                entries.getValue("Harmonicast/Shared Access Setup/01-setup.flac"))
            assertTrue(entries.getValue("README.txt").toString(Charsets.UTF_8).contains("AS PLEX SEES IT"))
        } finally { gateway.close() }
    }

    @Test fun downloadModeRejectsAccountRoutesOtherHostsAndExpiredRequests() = runBlocking {
        var now = 0L
        val gateway = AcquisitionSetupGateway(RuntimeEnvironment.getApplication(), testBindAddress = "127.0.0.1",
            pairing = SetupPairing { now })
        try {
            gateway.start(); val base = gateway.state.value.url
            for (path in listOf("/pair", "/validate", "/status")) assertEquals(404, request(base, path, "POST").code)
            assertEquals(404, request(base, "/../setup/index.html").code)
            assertEquals(403, request(base, host = "untrusted.test").code)
            now = 300001L
            assertEquals(403, request(base, "/harmonicast-plex-setup.zip").code)
            gateway.close()
            assertEquals("", gateway.state.value.url)
        } finally { gateway.close() }
    }
}
