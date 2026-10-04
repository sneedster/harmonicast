package io.github.sneedster.harmonicast

import androidx.media3.common.MediaItem
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** Main-thread control with a separate, revocable current-track audio proxy. */
internal class NativePlaybackHost private constructor(
    private val receiver: String,
    private val token: String,
    private val http: OkHttpClient,
    private val address: String,
    private val offlineRoot: java.io.File,
) {
    private data class Stream(val path: String, val upstream: String)
    @Volatile private var stream: Stream? = null
    @Volatile private var closed = false
    private val audioToken = NativePlaybackProtocol.secret()
    private val server = ServerSocket().apply { bind(InetSocketAddress(address, 0)) }
    private val sockets = ConcurrentHashMap.newKeySet<java.net.Socket>()
    private val workers = ThreadPoolExecutor(2, 4, 20, TimeUnit.SECONDS, ArrayBlockingQueue(8))
    private var item: MediaItem? = null
    private var revision = 0L
    private var command = 0L
    private var seekPosition = 0L
    private var wantedPlaying = false
    private var endedReported = false
    var position = 0L; private set
    var playing = false; private set
    private var job: Job? = null

    init {
        thread(name = "harmonicast-native-audio", isDaemon = true) {
            while (!closed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                sockets.add(socket)
                runCatching { workers.execute {
                    try { socket.use { proxy(it) } } catch (_: Exception) { /* connection ended */ }
                    finally { sockets.remove(socket) }
                } }.onFailure { sockets.remove(socket); socket.close() }
            }
        }
    }
    fun load(next: MediaItem, positionMs: Long, play: Boolean) {
        val uri = next.localConfiguration?.uri ?: error("Track has no audio")
        item = next
        stream = Stream("/audio?track=${NativePlaybackProtocol.secret()}", uri.toString())
        position = positionMs.coerceAtLeast(0)
        seekPosition = position
        revision++
        command++
        wantedPlaying = play
        endedReported = false
    }
    fun play(value: Boolean) { wantedPlaying = value; command++ }
    fun seek(value: Long) { position = value.coerceAtLeast(0); seekPosition = position; revision++; endedReported = false }
    fun monitor(scope: CoroutineScope, update: suspend (Boolean) -> Unit, failed: () -> Unit) {
        job = scope.launch {
            try {
                while (isActive && !closed) {
                    val current = item
                    val audio = stream
                    if (current != null && audio != null) {
                        val sentRevision = revision
                        val sentCommand = command
                        val data = JSONObject().put("url", "http://$address:${server.localPort}${audio.path}")
                            .put("audioToken", audioToken).put("revision", revision).put("command", command)
                            .put("position", seekPosition).put("playing", wantedPlaying)
                            .put("title", current.mediaMetadata.title?.toString().orEmpty())
                            .put("artist", current.mediaMetadata.artist?.toString().orEmpty())
                        val result = withContext(Dispatchers.IO) { call("/state", data) }
                        if (result.optBoolean("error")) error("Receiver playback failed")
                        if (revision == sentRevision && command == sentCommand) {
                            position = result.optLong("position").coerceAtLeast(0)
                            playing = result.optBoolean("playing")
                            val ended = result.optBoolean("ended") && !endedReported
                            if (ended) endedReported = true
                            update(ended)
                        }
                    }
                    delay(750)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                android.util.Log.w("HarmonicastTransfer", "Host transport failed: ${e.javaClass.simpleName} at ${e.stackTrace.firstOrNull()}")
                if (!closed) failed()
            }
        }
    }
    suspend fun stop(): Boolean {
        job?.cancel()
        val acknowledged = withContext(Dispatchers.IO) { runCatching { call("/stop", JSONObject()) }.isSuccess }
        close()
        return acknowledged
    }
    fun close() {
        closed = true
        job?.cancel()
        stream = null
        runCatching { server.close() }
        sockets.forEach { runCatching { it.close() } }
        workers.shutdownNow()
    }
    private fun call(path: String, data: JSONObject): JSONObject {
        val request = Request.Builder().url("http://$receiver:${NativePlaybackProtocol.PORT}$path")
            .header("Authorization", token).post(data.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return http.newCall(request).execute().use {
            check(it.isSuccessful) { "Receiver connection failed (${it.code})" }
            JSONObject(it.body?.string().orEmpty())
        }
    }
    private fun proxy(socket: java.net.Socket) {
        val request = NativePlaybackProtocol.read(socket) ?: return
        val selected = stream
        if (closed || selected == null || request.method != "GET" || request.path != selected.path ||
            socket.inetAddress.hostAddress != receiver || "origin" in request.headers || "sec-fetch-mode" in request.headers ||
            !NativePlaybackProtocol.matches(request.headers["authorization"], audioToken)) {
            NativePlaybackProtocol.reply(socket, 401); return
        }
        if (selected.upstream.startsWith("file:")) {
            val file = offlineAudioFile(offlineRoot, selected.upstream) ?: run { NativePlaybackProtocol.reply(socket, 404); return }
            proxyOfflineAudio(socket, file, request.headers["range"], { !closed && stream === selected })
            return
        }
        val connection = URL(selected.upstream).openConnection() as HttpURLConnection
        connection.connectTimeout = 4_000
        connection.readTimeout = 4_000
        connection.instanceFollowRedirects = false
        request.headers["range"]?.takeIf { Regex("bytes=\\d+-\\d*").matches(it) }?.let { connection.setRequestProperty("Range", it) }
        try {
            val status = connection.responseCode
            if (status != 200 && status != 206) { NativePlaybackProtocol.reply(socket, 502); return }
            val out = socket.getOutputStream()
            val header = buildString {
                append("HTTP/1.1 $status OK\r\nContent-Type: ${connection.contentType ?: "audio/mpeg"}\r\n")
                for (name in listOf("Content-Length", "Content-Range", "Accept-Ranges")) connection.getHeaderField(name)?.let { append("$name: $it\r\n") }
                append("Cache-Control: no-store\r\nConnection: close\r\n\r\n")
            }
            out.write(header.toByteArray())
            connection.inputStream.use { input ->
                val buffer = ByteArray(32768)
                while (!closed && stream === selected) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    out.write(buffer, 0, count)
                }
            }
            out.flush()
        } finally { connection.disconnect() }
    }
    companion object {
        suspend fun pair(context: android.content.Context, address: String, code: String): NativePlaybackHost = withContext(Dispatchers.IO) {
            require(NativePlaybackProtocol.isLocalAddress(address)) { "The room player needs a local Wi-Fi address" }
            require(code.length == 43) { "Room playback offer has expired" }
            val network = NativePlaybackProtocol.localNetwork(context)
            val client = OkHttpClient.Builder().socketFactory(network.socketFactory).proxy(java.net.Proxy.NO_PROXY).connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS)
                .callTimeout(3, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
            val request = Request.Builder().url("http://$address:${NativePlaybackProtocol.PORT}/pair")
                .post(JSONObject().put("code", code).toString().toRequestBody("application/json".toMediaType())).build()
            val token = client.newCall(request).execute().use {
                check(it.isSuccessful) { "Room player is unavailable. Offer playback again from that device." }
                JSONObject(it.body?.string().orEmpty()).getString("token")
            }
            require(token.length in 32..128)
            NativePlaybackHost(address, token, client, NativePlaybackProtocol.localAddress(context, network), java.io.File(context.noBackupFilesDir, "offline_music"))
        }
    }
}

/** Same peer/token guard as Plex proxying, with a strict private-download root. */
internal fun offlineAudioFile(root: java.io.File, uri: String): java.io.File? = runCatching {
    val file = java.io.File(java.net.URI(uri)).canonicalFile
    file.takeIf { it.isFile && it.extension == "audio" && it.path.startsWith(root.canonicalPath + java.io.File.separator) }
}.getOrNull()

internal fun proxyOfflineAudio(socket: java.net.Socket, file: java.io.File, range: String?, active: () -> Boolean) {
    java.io.RandomAccessFile(file, "r").use { input ->
        val total = input.length()
        val match = range?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
        val start = match?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val end = match?.groupValues?.get(2)?.toLongOrNull()?.coerceAtMost(total - 1) ?: total - 1
        if ((range != null && (match == null || match.groupValues[1].toLongOrNull() == null || (match.groupValues[2].isNotEmpty() && match.groupValues[2].toLongOrNull() == null))) || start < 0 || start >= total || end < start) {
            NativePlaybackProtocol.reply(socket, 416); return
        }
        val output = socket.getOutputStream()
        val header = buildString {
            append("HTTP/1.1 ${if (range == null) 200 else 206} OK\r\nContent-Type: application/octet-stream\r\n")
            append("Content-Length: ${end - start + 1}\r\nAccept-Ranges: bytes\r\n")
            if (range != null) append("Content-Range: bytes $start-$end/$total\r\n")
            append("Cache-Control: no-store\r\nConnection: close\r\n\r\n")
        }
        output.write(header.toByteArray()); input.seek(start)
        var remaining = end - start + 1
        val buffer = ByteArray(32768)
        while (remaining > 0 && active()) {
            val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (count < 0) break
            output.write(buffer, 0, count); remaining -= count
        }
        output.flush()
    }
}
