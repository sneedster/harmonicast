package io.github.sneedster.harmonicast

import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class OfflineProxyTest {
    @Test fun fileResolutionRejectsAnythingOutsidePrivateCompletedAudio() {
        val root = kotlin.io.path.createTempDirectory().toFile()
        try {
            val inside = File(root, "scope/track.audio").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1, 2)) }
            val incomplete = File(root, "scope/track.part").apply { writeText("partial") }
            assertEquals(inside.canonicalFile, offlineAudioFile(root, inside.toURI().toString()))
            assertNull(offlineAudioFile(root, incomplete.toURI().toString()))
            assertNull(offlineAudioFile(File(root, "scope"), File(root, "outside.audio").apply { writeText("private") }.toURI().toString()))
            assertNull(offlineAudioFile(root, "https://example.com/audio"))
        } finally { root.deleteRecursively() }
    }
    @Test fun proxySupportsBoundedSeekRangeAndRejectsOutOfRange() {
        val file = kotlin.io.path.createTempFile(suffix = ".audio").toFile().apply { writeBytes(ByteArray(100) { it.toByte() }) }
        fun read(range: String): ByteArray {
            val server = ServerSocket(0)
            val worker = thread { server.accept().use { proxyOfflineAudio(it, file, range) { true } }; server.close() }
            val response = Socket("127.0.0.1", server.localPort).use { it.getInputStream().readBytes() }
            worker.join(2000); return response
        }
        try {
            val data = read("bytes=10-19")
            val text = data.toString(Charsets.ISO_8859_1)
            assertTrue(text.startsWith("HTTP/1.1 206"))
            assertTrue(text.contains("Content-Range: bytes 10-19/100"))
            assertArrayEquals(ByteArray(10) { (it + 10).toByte() }, data.takeLast(10).toByteArray())
            assertTrue(read("bytes=100-200").toString(Charsets.ISO_8859_1).startsWith("HTTP/1.1 416"))
        } finally { file.delete() }
    }
}
