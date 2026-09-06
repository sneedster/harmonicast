package io.github.sneedster.harmonicast

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.common.MediaItem
import coil.compose.AsyncImage
import com.google.common.util.concurrent.MoreExecutors
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.roundToInt

// Playback control is delegated to HarmonicastMediaService via MediaController.
// The ViewModel only reads state for display and forwards user commands.
class HarmonicastViewModel : ViewModel() {
    private lateinit var context: Context
    private lateinit var api: Api
    private lateinit var core: HarmonicastCore
    private lateinit var plex: LocalPlexClient
    private var socket: CoreSubscription? = null
    private var socketReconnectJob: kotlinx.coroutines.Job? = null
    private var socketStopped = false
    var ready by mutableStateOf(false); private set
    var loading by mutableStateOf(false); var error by mutableStateOf("")
    var notice by mutableStateOf(""); private set
    var queue by mutableStateOf<List<Song>>(emptyList()); var nowPlaying by mutableStateOf(NowPlaying())
    var playbackPosition by mutableFloatStateOf(0f); private set
    var artistDiscovery by mutableStateOf<ArtistDiscovery?>(null); private set
    var artistDiscoveryLoading by mutableStateOf(false); private set
    var artistDiscoveryError by mutableStateOf(""); private set
    var isHost by mutableStateOf(false); var isActivePlayer by mutableStateOf(false)
    var ratedTrackShare by mutableIntStateOf(8); private set
    var settingsSaving by mutableStateOf(false); private set
    var configured by mutableStateOf(true)
    var needsPlexSetup by mutableStateOf(false); private set
    var isPlexSetupOwner by mutableStateOf(false); private set
    var plexServers by mutableStateOf<List<PlexServer>>(emptyList()); private set
    var plexLibraries by mutableStateOf<List<PlexLibrary>>(emptyList()); private set
    var selectedPlexServer by mutableStateOf<PlexServer?>(null); private set
    var plexSourceLabel by mutableStateOf(""); private set
    var playlists by mutableStateOf<List<PlexPlaylist>>(emptyList()); private set
    var playlistsLoading by mutableStateOf(false); private set
    var results by mutableStateOf<List<Song>>(emptyList()); var query by mutableStateOf("")
    var searchLoading by mutableStateOf(false); private set
    var libraryArtistBrowse by mutableStateOf<LibraryArtistBrowse?>(null); private set
    var musicSourceExtension by mutableStateOf<MusicSourceExtension?>(null); private set
    var musicSourceDialog by mutableStateOf<MusicSourceDialog?>(null); private set
    var musicSourceRecordings by mutableStateOf<List<MusicSourceRecording>>(emptyList()); private set
    var musicSourceAlbums by mutableStateOf<List<MusicSourceAlbum>>(emptyList()); private set
    var musicSourceArtist by mutableStateOf<MusicSourceArtist?>(null); private set
    var musicSourceViewingTracks by mutableStateOf(false); private set
    var musicSourceHasMoreAlbums by mutableStateOf(false); private set
    var musicSourceLoading by mutableStateOf(false); private set
    var musicSourceMessage by mutableStateOf(""); private set
    private var searchGeneration = 0
    var controller by mutableStateOf<MediaController?>(null); private set
    var savedBaseUrl by mutableStateOf("")
    var personalSetupActive by mutableStateOf(false); private set
    val isPersonalMode: Boolean get() = ::api.isInitialized && api.profile.mode == HomeMode.PERSONAL_PLEX
    val personalSetupCanCancel: Boolean get() = ::api.isInitialized && api.profile.homeReady
    private var personalPin: PlexPin? = null
    private var personalAuthJob: kotlinx.coroutines.Job? = null
    private var personalToken = ""
    private var personalServerBase = ""
    var nearbyRoomState by mutableStateOf(NearbyRoomState()); private set
    private var nearbyRoomClient: NearbyRoomClient? = null

    fun initialize(appContext: Context) {
        if (::api.isInitialized) return
        context = appContext
        api = Api(context.getSharedPreferences("harmonicast", Context.MODE_PRIVATE))
        plex = LocalPlexClient(api.storage)
        core = harmonicastCore(api)
        savedBaseUrl = api.base
        ready = api.profile.homeReady
        if (ready) {
            connectPlaybackService()
            refresh()
        }
    }

    private fun connectPlaybackService() {
        if (controller != null) return
        try {
            context.startService(Intent(context, HarmonicastMediaService::class.java))
        } catch (e: Exception) {
            android.util.Log.e("Harmonicast", "Playback service startup failed", e)
            error = "Playback service could not start"
        }

        val sessionToken = SessionToken(context, ComponentName(context, HarmonicastMediaService::class.java))
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture.addListener({
            try {
                controller = controllerFuture.get()
            } catch (e: Exception) {
                android.util.Log.e("Harmonicast", "Playback service connection failed", e)
                error = "Playback service could not start"
            }
        }, MoreExecutors.directExecutor())
    }

    fun serverUrl() = savedBaseUrl
    fun artworkUrl(song: Song) = core.library.artworkUrl(song)
    fun setServer(url: String) { api.setBase(url); savedBaseUrl = api.base; ready = api.profile.ready; error = "" }
    fun authUrl(): String = "${api.base}/api/auth/plex?mobile_redirect=harmonicast%3A%2F%2Fauth"

    fun beginPersonalSetup(openUrl: (String) -> Unit) {
        viewModelScope.launch {
            loading = true
            error = ""
            try {
                personalSetupActive = true
                val pending = plex.createPin()
                personalPin = pending
                startPersonalSignInPolling(pending)
                openUrl(plex.authorizationUrl(pending))
            } catch (e: Exception) {
                personalSetupActive = false
                error = e.message ?: "Could not start Plex sign-in"
            } finally {
                loading = false
            }
        }
    }

    fun beginPersonalSourceChange() {
        val source = api.profile.personalSource ?: return
        setGuestControl(false)
        personalAuthJob?.cancel()
        personalPin = null
        personalToken = source.token
        personalServerBase = ""
        selectedPlexServer = null
        plexLibraries = emptyList()
        plexServers = emptyList()
        error = ""
        personalSetupActive = true
        viewModelScope.launch {
            loading = true
            try {
                plexServers = plex.ownedServers(source.token)
                if (plexServers.isEmpty()) error = "No owned Plex servers were found."
            } catch (e: Exception) {
                error = e.message ?: "Could not load owned Plex servers"
            } finally {
                loading = false
            }
        }
    }

    fun cancelPersonalSetup() {
        if (!api.profile.homeReady) return
        personalAuthJob?.cancel()
        personalPin = null
        personalSetupActive = false
        selectedPlexServer = null
        plexLibraries = emptyList()
        plexServers = emptyList()
        error = ""
    }

    fun signOutPersonalPlex() {
        if (!isPersonalMode) return
        setGuestControl(false)
        leaveNearbyRoom()
        personalAuthJob?.cancel()
        personalPin = null
        socketReconnectJob?.cancel()
        socket?.close()
        socket = null
        api.profile.clearPersonalSource()
        personalToken = ""
        personalServerBase = ""
        personalSetupActive = false
        selectedPlexServer = null
        plexServers = emptyList()
        plexLibraries = emptyList()
        plexSourceLabel = ""
        playlists = emptyList()
        queue = emptyList()
        nowPlaying = NowPlaying()
        results = emptyList()
        core = harmonicastCore(api)
        ready = false
        error = ""
        context.startService(
            Intent(context, HarmonicastMediaService::class.java)
                .setAction(HarmonicastMediaService.RELOAD_PROFILE_ACTION)
        )
    }
    
    fun receiveAuth(uri: Uri) {
        if (uri.host == "plex-auth") {
            completePersonalSignIn()
            return
        }
        android.util.Log.d("HarmonicastAuth", "receiveAuth called with: $uri")
        val token = uri.fragment?.split("&")?.firstOrNull { it.startsWith("auth_token=") }?.removePrefix("auth_token=") 
            ?: uri.getQueryParameter("auth_token") 
            ?: uri.getQueryParameter("token")
        
        if (token.isNullOrBlank()) {
            error = "Sign-in did not return a token"
            return
        }
        api.setToken(token)
        ready = api.profile.ready
        if (ready) {
            core = harmonicastCore(api)
            connectPlaybackService()
            refresh()
        }
    }

    private fun completePersonalSignIn() {
        val pending = personalPin ?: return
        if (personalAuthJob?.isActive != true) startPersonalSignInPolling(pending)
    }

