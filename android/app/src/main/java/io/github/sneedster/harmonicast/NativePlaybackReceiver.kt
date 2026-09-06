package io.github.sneedster.harmonicast

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import androidx.media3.common.*
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class NativeReceiverState(val address: String = "", val code: String = "", val title: String = "Receive playback",
    val artist: String = "", val message: String = "", val connected: Boolean = false)

/** Dedicated native media session; receiver mode never sends its Plex credentials to the host. */
class NativePlaybackReceiver : MediaSessionService() {
    companion object { val state = mutableStateOf(NativeReceiverState()) }
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private var server: ServerSocket? = null
    private val main = Handler(Looper.getMainLooper())
    private lateinit var pairing: NativePairing
    @Volatile private var host = ""
    @Volatile private var lastSeen = 0L
    private lateinit var audioHttp: okhttp3.OkHttpClient
    private var source = ""
    private var revision = -1L
    private var failure = false
    private var stopped = false
    private val watchdog = object : Runnable {
        override fun run() {
            if (stopped) return
            if (lastSeen != 0L && SystemClock.elapsedRealtime() - lastSeen > NativePlaybackProtocol.LEASE_MS) {
                disconnect("Host disconnected. Stop playing here to offer again.")
            }
            main.postDelayed(this, 500)
        }
    }
    override fun onCreate() {
        super.onCreate()
        if (!NativePlaybackProtocol.ownerEligible(this)) {
            state.value = NativeReceiverState(message = "Playback transfer requires an owner Plex sign-in on both devices.")
            stopSelf()
            return
        }
        val code = NativePlaybackProtocol.secret()
        pairing = NativePairing(code)
        player = ExoPlayer.Builder(this).setAudioAttributes(AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_LOCAL).build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { failure = true }
        })
        session = MediaSession.Builder(this, player).setId("room-playback-receiver").setSessionActivity(PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
        runCatching {
            val network = NativePlaybackProtocol.localNetwork(this)
            audioHttp = okhttp3.OkHttpClient.Builder().socketFactory(network.socketFactory)
                .proxy(java.net.Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false).build()
            val address = NativePlaybackProtocol.localAddress(this, network)
            val socket = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(address, NativePlaybackProtocol.PORT)) }
            server = socket
            state.value = NativeReceiverState(address, code, message = "Available to the room host for playback.")
            thread(name = "harmonicast-receiver", isDaemon = true) {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    runCatching { client.use { serve(it) } }
                }
            }
        }.onFailure { state.value = NativeReceiverState(message = it.message ?: "Could not open receiver") }
        main.post(watchdog)
    }
    private fun serve(socket: java.net.Socket) {
        val request = NativePlaybackProtocol.read(socket) ?: return
        if (!NativePlaybackProtocol.ownerEligible(this)) { NativePlaybackProtocol.reply(socket, 403); main.post { disconnect("Owner access is required") }; return }
        // No CORS/preflight support, and no requests originating in a browser.
        if (request.method != "POST" || "origin" in request.headers || "sec-fetch-mode" in request.headers) {
            NativePlaybackProtocol.reply(socket, 403); return
        }
        val data = JSONObject(request.body.ifBlank { "{}" })
        val address = socket.inetAddress.hostAddress.orEmpty()
        if (!NativePlaybackProtocol.isLocalAddress(address)) { NativePlaybackProtocol.reply(socket, 403); return }
        if (request.path == "/pair") {
            val token = pairing.pair(data.optString("code"))
            if (token == null) { NativePlaybackProtocol.reply(socket, 401); return }
            host = address
            lastSeen = SystemClock.elapsedRealtime()
            main.post { state.value = state.value.copy(connected = true, code = "", message = "Paired with host") }
            NativePlaybackProtocol.reply(socket, 200, JSONObject().put("token", token).toString())
            return
        }
        if (host != address || !pairing.authorized(request.headers["authorization"])) {
            NativePlaybackProtocol.reply(socket, 401); return
        }
        val latch = CountDownLatch(1)
        var response = "{}"
        var status = 200
        main.post {
            try {
                if (!pairing.authorized(request.headers["authorization"])) { status = 401; return@post }
                when (request.path) {
                    "/state" -> {
                        lastSeen = SystemClock.elapsedRealtime()
                        applyState(data)
                        response = JSONObject().put("position", player.currentPosition)
                            .put("ended", player.playbackState == Player.STATE_ENDED)
                            .put("playing", player.isPlaying).put("ready", player.playbackState == Player.STATE_READY)
                            .put("error", failure).toString()
                    }
                    "/stop" -> disconnect("Playback returned to the host. Stop playing here to offer again.")
                    else -> status = 404
                }
            } catch (e: Exception) {
                android.util.Log.w("HarmonicastTransfer", "Receiver rejected state: ${e.javaClass.simpleName} at ${e.stackTrace.firstOrNull()}")
                status = 400
            }
            finally { latch.countDown() }
        }
        if (!latch.await(2, TimeUnit.SECONDS)) status = 503
        NativePlaybackProtocol.reply(socket, status, response)
    }
    private fun applyState(data: JSONObject) {
        val url = data.getString("url")
        val uri = URI(url)
        if (!(uri.scheme == "http" && uri.host == host && uri.path == "/audio" && uri.userInfo == null)) {
            android.util.Log.w("HarmonicastTransfer", "Audio route mismatch: scheme=${uri.scheme} address=${uri.host} peer=$host path=${uri.path}")
            error("Invalid room audio route")
        }
        val nextRevision = data.getLong("revision")
        require(nextRevision >= revision)
        val position = data.optLong("position").coerceAtLeast(0)
        if (url != source) {
            source = url
            failure = false
            val factory = DefaultMediaSourceFactory(this).setDataSourceFactory(OkHttpDataSource.Factory(audioHttp)
                .setDefaultRequestProperties(mapOf("Authorization" to data.getString("audioToken"))))
            val item = MediaItem.Builder().setUri(url).setMediaMetadata(MediaMetadata.Builder()
                .setTitle(data.optString("title")).setArtist(data.optString("artist")).build()).build()
            player.setMediaSource(factory.createMediaSource(item), position)
            player.prepare()
        } else if (nextRevision != revision) player.seekTo(position)
        revision = nextRevision
        // Only a host command change alters playWhenReady, preserving local audio-focus/noisy pauses.
        if (data.optLong("command") != lastCommand) {
            lastCommand = data.optLong("command")
            player.playWhenReady = data.optBoolean("playing")
        }
        state.value = state.value.copy(title = data.optString("title"), artist = data.optString("artist"), message = "Controlled by the host")
    }
    private var lastCommand = -1L
    private fun disconnect(message: String) {
        pairing.revoke()
        lastSeen = 0
        player.stop()
        player.clearMediaItems()
        state.value = state.value.copy(connected = false, message = message)
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onDestroy() {
        stopped = true
        if (::pairing.isInitialized) pairing.revoke()
        main.removeCallbacks(watchdog)
        runCatching { server?.close() }
        session?.release()
        if (::player.isInitialized) player.release()
        state.value = NativeReceiverState()
        super.onDestroy()
    }
}