    private fun startPersonalSignInPolling(pending: PlexPin) {
        personalAuthJob?.cancel()
        personalAuthJob = viewModelScope.launch {
            var lastFailure: Exception? = null
            try {
                repeat(180) {
                    val claimed = try {
                        plex.readPin(pending).also { lastFailure = null }
                    } catch (e: Exception) {
                        lastFailure = e
                        null
                    }
                    val token = claimed?.authToken
                    if (!token.isNullOrBlank()) {
                        personalToken = token
                        plexServers = plex.ownedServers(token)
                        if (plexServers.isEmpty()) error = "No owned Plex servers were found."
                        return@launch
                    }
                    delay(1_000)
                }
                error = lastFailure?.message ?: "Plex sign-in timed out. Start it again to get a new PIN."
            } catch (e: Exception) {
                error = e.message ?: "Could not finish Plex sign-in"
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            loading = true
            try {
                val connection = core.guests.policy()
                configured = connection.configured
                isHost = connection.isHost
                isActivePlayer = connection.isActivePlayer
                needsPlexSetup = connection.needsPlexSetup
                isPlexSetupOwner = connection.isSetupOwner
                if (configured) {
                    queue = core.queue.songs()
                    val state = core.playback.snapshot()
                    nowPlaying = state.nowPlaying
                    playbackPosition = state.positionSeconds.toFloat().coerceAtLeast(0f)
                    ratedTrackShare = core.queue.ratedTrackShare()
                    ensureSocket()
                    if (isHost) loadPlexSource()
                    if (api.profile.mode == HomeMode.PERSONAL_PLEX && playlists.isEmpty()) loadPlaylists()
                } else if (needsPlexSetup && isPlexSetupOwner) {
                    loadPlexServers()
                }
                // Refresh runs continuously. A transient network failure must not
                // leave its error visible after a later request succeeds.
                error = ""
            } catch (e: Exception) {
                error = e.message ?: "Could not connect"
                if ((e.message ?: "").contains("Authentication required")) ready = false
            } finally {
                loading = false
            }
        }
    }

    private fun ensureSocket() {
        if (socketStopped || socket != null || !ready) return
        socket = core.observe(
            onEvent = { refresh() },
            onDisconnected = {
                socket = null
                if (!socketStopped) {
                    socketReconnectJob?.cancel()
                    socketReconnectJob = viewModelScope.launch {
                        delay(1_000)
                        ensureSocket()
                    }
                }
            },
        )
    }

    private fun loadPlexSource() {
        api.profile.personalSource?.let {
            plexSourceLabel = "${it.serverName} · ${it.libraryName}"
            return
        }
        viewModelScope.launch {
            runCatching {
                val source = JSONObject(api.json("plex/source"))
                if (source.optBoolean("configured")) {
                    val server = source.optJSONObject("server")?.optString("name").orEmpty()
                    val selectedKey = source.optString("selectedLibraryKey")
                    val libraries = source.optJSONArray("libraries") ?: JSONArray()
                    val library = (0 until libraries.length()).asSequence()
                        .map { libraries.getJSONObject(it) }
                        .firstOrNull { it.optString("key") == selectedKey }
                        ?.optString("title").orEmpty()
                    plexSourceLabel = listOf(server, library).filter { it.isNotBlank() }.joinToString(" · ")
                }
            }
        }
    }

    private fun loadPlexServers() {
        viewModelScope.launch {
            try {
                val items = JSONObject(api.json("setup/plex/servers")).optJSONArray("servers") ?: JSONArray()
                plexServers = List(items.length()) { index ->
                    val item = items.getJSONObject(index)
                    PlexServer(item.optString("machineIdentifier"), item.optString("name"))
                }
            } catch (e: Exception) {
                error = e.message ?: "Could not load owned Plex servers"
            }
        }
    }

    fun choosePlexServer(server: PlexServer) {
        selectedPlexServer = server
        plexLibraries = emptyList()
        viewModelScope.launch {
            loading = true
            try {
                if (personalSetupActive) {
                    personalServerBase = plex.connect(personalToken, server)
                    plexLibraries = plex.musicLibraries(personalServerBase, personalToken)
                    if (plexLibraries.isEmpty()) error = "This Plex server has no Music libraries."
                    return@launch
                }
                val response = JSONObject(api.json("setup/plex/servers/${URLEncoder.encode(server.machineIdentifier, "UTF-8")}/libraries"))
                val items = response.optJSONArray("libraries") ?: JSONArray()
                plexLibraries = List(items.length()) { index ->
                    val item = items.getJSONObject(index)
                    PlexLibrary(item.optString("key"), item.optString("title"))
                }
                if (plexLibraries.isEmpty()) error = "This Plex server has no Music libraries."
            } catch (e: Exception) {
                error = e.message ?: "Could not reach that Plex server"
            } finally {
                loading = false
            }
        }
    }

    fun selectPlexLibrary(library: PlexLibrary) {
        val server = selectedPlexServer ?: return
        if (personalSetupActive) {
            val previous = api.profile.personalSource
            if (previous != null && (
                    previous.machineIdentifier != server.machineIdentifier || previous.libraryKey != library.key
                )) {
                api.profile.clearPersonalPlaybackState()
            }
            api.profile.savePersonalSource(PersonalPlexSource(
                personalToken,
                personalServerBase,
                server.machineIdentifier,
                server.name,
                library.key,
                library.title,
            ))
            core = harmonicastCore(api)
            ready = api.profile.homeReady
            personalSetupActive = false
            context.startService(
                Intent(context, HarmonicastMediaService::class.java)
                    .setAction(HarmonicastMediaService.RELOAD_PROFILE_ACTION)
            )
            connectPlaybackService()
            refresh()
            return
        }
        action("setup/plex/select", "POST", JSONObject()
            .put("machineIdentifier", server.machineIdentifier)
            .put("libraryKey", library.key)) { refresh() }
    }

    fun setGuestControl(enabled: Boolean) {
        val action = if (enabled) HarmonicastMediaService.ENABLE_GUEST_CONTROL_ACTION
        else HarmonicastMediaService.DISABLE_GUEST_CONTROL_ACTION
        context.startService(Intent(context, HarmonicastMediaService::class.java).setAction(action))
    }

    fun scanNearbyRoom() {
        if (!::context.isInitialized) return
        val client = nearbyRoomClient ?: NearbyRoomClient(context) { state -> nearbyRoomState = state }
            .also { nearbyRoomClient = it }
        client.scan()
    }

    fun joinNearbyRoom(roomCode: String) = nearbyRoomClient?.connect(roomCode)

    fun leaveNearbyRoom() {
        nearbyRoomClient?.close()
        nearbyRoomClient = null
        nearbyRoomState = NearbyRoomState()
    }

    fun loadNearbyQueue(offset: Int = 0) = nearbyRoomClient?.loadQueue(offset)
    fun searchNearbyRoom(query: String, offset: Int = 0) = nearbyRoomClient?.search(query, offset)
    fun requestNearbySong(song: Song) = nearbyRoomClient?.request(song)
    fun voteNearby(up: Boolean) = nearbyRoomClient?.vote(up)

    fun loadPlaylists() {
        if (playlistsLoading) return
        viewModelScope.launch {
            playlistsLoading = true
            try {
                playlists = core.library.playlists()
            } catch (e: Exception) {
                error = e.message ?: "Could not load Plex playlists"
            } finally {
                playlistsLoading = false
            }
        }
    }

    fun loadPlaylist(playlist: PlexPlaylist, action: PlaylistAction) {
        viewModelScope.launch {
            playlistsLoading = true
            try {
                var tracks = core.library.playlistTracks(playlist.id)
                if (action == PlaylistAction.SHUFFLE) tracks = tracks.shuffled()
                if (tracks.isEmpty()) {
                    error = "${playlist.title} has no playable tracks"
                    return@launch
                }
                when (action) {
                    PlaylistAction.PLAY, PlaylistAction.SHUFFLE -> {
                        core.queue.clear()
                        core.queue.addAll(tracks)
                        controller?.seekToNext()
                    }
                    PlaylistAction.NEXT -> core.queue.addAll(tracks, next = true)
                    PlaylistAction.QUEUE -> core.queue.addAll(tracks)
                }
                val skipped = (playlist.trackCount - tracks.size).coerceAtLeast(0)
                showTemporaryNotice(
                    "${playlist.title} · ${tracks.size} tracks" +
                        if (skipped > 0) " · $skipped unavailable skipped" else "",
                )
                refresh()
            } catch (e: Exception) {
                error = e.message ?: "Could not load playlist"
            } finally {
                playlistsLoading = false
            }
        }
    }

    fun search() {
        viewModelScope.launch {
            if (query.isBlank()) return@launch
            val generation = ++searchGeneration
            searchLoading = true
            musicSourceExtension = null
            libraryArtistBrowse = null
            try {
                val term = query.trim()
                val localResults = core.library.search(term)
                val artist = core.library.artist(term)
                if (generation != searchGeneration) return@launch
                results = localResults
                libraryArtistBrowse = artist
                if (api.profile.mode == HomeMode.REMOTE_SERVER && (localResults.isEmpty() || artist != null)) {
                    val extension = JSONObject(api.json("extensions/music-sources")).optJSONObject("extension")
                        ?.let { MusicSourceExtension(it.optString("id"), it.optString("displayName"), it.optBoolean("available")) }
                    if (generation == searchGeneration) musicSourceExtension = extension?.takeIf { it.available }
                }
                error = ""
            } catch (e: Exception) {
                if (generation == searchGeneration) error = e.message ?: "Search failed"
            } finally {
                if (generation == searchGeneration) searchLoading = false
            }
        }
    }

    fun openMusicSource(mode: String) {
        val extension = musicSourceExtension ?: return
        val term = query.trim()
        if (term.isBlank()) return
        viewModelScope.launch {
            musicSourceDialog = MusicSourceDialog(extension.id, extension.displayName, mode, term)
            musicSourceRecordings = emptyList()
            musicSourceAlbums = emptyList()
            musicSourceArtist = null
            musicSourceViewingTracks = false
            musicSourceHasMoreAlbums = false
            musicSourceMessage = ""
            musicSourceLoading = true
            try {
                val body = JSONObject().put("query", term).put("mode", mode)
                val request = JSONObject(api.json("extensions/music-sources/${URLEncoder.encode(extension.id, "UTF-8")}/launch", "POST", body))
                val requestId = request.getString("requestId")
                musicSourceDialog = musicSourceDialog?.copy(requestId = requestId)
                loadMusicSourceRecordings(requestId)
            } catch (e: Exception) {
                musicSourceMessage = e.message ?: "Connected music sources are unavailable"
                musicSourceLoading = false
            }
        }
    }

    private suspend fun loadMusicSourceRecordings(requestId: String) {
        val dialog = musicSourceDialog ?: return
        try {
            val payload = JSONObject(api.json("plugins/${URLEncoder.encode(dialog.id, "UTF-8")}/requests/${URLEncoder.encode(requestId, "UTF-8")}/recordings", "POST", JSONObject()))
            val artist = payload.optJSONObject("artist")?.let { MusicSourceArtist(it.optString("id"), it.optString("name")) }
            if (artist != null) {
                musicSourceArtist = artist
                loadMusicSourceAlbums(artist.id, 0)
            } else {
                musicSourceRecordings = recordings(payload.optJSONArray("recordings") ?: JSONArray())
            }
        } catch (e: Exception) {
            musicSourceMessage = e.message ?: "MusicBrainz lookup failed"
        } finally {
            musicSourceLoading = false
        }
    }

    fun openMusicSourceAlbum(album: MusicSourceAlbum) {
        val dialog = musicSourceDialog ?: return
        val artist = musicSourceArtist ?: return
        val requestId = dialog.requestId ?: return
        viewModelScope.launch {
            musicSourceLoading = true
            musicSourceMessage = ""
            try {
                val body = JSONObject().put("releaseGroupId", album.id).put("artistName", artist.name)
                val payload = JSONObject(api.json("plugins/${URLEncoder.encode(dialog.id, "UTF-8")}/requests/${URLEncoder.encode(requestId, "UTF-8")}/release-group-tracks", "POST", body))
                musicSourceRecordings = recordings(payload.optJSONArray("recordings") ?: JSONArray())
                musicSourceViewingTracks = true
            } catch (e: Exception) {
                musicSourceMessage = e.message ?: "Could not load this album"
            } finally {
                musicSourceLoading = false
            }
        }
    }

    fun loadMoreMusicSourceAlbums() {
        val artist = musicSourceArtist ?: return
        viewModelScope.launch { loadMusicSourceAlbums(artist.id, musicSourceAlbums.size) }
    }

    private suspend fun loadMusicSourceAlbums(artistId: String, offset: Int) {
        val dialog = musicSourceDialog ?: return
        val requestId = dialog.requestId ?: return
        musicSourceLoading = true
        musicSourceMessage = ""
        try {
            val body = JSONObject().put("artistId", artistId).put("offset", offset)
            val payload = JSONObject(api.json("plugins/${URLEncoder.encode(dialog.id, "UTF-8")}/requests/${URLEncoder.encode(requestId, "UTF-8")}/artist-albums", "POST", body))
            val albums = albums(payload.optJSONArray("albums") ?: JSONArray())
            musicSourceAlbums = if (offset == 0) albums else musicSourceAlbums + albums
            musicSourceHasMoreAlbums = albums.size == 25
        } catch (e: Exception) {
            musicSourceMessage = e.message ?: "Could not load artist albums"
        } finally {
            musicSourceLoading = false
        }
    }

    fun requestMusicSourceRecording(recording: MusicSourceRecording) {
        val dialog = musicSourceDialog ?: return
        val requestId = dialog.requestId ?: return
        viewModelScope.launch {
            musicSourceLoading = true
            musicSourceMessage = "Requesting the best available source…"
            try {
                val body = JSONObject().put("recordingId", recording.id).put("artist", recording.artist)
                    .put("title", recording.title).put("durationMs", recording.durationMs)
                api.json("plugins/${URLEncoder.encode(dialog.id, "UTF-8")}/requests/${URLEncoder.encode(requestId, "UTF-8")}/acquire", "POST", body)
                waitForMusicSourceRequest(requestId, recording.title)
            } catch (e: Exception) {
                musicSourceMessage = e.message ?: "Could not request this recording"
                musicSourceLoading = false
            }
        }
    }

    /** Poll briefly so a request is not presented as queued before Plex verifies it. */
    private suspend fun waitForMusicSourceRequest(requestId: String, title: String) {
        repeat(8) {
            delay(2_000)
            try {
                val state = JSONObject(api.json("extensions/music-sources/requests/${URLEncoder.encode(requestId, "UTF-8")}"))
                val status = state.optString("status")
                val message = state.optString("message")
                musicSourceMessage = message.ifBlank {
                    when (status) {
                        "acquiring" -> "Finding the best available source…"
                        "waiting_for_plex" -> "Waiting for it to reach your Plex library…"
                        else -> "Processing your request…"
                    }
                }
                when (status) {
                    "fulfilled" -> {
                        showTemporaryNotice("Added $title to the acquisition queue")
                        closeMusicSource()
                        refresh()
                        return
                    }
                    "failed" -> {
                        musicSourceLoading = false
                        return
                    }
                }
            } catch (e: Exception) {
                musicSourceMessage = e.message ?: "Could not check request status"
                musicSourceLoading = false
                return
            }
        }
        musicSourceMessage = "Your request is still being processed. It will join the queue once Plex finds it."
        musicSourceLoading = false
    }

    fun closeMusicSource() {
        musicSourceDialog = null
        musicSourceRecordings = emptyList()
        musicSourceAlbums = emptyList()
        musicSourceArtist = null
        musicSourceViewingTracks = false
        musicSourceLoading = false
        musicSourceMessage = ""
    }

    fun backMusicSource() {
        if (musicSourceViewingTracks) {
            musicSourceViewingTracks = false
            musicSourceRecordings = emptyList()
        } else closeMusicSource()
    }

    fun add(song: Song) = coreAction { core.queue.add(song); refresh() }
    fun remove(song: Song) = coreAction { core.queue.remove(song.id); refresh() }
    fun vote(up: Boolean) = coreAction { core.guests.vote(up) }
    fun claim() = coreAction { core.playback.claim(); refresh() }
    fun clearQueue() = coreAction { core.queue.clear(); refresh() }
    fun saveRatedTrackShare(share: Int, announce: Boolean = true) {
        if (!isHost) return
        val value = share.coerceIn(0, 10)
        ratedTrackShare = value
        viewModelScope.launch {
            settingsSaving = true
            try {
                core.queue.setRatedTrackShare(value)
                if (announce) showTemporaryNotice("Automatic mix saved")
            } catch (e: Exception) {
                error = e.message ?: "Could not save automatic mix"
            } finally {
                settingsSaving = false
            }
        }
    }

    fun queueSimilar() {
        viewModelScope.launch {
            try {
                val added = core.queue.radio()
                if (added > 0) showTemporaryNotice("Track Radio ready · $added songs queued")
                else error = "No Track Radio songs were found"
                refresh()
            } catch (e: Exception) {
                error = e.message ?: "Could not queue Track Radio"
            }
        }
    }

    private fun showTemporaryNotice(message: String) {
        notice = message
        viewModelScope.launch {
            delay(3_500)
            if (notice == message) notice = ""
        }
    }

    /** Enables Jukebox, populates its random queue, and starts its first song. */
    fun startRandomPlayback() {
        viewModelScope.launch {
            try {
                core.queue.enableAutomaticPlayback()
                controller?.seekToNext()
            } catch (e: Exception) {
                error = "Failed to start random playback"
            }
        }
    }
    
    fun toggle() {
        nowPlaying.song ?: return
        if (nowPlaying.isPlaying) controller?.pause() else controller?.play()
    }

    fun previousSong() {
        if (isActivePlayer) controller?.seekToPrevious()
    }

    fun nextSong() = coreAction { core.playback.skip() }
    fun playQueued(song: Song) {
        if (!isActivePlayer) return
        controller?.setMediaItem(MediaItem.Builder().setMediaId(song.id).build())
        controller?.prepare()
        controller?.play()
    }
    fun seekTo(seconds: Float) { controller?.seekTo((seconds.coerceAtLeast(0f) * 1_000).toLong()) }

    fun loadArtistDiscovery(song: Song) {
        viewModelScope.launch {
            artistDiscoveryLoading = true
            artistDiscovery = null
            artistDiscoveryError = ""
            try {
                artistDiscovery = core.library.discovery(song)
            } catch (e: Exception) {
                artistDiscoveryError = e.message ?: "Could not load artist discovery"
            } finally {
                artistDiscoveryLoading = false
            }
        }
    }

    private fun coreAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() } catch (e: Exception) { error = e.message ?: "Request failed" }
        }
    }

    private fun action(path: String, method: String, body: JSONObject, done: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                api.json(path, method, body)
                done()
            } catch (e: Exception) {
                error = e.message ?: "Request failed"
            }
        }
    }

    override fun onCleared() {
        socketStopped = true
        socketReconnectJob?.cancel()
        socket?.close()
        nearbyRoomClient?.close()
        controller?.let { MediaController.releaseFuture(com.google.common.util.concurrent.Futures.immediateFuture(it)) }
        super.onCleared()
    }

    private fun recordings(a: JSONArray) = List(a.length()) { index ->
        val item = a.getJSONObject(index)
        MusicSourceRecording(item.optString("id"), item.optString("title"), item.optString("artist"),
            item.optString("album").takeIf { !item.isNull("album") }, item.optString("year").takeIf { !item.isNull("year") },
            item.optLong("durationMs").takeIf { !item.isNull("durationMs") }, item.optString("disambiguation").takeIf { !item.isNull("disambiguation") })
    }
    private fun albums(a: JSONArray) = List(a.length()) { index ->
        val item = a.getJSONObject(index)
        MusicSourceAlbum(item.optString("id"), item.optString("title"), item.optString("year").takeIf { !item.isNull("year") }, item.optString("type").takeIf { !item.isNull("type") })
    }
}

data class MusicSourceDialog(val id: String, val displayName: String, val mode: String, val query: String, val requestId: String? = null)
enum class PlaylistAction { PLAY, SHUFFLE, NEXT, QUEUE }

class MainActivity : ComponentActivity() {
    private var authUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authUri = intent?.data
        setContent {
            val vm: HarmonicastViewModel = viewModel()
            LaunchedEffect(Unit) { vm.initialize(applicationContext) }
            LaunchedEffect(authUri) { authUri?.let(vm::receiveAuth) }
            LaunchedEffect(vm.ready) {
                while (vm.ready) {
                    delay(5_000)
                    vm.refresh()
                }
            }
            HarmonicastApp(vm)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        authUri = intent.data
    }
}

@Composable private fun HarmonicastApp(vm: HarmonicastViewModel) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xffd0a2ff))) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            when {
                vm.nearbyRoomState.connected -> NearbyGuestScreen(vm)
                vm.personalSetupActive -> PersonalSetupPage(vm)
                !vm.ready -> Login(vm)
                else -> Home(vm)
            }
        }
    }
}

@Composable private fun NearbyGuestScreen(vm: HarmonicastViewModel) {
    val room = vm.nearbyRoomState
    var search by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(WindowInsets.safeDrawing.asPaddingValues())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Harmonicast", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text("Room ${room.roomCode}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = vm::leaveNearbyRoom) {
                Icon(Icons.AutoMirrored.Filled.Logout, "Leave room")
                Spacer(Modifier.width(6.dp))
                Text("Leave")
            }
        }
        Surface(shape = RoundedCornerShape(50), color = Color(0xff30243a)) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.BluetoothConnected, null, Modifier.size(18.dp), tint = Color(0xffd0a2ff))
                Spacer(Modifier.width(7.dp))
                Text("Nearby guest · connected", style = MaterialTheme.typography.labelLarge, color = Color(0xffeadcff))
            }
        }
        ElevatedCard(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = Color(0xff281e32)),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("NOW PLAYING", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color(0xffd0a2ff))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    GuestArtwork(room.title, 116.dp, room.artwork)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            if (room.title.isBlank()) "Nothing is playing" else room.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (room.artist.isNotBlank()) Text(room.artist, style = MaterialTheme.typography.titleMedium, color = Color(0xffeadcff), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (room.album.isNotBlank()) Text(room.album, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (room.isPlaying) Icons.Default.GraphicEq else Icons.Default.PauseCircle,
                                null,
                                Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (room.isPlaying) "Playing on the host" else "Paused on the host", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                if (room.durationSeconds > 0) {
                    LinearProgressIndicator(
                        progress = { (room.positionSeconds.toFloat() / room.durationSeconds).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatDuration(room.positionSeconds), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatDuration(room.durationSeconds), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(
                        selected = room.vote < 0,
                        onClick = { vm.voteNearby(false) },
                        enabled = !room.busy && room.title.isNotBlank(),
                        label = { Text(if (room.vote < 0) "Voted down" else "Vote down") },
                        leadingIcon = { Icon(Icons.Default.ThumbDown, null, Modifier.size(18.dp)) },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = room.vote > 0,
                        onClick = { vm.voteNearby(true) },
                        enabled = !room.busy && room.title.isNotBlank(),
                        label = { Text(if (room.vote > 0) "Voted up" else "Vote up") },
                        leadingIcon = { Icon(Icons.Default.ThumbUp, null, Modifier.size(18.dp)) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Request music", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Search the host's library and add something to the shared queue.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Song, artist, or album") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { vm.searchNearbyRoom(search) },
                enabled = search.isNotBlank() && !room.busy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Default.Search, null)
                Spacer(Modifier.width(8.dp))
                Text("Search host library")
            }
        }
        room.searchResults.forEach { song ->
            GuestSongCard(song, actionLabel = "Request", enabled = !room.busy) { vm.requestNearbySong(song) }
        }
        if (room.searchResults.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    onClick = { vm.searchNearbyRoom(search, (room.searchOffset - NearbyRoomWire.PAGE_SIZE).coerceAtLeast(0)) },
                    enabled = room.searchOffset > 0 && !room.busy,
                ) { Text("Previous results") }
                TextButton(
                    onClick = { vm.searchNearbyRoom(search, room.searchOffset + NearbyRoomWire.PAGE_SIZE) },
                    enabled = room.searchHasMore && !room.busy,
                ) { Text("More results") }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Up next", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Shared request queue", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { vm.loadNearbyQueue(room.queueOffset) }, enabled = !room.busy) { Icon(Icons.Default.Refresh, "Refresh queue") }
        }
        if (room.queue.isEmpty() && !room.busy) {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = Color(0xff242029)) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text("The request queue is empty", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        room.queue.forEachIndexed { index, song ->
            GuestSongCard(song, queueNumber = room.queueOffset + index + 1)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                onClick = { vm.loadNearbyQueue((room.queueOffset - NearbyRoomWire.PAGE_SIZE).coerceAtLeast(0)) },
                enabled = room.queueOffset > 0 && !room.busy,
            ) { Text("Previous") }
            TextButton(
                onClick = { vm.loadNearbyQueue(room.queueOffset + NearbyRoomWire.PAGE_SIZE) },
                enabled = room.queueHasMore && !room.busy,
            ) { Text("More") }
        }
        if (room.busy) LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)))
        if (room.message.isNotBlank()) GuestNotice(room.message, false)
        if (room.error.isNotBlank()) GuestNotice(room.error, true)
        Spacer(Modifier.height(6.dp))
    }
}

@Composable private fun GuestArtwork(seed: String, size: androidx.compose.ui.unit.Dp, artwork: ByteArray? = null) {
    val colors = listOf(Color(0xff6f3b82), Color(0xff315c70), Color(0xff72532d), Color(0xff5b456f), Color(0xff386252))
    val color = colors[(seed.hashCode().toLong().let { if (it < 0) -it else it } % colors.size).toInt()]
    val shape = RoundedCornerShape(if (size >= 100.dp) 22.dp else 12.dp)
    val bitmap = remember(artwork) {
        artwork?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.asImageBitmap()
    }
    Surface(Modifier.size(size), shape = shape, color = color) {
        Box(contentAlignment = Alignment.Center) {
            if (bitmap == null) {
                Icon(Icons.Default.Album, null, Modifier.size(size * 0.52f), tint = Color.White.copy(alpha = 0.9f))
            } else {
                Image(bitmap, null, Modifier.fillMaxSize().clip(shape), contentScale = ContentScale.Crop)
            }
        }
    }
}

@Composable private fun GuestSongCard(
    song: Song,
    queueNumber: Int? = null,
    actionLabel: String? = null,
    enabled: Boolean = true,
    onAction: () -> Unit = {},
) {
    ElevatedCard(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = Color(0xff242029)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
            if (queueNumber != null) {
                Surface(Modifier.size(46.dp), shape = RoundedCornerShape(12.dp), color = Color(0xff453259)) {
                    Box(contentAlignment = Alignment.Center) { Text(queueNumber.toString(), fontWeight = FontWeight.Bold, color = Color(0xffeadcff)) }
                }
            } else GuestArtwork(song.title, 48.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (song.album.isNotBlank()) Text(song.album, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (actionLabel != null) {
                FilledTonalButton(onClick = onAction, enabled = enabled, contentPadding = PaddingValues(horizontal = 13.dp, vertical = 8.dp)) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable private fun GuestNotice(message: String, error: Boolean) {
    val background = if (error) MaterialTheme.colorScheme.errorContainer else Color(0xff30243a)
    val foreground = if (error) MaterialTheme.colorScheme.onErrorContainer else Color(0xffeadcff)
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = background) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (error) Icons.Default.ErrorOutline else Icons.Default.CheckCircle, null, tint = foreground)
            Spacer(Modifier.width(9.dp))
            Text(message, color = foreground)
        }
    }
}

@Composable private fun PersonalSetupPage(vm: HarmonicastViewModel) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize().padding(28.dp), Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(if (vm.personalSetupCanCancel) "Change Plex music" else "Set up personal mode", style = MaterialTheme.typography.headlineSmall)
            Text("Choose the Plex server and Music library this phone will play directly.")
            PersonalPlexSetup(vm)
            if (vm.plexServers.isEmpty()) {
                OutlinedButton(
                    onClick = {
                        vm.beginPersonalSetup { url ->
                            CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
                        }
                    },
                    enabled = !vm.loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Restart Plex sign-in")
                }
            }
            if (vm.personalSetupCanCancel) {
                OutlinedButton(onClick = vm::cancelPersonalSetup, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel")
                }
            }
            if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (vm.error.isNotBlank()) Text(vm.error, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable private fun Login(vm: HarmonicastViewModel) {
    var server by remember(vm.savedBaseUrl) { mutableStateOf(vm.serverUrl()) }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val dismissKeyboard = {
        focusManager.clearFocus()
        keyboardController?.hide()
    }
    val nearbyPermissions = remember { bluetoothPermissions(advertise = false) }
    val nearbyPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (nearbyPermissions.all {
                result[it] == true || ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }) vm.scanNearbyRoom()
        else vm.error = "Nearby devices permission is required to find a room"
    }
    Box(Modifier.fillMaxSize().padding(28.dp), Alignment.Center) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Harmonicast", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text("Your personal Plex music player.")
            Button(
                onClick = {
                    vm.beginPersonalSetup { url ->
                        CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
                    }
                },
                enabled = !vm.loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Login, null)
                Spacer(Modifier.width(8.dp))
                Text("Sign in with Plex")
            }
            if (vm.personalSetupActive) {
                PersonalPlexSetup(vm)
            } else {
                HorizontalDivider()
                Text("Existing Harmonicast server", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(server, { server = it }, label = { Text("Server URL") }, placeholder = { Text("https://harmonicast.example.com") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { vm.setServer(server); CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(vm.authUrl())) }, enabled = server.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text("Connect during migration")
                }
            }
            HorizontalDivider()
            Text("Guest mode", style = MaterialTheme.typography.titleSmall)
            Text("Join a nearby host over Bluetooth. This phone keeps its current internet connection and does not need Plex credentials.")
            Button(
                onClick = {
                    dismissKeyboard()
                    if (nearbyPermissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
                        vm.scanNearbyRoom()
                    } else nearbyPermissionLauncher.launch(nearbyPermissions)
                },
                enabled = !vm.nearbyRoomState.scanning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (vm.nearbyRoomState.scanning) "Looking for nearby rooms…" else if (vm.nearbyRoomState.availableRooms.size > 1) "Scan again" else "Join nearby room")
            }
            val nearby = vm.nearbyRoomState
            if (!nearby.connected && nearby.availableRooms.size > 1) {
                Text("Choose a nearby room", style = MaterialTheme.typography.titleMedium)
                nearby.availableRooms.forEach { roomCode ->
                    OutlinedButton(
                        onClick = {
                            dismissKeyboard()
                            vm.joinNearbyRoom(roomCode)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.BluetoothConnected, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Room $roomCode")
                    }
                }
            }
            if (!nearby.connected && nearby.message.isNotBlank()) {
                Text(nearby.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (nearby.connected) {
                Text("Connected to room ${nearby.roomCode}", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (nearby.title.isBlank()) "Nothing is playing" else "${nearby.title} — ${nearby.artist}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = vm::leaveNearbyRoom, modifier = Modifier.fillMaxWidth()) { Text("Leave room") }
            } else if (nearby.error.isNotBlank()) {
                Text(nearby.error, color = MaterialTheme.colorScheme.error)
            }
            if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (vm.error.isNotBlank()) Text(vm.error, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable private fun PersonalPlexSetup(vm: HarmonicastViewModel) {
    when {
        vm.plexServers.isEmpty() -> Text("Finish signing in with Plex, then return here.")
        vm.selectedPlexServer == null -> {
            Text("Choose your Plex server", style = MaterialTheme.typography.titleMedium)
            vm.plexServers.forEach { server ->
                OutlinedButton(onClick = { vm.choosePlexServer(server) }, modifier = Modifier.fillMaxWidth()) {
                    Text(server.name)
                }
            }
        }
        else -> {
            Text("${vm.selectedPlexServer?.name} — choose a Music library", style = MaterialTheme.typography.titleMedium)
            vm.plexLibraries.forEach { library ->
                Button(onClick = { vm.selectPlexLibrary(library) }, modifier = Modifier.fillMaxWidth()) {
                    Text(library.title)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun Home(vm: HarmonicastViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    if (!vm.configured) {
        PlexMusicSetup(vm)
        return
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Harmonicast")
                        Text(
                            "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        if (vm.plexSourceLabel.isNotBlank()) {
                            Text(vm.plexSourceLabel, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                actions = {
                    if (vm.loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    if (vm.isHost && !vm.isActivePlayer) {
                        IconButton(onClick = { vm.claim() }) {
                            Icon(Icons.Default.PlayCircle, contentDescription = "Take control of playback on this device")
                        }
                    }
                    if (vm.isHost && vm.nowPlaying.song != null) {
                        IconButton(onClick = { vm.queueSimilar() }, enabled = vm.isActivePlayer) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = "Start Track Radio")
                        }
                    }
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding()) {
                PhonePlayerControls(vm)
                NavigationBar(Modifier.height(56.dp), windowInsets = WindowInsets(0, 0, 0, 0)) {
                    val items = listOf(
                        "Now playing" to Icons.Default.MusicNote,
                        "Queue" to Icons.AutoMirrored.Filled.QueueMusic,
                        "Search" to Icons.Default.Search,
                        "Playlists" to Icons.AutoMirrored.Filled.PlaylistPlay,
                        "Settings" to Icons.Default.Settings,
                    )
                    items.forEachIndexed { index, item ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            icon = { Icon(item.second, item.first) },
                            label = null,
                            alwaysShowLabel = false,
                        )
                    }
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> Now(vm) { term -> vm.query = term; vm.search(); tab = 2 }
                1 -> Queue(vm)
                2 -> Search(vm)
                3 -> PlaylistsScreen(vm)
                else -> SettingsScreen(vm)
            }
            if (vm.musicSourceDialog != null) MusicSourceSheet(vm)
            val message = vm.error.ifBlank { vm.notice }
            if (message.isNotBlank()) {
                Snackbar(modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp), action = {}) {
                    Text(message)
                }
            }
        }
    }
}

@Composable private fun PlaylistsScreen(vm: HarmonicastViewModel) {
    if (!vm.isPersonalMode) {
        Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
            Text("Plex playlists become available when this device moves to personal mode.", textAlign = TextAlign.Center)
        }
        return
    }
    LaunchedEffect(Unit) { vm.loadPlaylists() }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Plex playlists", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = vm::loadPlaylists, enabled = !vm.playlistsLoading) {
                Icon(Icons.Default.Refresh, "Refresh playlists")
            }
        }
        if (vm.playlistsLoading && vm.playlists.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!vm.playlistsLoading && vm.playlists.isEmpty()) {
            Text("No audio playlists were found in this Plex account.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(vm.playlists, key = PlexPlaylist::id) { playlist ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(playlist.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (playlist.trackCount > 0) Text("${playlist.trackCount} tracks", style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { vm.loadPlaylist(playlist, PlaylistAction.PLAY) }) { Text("Play") }
                            TextButton(onClick = { vm.loadPlaylist(playlist, PlaylistAction.SHUFFLE) }) { Text("Shuffle") }
                            TextButton(onClick = { vm.loadPlaylist(playlist, PlaylistAction.NEXT) }) { Text("Next") }
                            TextButton(onClick = { vm.loadPlaylist(playlist, PlaylistAction.QUEUE) }) { Text("Queue") }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun SettingsScreen(vm: HarmonicastViewModel) {
    var share by remember(vm.ratedTrackShare) { mutableFloatStateOf(vm.ratedTrackShare.toFloat()) }
    var confirmPlexSignOut by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val hostPermissions = remember { bluetoothPermissions(advertise = true) }
    val hostPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (hostPermissions.all {
                result[it] == true || ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }) vm.setGuestControl(true)
        else vm.error = "Nearby devices permission is required to open a Bluetooth room"
    }
    val value = share.roundToInt().coerceIn(0, 10)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        if (!vm.isPersonalMode) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Personal mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Play directly from Plex on this phone. Your current server profile remains saved during migration.")
                    Button(
                        onClick = {
                            vm.beginPersonalSetup { url ->
                                CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
                            }
                        },
                        enabled = !vm.loading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Move this device to personal mode")
                    }
                }
            }
        }
        if (vm.isPersonalMode) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Plex music source", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        vm.plexSourceLabel.ifBlank { "Personal Plex library" },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = vm::beginPersonalSourceChange,
                        enabled = !vm.loading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Change Plex server or library")
                    }
                    TextButton(onClick = { confirmPlexSignOut = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Sign out of Plex", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            val room = HarmonicastMediaService.roomShareState.value
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Allow guest control", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("Temporary and accountless", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = room.enabled,
                            onCheckedChange = { enabled ->
                                if (!enabled) vm.setGuestControl(false)
                                else if (hostPermissions.all {
                                        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                                    }) vm.setGuestControl(true)
                                else hostPermissionLauncher.launch(hostPermissions)
                            },
                        )
                    }
                    Text(
                        "Guests can search, request tracks, view the queue and now playing, and vote. Plex credentials and owner controls stay on this phone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (room.error.isNotBlank()) {
                        Text(room.error, color = MaterialTheme.colorScheme.error)
                    }
                    if (room.enabled) {
                        Text("Room ${room.roomCode}", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            if (room.nearbyAvailable) "Bluetooth room is ready" else "Bluetooth is unavailable; same-Wi-Fi access still works",
                            color = if (room.nearbyAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                        Text(
                            "Guests already on this Wi-Fi can scan the code below. Bluetooth joining will use the Harmonicast guest app and keep each phone's existing internet connection.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text("Same-Wi-Fi browser controller", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        RoomQrCode(room.joinUrl, "Open Harmonicast room ${room.roomCode}")
                        Button(
                            onClick = {
                                context.startActivity(Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, room.joinUrl)
                                    },
                                    "Share Harmonicast room",
                                ))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Share guest link")
                        }
                        OutlinedButton(onClick = { vm.setGuestControl(false) }, modifier = Modifier.fillMaxWidth()) {
                            Text("End guest room")
                        }
                    }
                }
            }
        }
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.Tune, null, tint = MaterialTheme.colorScheme.primary)
                    Text("Automatic rated-track share", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    "Choose how many of every ten automatic picks come from rated tracks. Tracks above 1 stay eligible, but lower ratings are selected less often. The rest explore unrated tracks.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = share,
                    onValueChange = {
                        share = it.roundToInt().toFloat()
                        if (vm.isPersonalMode) vm.saveRatedTrackShare(share.roundToInt(), announce = false)
                    },
                    onValueChangeFinished = { vm.saveRatedTrackShare(share.roundToInt()) },
                    valueRange = 0f..10f,
                    steps = 9,
                    enabled = vm.isHost,
                )
                Text(
                    when (value) {
                        0 -> "All unrated"
                        10 -> "All rated"
                        else -> "$value rated · ${10 - value} unrated"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                if (!vm.isHost) {
                    Text("Only the host can change this setting.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (vm.settingsSaving) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
    }
    if (confirmPlexSignOut) {
        AlertDialog(
            onDismissRequest = { confirmPlexSignOut = false },
            title = { Text("Sign out of Plex?") },
            text = { Text("This removes the Plex account and source from this phone and clears its saved queue and playback history.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmPlexSignOut = false
                    vm.signOutPersonalPlex()
                }) { Text("Sign out", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmPlexSignOut = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable private fun PlexMusicSetup(vm: HarmonicastViewModel) {
    Box(Modifier.fillMaxSize().padding(28.dp), Alignment.Center) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!vm.needsPlexSetup) {
                Text("Plex source needs setup", style = MaterialTheme.typography.headlineSmall)
                Text("Sign in again with the Plex account that owns the server to finish setup.")
            } else if (!vm.isPlexSetupOwner) {
                Text("Plex setup is in progress", style = MaterialTheme.typography.headlineSmall)
                Text("The Plex account that began setup must choose the server and Music library before anyone else can use this installation.")
            } else {
                Text("Choose your Plex music", style = MaterialTheme.typography.headlineSmall)
                Text("Select a Plex server you own, then choose its Music library. Plex sharing controls guest access.")
                if (vm.loading) CircularProgressIndicator()
                if (vm.selectedPlexServer == null) {
                    vm.plexServers.forEach { server ->
                        OutlinedButton(onClick = { vm.choosePlexServer(server) }, modifier = Modifier.fillMaxWidth()) {
                            Text(server.name)
                        }
                    }
                    if (!vm.loading && vm.plexServers.isEmpty()) Text("No owned Plex servers were found.")
                } else {
                    Text("${vm.selectedPlexServer?.name} — choose a Music library", style = MaterialTheme.typography.titleSmall)
                    vm.plexLibraries.forEach { library ->
                        Button(onClick = { vm.selectPlexLibrary(library) }, modifier = Modifier.fillMaxWidth()) {
                            Text(library.title)
                        }
                    }
                }
            }
            if (vm.error.isNotBlank()) Text(vm.error, color = MaterialTheme.colorScheme.error)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun Now(vm: HarmonicastViewModel, onSearch: (String) -> Unit) {
    val song = vm.nowPlaying.song
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp)) {
        val artworkSize = minOf((maxWidth - 20.dp).coerceAtLeast(180.dp), maxHeight * 0.43f)
        val density = LocalDensity.current
        val throwDistance = with(density) { (maxWidth + artworkSize).toPx() }
        val skipThreshold = throwDistance * 0.35f
        var swipeOffset by remember(song?.id) { mutableFloatStateOf(0f) }
        var swipeStartedAt by remember(song?.id) { mutableLongStateOf(0L) }
        var detailsOpen by remember(song?.id) { mutableStateOf(false) }
        val animatedSwipeOffset by animateFloatAsState(
            targetValue = swipeOffset,
            animationSpec = spring(),
            label = "artwork swipe",
        )
        if (detailsOpen && song != null) {
            ArtistDiscoveryPage(vm, song) { detailsOpen = false }
        } else Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
        if (song != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .pointerInput(song.id) {
                        var upward = 0f
                        detectVerticalDragGestures(onVerticalDrag = { change, amount ->
                            if (amount < 0) { change.consume(); upward -= amount }
                        }, onDragEnd = {
                            if (upward > 90f) { detailsOpen = true; vm.loadArtistDiscovery(song) }
                            upward = 0f
                        })
                    }
                    .pointerInput(song.id, vm.isHost) {
                        detectHorizontalDragGestures(
                            onDragStart = { swipeOffset = 0f; swipeStartedAt = SystemClock.uptimeMillis() },
                            onHorizontalDrag = { change, amount ->
                                if (vm.isHost && amount < 0f) {
                                    change.consume()
                                    swipeOffset = (swipeOffset + amount).coerceAtLeast(-throwDistance * 0.8f)
                                }
                            },
                            onDragEnd = {
                                // A deliberate flick should feel immediate even when it
                                // travels less than the long-drag distance.
                                val fastFlick = swipeOffset < -72f && SystemClock.uptimeMillis() - swipeStartedAt < 180L
                                if (swipeOffset <= -skipThreshold || fastFlick) vm.nextSong()
                                // A swipe is a command, not a card-dismissal UI.
                                // The art returns until the server publishes the next song.
                                swipeOffset = 0f
                            },
                            onDragCancel = { swipeOffset = 0f },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Cover(
                    vm,
                    song,
                    artworkSize,
                    Modifier.graphicsLayer {
                        translationX = animatedSwipeOffset
                        rotationZ = (animatedSwipeOffset / throwDistance * 14f).coerceAtLeast(-14f)
                        alpha = (1f + animatedSwipeOffset / throwDistance * 0.55f).coerceIn(0.45f, 1f)
                    },
                )
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    song.artist,
                    modifier = Modifier.clickable { onSearch(song.artist) }.padding(vertical = 1.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(song.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (song.album.isNotBlank()) {
                    val albumLabel = song.year?.let { "${song.album} ($it)" } ?: song.album
                    Text(
                        albumLabel,
                        modifier = Modifier.clickable { onSearch(song.album) }.padding(vertical = 1.dp),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val rating = song.rating ?: 0.0
                val filledStars = (rating / 2).toInt().coerceIn(0, 5)
                Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    repeat(5) { index -> Icon(if (index < filledStars) Icons.Default.Star else Icons.Outlined.StarBorder, if (index == 0) "Plex rating $rating out of 10" else null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(23.dp)) }
                }
            }

        } else {
            Spacer(Modifier.height(100.dp))
            Text("Nothing is playing")
            if (vm.isHost) {
                Button({ vm.claim() }, enabled = !vm.isActivePlayer) { Text("Take control on this device") }
                Button({ vm.startRandomPlayback() }, enabled = vm.isActivePlayer) { Text("Play random music") }
            }
        }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PhonePlayerControls(vm: HarmonicastViewModel) {
    val song = vm.nowPlaying.song ?: return
    var scrubPosition by remember(song.id) { mutableFloatStateOf(vm.playbackPosition) }
    var isScrubbing by remember(song.id) { mutableStateOf(false) }
    val duration = song.duration.toFloat().coerceAtLeast(0f)
    LaunchedEffect(song.id, vm.playbackPosition, isScrubbing) {
        if (!isScrubbing) scrubPosition = vm.playbackPosition.coerceIn(0f, duration)
    }
    LaunchedEffect(song.id, vm.nowPlaying.isPlaying, duration, isScrubbing) {
        while (vm.nowPlaying.isPlaying && duration > 0f && !isScrubbing) {
            delay(500)
            scrubPosition = (scrubPosition + 0.5f).coerceAtMost(duration)
        }
    }

    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.fillMaxWidth()) {
                if (duration > 0f) {
                    Slider(
                        value = scrubPosition.coerceIn(0f, duration),
                        onValueChange = {
                            isScrubbing = true
                            scrubPosition = it
                        },
                        onValueChangeFinished = {
                            vm.seekTo(scrubPosition)
                            isScrubbing = false
                        },
                        valueRange = 0f..duration,
                        enabled = vm.isActivePlayer,
                        modifier = Modifier.height(24.dp),
                        thumb = {},
                        track = { sliderState ->
                            SliderDefaults.Track(
                                sliderState = sliderState,
                                modifier = Modifier.height(3.dp),
                                enabled = vm.isActivePlayer,
                                drawStopIndicator = null,
                                thumbTrackGapSize = 0.dp,
                                trackInsideCornerSize = 0.dp,
                            )
                        },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatDuration(scrubPosition.toInt()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatDuration(duration.toInt()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatDuration(vm.playbackPosition.toInt()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Loading duration…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { vm.vote(false) }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.ThumbDown, "Vote down")
                }
                IconButton(onClick = { vm.previousSong() }, enabled = vm.isActivePlayer, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.SkipPrevious, "Previous track or restart", modifier = Modifier.size(32.dp))
                }
                FilledIconButton(onClick = { vm.toggle() }, enabled = vm.isActivePlayer, modifier = Modifier.size(64.dp)) {
                    Icon(if (vm.nowPlaying.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (vm.nowPlaying.isPlaying) "Pause" else "Play", modifier = Modifier.size(36.dp))
                }
                IconButton(onClick = { vm.nextSong() }, enabled = vm.isHost, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.SkipNext, "Next", modifier = Modifier.size(32.dp))
                }
                IconButton(onClick = { vm.vote(true) }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.ThumbUp, "Vote up")
                }
            }
            if (vm.isHost && !vm.isActivePlayer) Button({ vm.claim() }) { Text("Take control on this device") }
        }
    }
}

@Composable private fun ArtistDiscoveryPage(vm: HarmonicastViewModel, song: Song, close: () -> Unit) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().pointerInput(song.id, listState) {
            awaitPointerEventScope {
                while (true) {
                    awaitFirstDown(requireUnconsumed = false)
                    var downward = 0f
                    var active = true
                    while (active) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: break
                        val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                        downward = if (atTop) {
                            downward + change.positionChange().y.coerceAtLeast(0f)
                        } else {
                            0f
                        }
                        if (downward > 90f) { close(); active = false }
                        if (!change.pressed) active = false
                    }
                }
            }
        },
    ) {
        item {
            Column(Modifier.padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Artist discovery", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    TextButton(onClick = close) { Text("Down") }
                }
                when {
                    vm.artistDiscoveryLoading -> CircularProgressIndicator()
                    vm.artistDiscoveryError.isNotBlank() -> Text(vm.artistDiscoveryError, color = MaterialTheme.colorScheme.error)
                    vm.artistDiscovery != null -> {
                        val info = vm.artistDiscovery!!
                        Text(info.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        if (info.genres.isNotEmpty()) DetailLine("Genres", info.genres.joinToString(" · "))
                        if (info.albumName.isNotBlank()) {
                            Text("Album context", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${info.albumName}${info.albumYear?.let { " ($it)" }.orEmpty()}",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (info.albumSummary.isNotBlank()) Text(info.albumSummary, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Justify)
                        }
                        if (info.bio.isNotBlank()) Text(
                            info.bio,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Justify,
                        )
                        if (info.similarArtists.isNotEmpty()) {
                            Text("Similar artists", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(info.similarArtists.joinToString(" · "), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

private fun formatDuration(totalSeconds: Int): String {
    val seconds = totalSeconds.coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private fun bluetoothPermissions(advertise: Boolean): Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && advertise -> arrayOf(
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
    )
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )
    else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}

@Composable private fun RoomQrCode(value: String, description: String) {
    val bitmap = remember(value) {
        val matrix = QRCodeWriter().encode(
            value,
            BarcodeFormat.QR_CODE,
            640,
            640,
            mapOf(EncodeHintType.MARGIN to 1),
        )
        val pixels = IntArray(matrix.width * matrix.height) { index ->
            if (matrix[index % matrix.width, index / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
        Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565).apply {
            setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
        }.asImageBitmap()
    }
    Surface(color = Color.White, shape = RoundedCornerShape(14.dp)) {
        Image(bitmap, description, Modifier.fillMaxWidth().aspectRatio(1f).padding(10.dp))
    }
}

@Composable private fun Queue(vm: HarmonicastViewModel) {
    var confirmClear by remember { mutableStateOf(false) }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear queue?") },
            text = { Text("This removes every upcoming track from the shared queue. The current song will keep playing.") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; vm.clearQueue() }) { Text("Clear queue") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Queue", style = MaterialTheme.typography.headlineMedium)
                if (vm.isHost && vm.queue.isNotEmpty()) {
                    TextButton(onClick = { confirmClear = true }) {
                        Icon(Icons.Default.DeleteSweep, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Clear")
                    }
                }
            }
        }
        items(vm.queue, key = { it.id }) { SongRow(vm, it, false) }
    }
}

@Composable private fun Search(vm: HarmonicastViewModel) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(vm.query, { vm.query = it }, label = { Text("Search music") }, singleLine = true, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.search() }) { Icon(Icons.Default.Search, "Search") }
        }
        LazyColumn {
            items(vm.results, key = { it.id }) { SongRow(vm, it, true) }
            if (vm.searchLoading) {
                item { Box(Modifier.fillMaxWidth().padding(28.dp), Alignment.Center) { CircularProgressIndicator() } }
            } else if (vm.query.isNotBlank() && vm.results.isEmpty()) {
                item {
                    ElevatedCard(
                        Modifier.fillMaxWidth().padding(16.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = Color(0xff281e32)),
                    ) {
                        Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = RoundedCornerShape(16.dp), color = Color(0xffd0a2ff)) {
                                Icon(Icons.Default.LibraryMusic, null, Modifier.padding(11.dp), tint = Color(0xff2a1737))
                            }
                            Column {
                                Text("Not in your library", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text("Try a connected music source to add it to the jukebox.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            val source = vm.musicSourceExtension
            if (source != null && vm.results.isEmpty()) {
                item {
                    ConnectedSourceAction(
                        title = "Search connected music sources",
                        subtitle = "Find a verified recording, then request it",
                        icon = Icons.Default.TravelExplore,
                        onClick = { vm.openMusicSource("search") },
                    )
                }
            }
            if (source != null && vm.libraryArtistBrowse != null) {
                item {
                    ConnectedSourceAction(
                        title = "Find songs by ${vm.libraryArtistBrowse?.name}",
                        subtitle = "Browse releases beyond your local library",
                        icon = Icons.Default.PersonSearch,
                        onClick = { vm.openMusicSource("artist") },
                    )
                }
            }
        }
    }
}

@Composable private fun ConnectedSourceAction(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clickable(onClick = onClick),
        colors = CardDefaults.elevatedCardColors(containerColor = Color(0xff362047)),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(16.dp), color = Color(0xffd0a2ff)) {
                Icon(icon, null, Modifier.padding(11.dp), tint = Color(0xff2a1737))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color(0xffeadcff), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, "Open connected music sources", tint = Color(0xffeadcff))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun MusicSourceSheet(vm: HarmonicastViewModel) {
    val dialog = vm.musicSourceDialog ?: return
    ModalBottomSheet(onDismissRequest = { if (!vm.musicSourceLoading) vm.closeMusicSource() }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(14.dp), color = Color(0xffd0a2ff)) {
                        Icon(if (vm.musicSourceViewingTracks) Icons.AutoMirrored.Filled.QueueMusic else Icons.Default.TravelExplore, null, Modifier.padding(9.dp), tint = Color(0xff2a1737))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            when {
                                vm.musicSourceViewingTracks -> "Choose a track"
                                vm.musicSourceArtist != null -> vm.musicSourceArtist?.name ?: "Choose an album"
                                else -> "Connected music sources"
                            },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(if (vm.musicSourceViewingTracks) "Pick one recording to request" else dialog.displayName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TextButton(onClick = { vm.backMusicSource() }, enabled = !vm.musicSourceLoading) {
                    Text(if (vm.musicSourceViewingTracks) "Back" else "Close")
                }
            }
            if (vm.musicSourceMessage.isNotBlank()) {
                Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), shape = RoundedCornerShape(12.dp), color = if (vm.musicSourceMessage.contains("failed", true) || vm.musicSourceMessage.contains("could not", true)) MaterialTheme.colorScheme.errorContainer else Color(0xff30243a)) {
                    Text(vm.musicSourceMessage, Modifier.padding(12.dp), color = if (vm.musicSourceMessage.contains("failed", true) || vm.musicSourceMessage.contains("could not", true)) MaterialTheme.colorScheme.onErrorContainer else Color(0xffeadcff))
                }
            }
            when {
                vm.musicSourceLoading && vm.musicSourceAlbums.isEmpty() && vm.musicSourceRecordings.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                vm.musicSourceArtist != null && !vm.musicSourceViewingTracks -> MusicSourceAlbumList(vm)
                vm.musicSourceRecordings.isNotEmpty() -> MusicSourceRecordingList(vm)
                !vm.musicSourceLoading -> Box(Modifier.fillMaxSize().padding(28.dp), Alignment.Center) {
                    Text(if (vm.musicSourceMessage.isNotBlank()) "Try again or close this search." else "No matching tracks were found.", textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable private fun MusicSourceRecordingList(vm: HarmonicastViewModel) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(vm.musicSourceRecordings, key = { it.id }) { recording ->
            ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp), colors = CardDefaults.elevatedCardColors(containerColor = Color(0xff242029))) {
                Row(Modifier.padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(12.dp), color = Color(0xff453259)) {
                        Icon(Icons.Default.MusicNote, null, Modifier.padding(9.dp), tint = Color(0xffeadcff))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(recording.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val details = listOfNotNull(recording.artist, recording.album, recording.year, recording.durationMs?.let { formatDuration((it / 1000).toInt()) }, recording.disambiguation)
                        Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FilledIconButton(onClick = { vm.requestMusicSourceRecording(recording) }, enabled = !vm.musicSourceLoading) {
                        Icon(Icons.Default.Add, "Request this song")
                    }
                }
            }
        }
    }
}

@Composable private fun MusicSourceAlbumList(vm: HarmonicastViewModel) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(vm.musicSourceAlbums, key = { it.id }) { album ->
            ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp).clickable(enabled = !vm.musicSourceLoading) { vm.openMusicSourceAlbum(album) }, colors = CardDefaults.elevatedCardColors(containerColor = Color(0xff242029))) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(12.dp), color = Color(0xff453259)) {
                        Icon(Icons.Default.Album, null, Modifier.padding(10.dp), tint = Color(0xffeadcff))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(album.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(album.type, album.year).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronRight, "Browse album", tint = Color(0xffd0a2ff))
                }
            }
        }
        if (vm.musicSourceHasMoreAlbums) {
            item {
                TextButton(onClick = { vm.loadMoreMusicSourceAlbums() }, enabled = !vm.musicSourceLoading, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("Load more albums")
                }
            }
        }
        if (vm.musicSourceAlbums.isEmpty() && !vm.musicSourceLoading) {
            item { Text("No albums were found.", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable private fun SongRow(vm: HarmonicastViewModel, song: Song, add: Boolean) {
    ListItem(
        modifier = Modifier.clickable(enabled = !add && vm.isActivePlayer) { vm.playQueued(song) },
        headlineContent = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text("${song.artist}${if (song.addedByEmail.isNotBlank()) " · ${song.addedByEmail}" else ""}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Cover(vm, song, 48.dp) },
        trailingContent = {
            if (add) IconButton(onClick = { vm.add(song) }) { Icon(Icons.Default.Add, "Add to queue") }
            else if (vm.isHost) IconButton(onClick = { vm.remove(song) }) { Icon(Icons.Default.Delete, "Remove from queue") }
        }
    )
    HorizontalDivider()
}

@Composable private fun Cover(vm: HarmonicastViewModel, song: Song, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val url = vm.artworkUrl(song)
    val shape = RoundedCornerShape(if (size >= 180.dp) 24.dp else 12.dp)
    val coverModifier = Modifier.size(size).clip(shape).then(modifier)
    if (url == null) Icon(Icons.Default.Album, null, coverModifier) else AsyncImage(url, null, coverModifier, contentScale = ContentScale.Crop)
}
