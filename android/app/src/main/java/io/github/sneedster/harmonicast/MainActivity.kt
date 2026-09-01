package io.github.sneedster.harmonicast

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.common.MediaItem
import coil.compose.AsyncImage
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

// Playback control is delegated to HarmonicastMediaService via MediaController.
// The ViewModel only reads state for display and forwards user commands.
class HarmonicastViewModel : ViewModel() {
    private lateinit var context: Context
    private lateinit var api: Api
    private var socket: okhttp3.WebSocket? = null
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
    var configured by mutableStateOf(true)
    var needsPlexSetup by mutableStateOf(false); private set
    var isPlexSetupOwner by mutableStateOf(false); private set
    var plexServers by mutableStateOf<List<PlexServer>>(emptyList()); private set
    var plexLibraries by mutableStateOf<List<PlexLibrary>>(emptyList()); private set
    var selectedPlexServer by mutableStateOf<PlexServer?>(null); private set
    var plexSourceLabel by mutableStateOf(""); private set
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

    fun initialize(appContext: Context) {
        if (::api.isInitialized) return
        context = appContext
        api = Api(context.getSharedPreferences("harmonicast", Context.MODE_PRIVATE))
        savedBaseUrl = api.base

        // Start our own MediaLibraryService while the activity is visible.
        // MediaController normally binds a service token itself, but on recent
        // Pixels that bind can remain pending when the service has never been
        // started. A live service gives the controller a session immediately.
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
        
        ready = api.base.isNotBlank() && api.token.isNotBlank()
        if (ready) {
            refresh()
        }
    }

    fun serverUrl() = savedBaseUrl
    fun mediaToken() = if (::api.isInitialized) api.token else ""
    fun setServer(url: String) { api.setBase(url); savedBaseUrl = api.base; error = "" }
    fun authUrl(): String = "${api.base}/api/auth/plex?mobile_redirect=harmonicast%3A%2F%2Fauth"
    
    fun receiveAuth(uri: Uri) {
        android.util.Log.d("HarmonicastAuth", "receiveAuth called with: $uri")
        val token = uri.fragment?.split("&")?.firstOrNull { it.startsWith("auth_token=") }?.removePrefix("auth_token=") 
            ?: uri.getQueryParameter("auth_token") 
            ?: uri.getQueryParameter("token")
        
        if (token.isNullOrBlank()) {
            error = "Sign-in did not return a token"
            return
        }
        api.setToken(token)
        ready = true
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            loading = true
            try {
                val connection = JSONObject(api.json("connection"))
                configured = connection.optBoolean("configured")
                isHost = connection.optBoolean("isHost")
                isActivePlayer = connection.optBoolean("isActivePlayer")
                needsPlexSetup = connection.optBoolean("needsPlexSetup")
                isPlexSetupOwner = connection.optBoolean("isSetupOwner")
                if (configured) {
                    queue = songs(JSONArray(api.json("queue")))
                    val npJson = api.json("now-playing")
                    val np = JSONObject(npJson)
                    nowPlaying = NowPlaying(np.optJSONObject("song")?.let(::song), np.optBoolean("isPlaying"))
                    playbackPosition = np.optDouble("playbackPosition", 0.0).toFloat().coerceAtLeast(0f)
                    ensureSocket()
                    if (isHost) loadPlexSource()
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
        socket = api.websocket(
            onMessage = { refresh() },
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
        action("setup/plex/select", "POST", JSONObject()
            .put("machineIdentifier", server.machineIdentifier)
            .put("libraryKey", library.key)) { refresh() }
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
                val localResults = songs(JSONArray(api.json("search?q=" + URLEncoder.encode(term, "UTF-8"))))
                val artist = JSONObject(api.json("search/artist?q=" + URLEncoder.encode(term, "UTF-8")))
                    .optJSONObject("artist")?.let { item ->
                        LibraryArtistBrowse(item.optString("name", term), songs(item.optJSONArray("songs") ?: JSONArray()))
                    }
                if (generation != searchGeneration) return@launch
                results = localResults
                libraryArtistBrowse = artist
                if (localResults.isEmpty() || artist != null) {
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

    fun add(song: Song) = action("queue", "POST", JSONObject().put("song", songJson(song))) { refresh() }
    fun remove(song: Song) = action("queue/${URLEncoder.encode(song.id, "UTF-8")}", "DELETE", JSONObject()) { refresh() }
    fun vote(up: Boolean) = action("vote", "POST", JSONObject().put("vote", if (up) "up" else "down"))
    fun claim() = action("player/claim", "POST", JSONObject()) { refresh() }
    fun clearQueue() = action("queue", "DELETE", JSONObject()) { refresh() }
    fun queueSimilar() {
        viewModelScope.launch {
            try {
                val added = JSONObject(api.json("queue/similar", "POST", JSONObject())).optInt("added", 0)
                if (added > 0) showTemporaryNotice("Queued $added Track Radio songs")
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
                api.json("jukebox", "POST", JSONObject().put("enabled", true))
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

    fun nextSong() = action("player/skip", "POST", JSONObject())
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
                val item = JSONObject(api.json("plex/tracks/${URLEncoder.encode(song.id, "UTF-8")}/discovery"))
                fun strings(key: String) = item.optJSONArray(key)?.let { a -> List(a.length()) { a.optString(it) }.filter { it.isNotBlank() } } ?: emptyList()
                val album = item.optJSONObject("album")
                artistDiscovery = ArtistDiscovery(
                    item.optString("name", song.artist), item.optString("bio"), strings("genres"), strings("similarArtists"),
                    album?.optString("name").orEmpty(),
                    album?.takeIf { it.has("year") && !it.isNull("year") }?.optInt("year"),
                    album?.optString("summary").orEmpty(),
                )
            } catch (e: Exception) {
                artistDiscoveryError = e.message ?: "Could not load artist discovery"
            } finally {
                artistDiscoveryLoading = false
            }
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
        socket?.close(1000, null)
        controller?.let { MediaController.releaseFuture(com.google.common.util.concurrent.Futures.immediateFuture(it)) }
    }

    private fun songs(a: JSONArray) = List(a.length()) { song(a.getJSONObject(it)) }
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
    private fun song(o: JSONObject) = Song(
        o.optString("id"), o.optString("title"), o.optString("artist"), o.optString("album"),
        o.optInt("duration"), o.optString("coverArt"),
        if (o.has("rating") && !o.isNull("rating")) o.optInt("rating").coerceIn(0, 10) else null,
        o.optString("addedByEmail"), o.optBoolean("isManual", true),
    )
    private fun songJson(s: Song) = JSONObject().put("id", s.id).put("title", s.title).put("artist", s.artist).put("album", s.album).put("duration", s.duration).put("coverArt", s.coverArt)
}

data class MusicSourceDialog(val id: String, val displayName: String, val mode: String, val query: String, val requestId: String? = null)

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
        if (!vm.ready) Login(vm) else Home(vm)
    }
}

@Composable private fun Login(vm: HarmonicastViewModel) {
    var server by remember(vm.savedBaseUrl) { mutableStateOf(vm.serverUrl()) }
    val context = LocalContext.current
    Box(Modifier.fillMaxSize().padding(28.dp), Alignment.Center) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Harmonicast", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text("Connect to your self-hosted jukebox.")
            OutlinedTextField(server, { server = it }, label = { Text("Server URL") }, placeholder = { Text("https://harmonicast.example.com") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { vm.setServer(server); CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(vm.authUrl())) }, enabled = server.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Filled.Login, null)
                Spacer(Modifier.width(8.dp))
                Text("Sign in with Plex")
            }
            if (vm.error.isNotBlank()) Text(vm.error, color = MaterialTheme.colorScheme.error)
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
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(Modifier.height(56.dp)) {
                val items = listOf("Now playing" to Icons.Default.MusicNote, "Queue" to Icons.AutoMirrored.Filled.QueueMusic, "Search" to Icons.Default.Search)
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
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> Now(vm) { term -> vm.query = term; vm.search(); tab = 2 }
                1 -> Queue(vm)
                else -> Search(vm)
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

@Composable private fun Now(vm: HarmonicastViewModel, onSearch: (String) -> Unit) {
    val song = vm.nowPlaying.song
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
        val artworkSize = minOf((maxWidth - 12.dp).coerceAtLeast(180.dp), maxHeight * 0.5f)
        val density = LocalDensity.current
        val throwDistance = with(density) { (maxWidth + artworkSize).toPx() }
        val skipThreshold = throwDistance * 0.35f
        var swipeOffset by remember(song?.id) { mutableFloatStateOf(0f) }
        var swipeStartedAt by remember(song?.id) { mutableLongStateOf(0L) }
        var throwingSongAway by remember(song?.id) { mutableStateOf(false) }
        var detailsOpen by remember(song?.id) { mutableStateOf(false) }
        var scrubPosition by remember(song?.id) { mutableFloatStateOf(vm.playbackPosition) }
        var isScrubbing by remember(song?.id) { mutableStateOf(false) }
        val duration = song?.duration?.toFloat()?.coerceAtLeast(0f) ?: 0f
        LaunchedEffect(song?.id, vm.playbackPosition, isScrubbing) {
            if (!isScrubbing) scrubPosition = vm.playbackPosition.coerceIn(0f, duration)
        }
        LaunchedEffect(song?.id, vm.nowPlaying.isPlaying, duration, isScrubbing) {
            while (vm.nowPlaying.isPlaying && duration > 0f && !isScrubbing) {
                delay(500)
                scrubPosition = (scrubPosition + 0.5f).coerceAtMost(duration)
            }
        }
        val animatedSwipeOffset by animateFloatAsState(
            targetValue = if (throwingSongAway) -throwDistance else swipeOffset,
            animationSpec = if (throwingSongAway) {
                tween(durationMillis = 220, easing = FastOutSlowInEasing)
            } else {
                spring()
            },
            label = "artwork throw",
            finishedListener = {
                if (throwingSongAway) {
                    // Keep the old art off-screen until the next queue update
                    // replaces it, rather than springing it back into view.
                    swipeOffset = -throwDistance
                    throwingSongAway = false
                    vm.nextSong()
                }
            },
        )
        if (detailsOpen && song != null) {
            ArtistDiscoveryPage(vm, song) { detailsOpen = false }
        } else Column(
            Modifier.fillMaxSize().pointerInput(song?.id) {
                var upward = 0f
                detectVerticalDragGestures(onVerticalDrag = { change, amount ->
                    if (amount < 0) { change.consume(); upward -= amount }
                }, onDragEnd = {
                    if (upward > 90f && song != null) { detailsOpen = true; vm.loadArtistDiscovery(song) }
                    upward = 0f
                })
            },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
        if (song != null) {
            Box(
                Modifier
                    .fillMaxWidth()
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
                                if (swipeOffset <= -skipThreshold || fastFlick) throwingSongAway = true
                                else swipeOffset = 0f
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
            Column(Modifier.fillMaxWidth()) {
                Text("Playback", style = MaterialTheme.typography.labelMedium)
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
            TextButton(onClick = { onSearch(song.artist) }) { Text(song.artist, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Text(song.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (song.album.isNotBlank()) TextButton(onClick = { onSearch(song.album) }) { Text(song.album, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            val rating = song.rating ?: 0
            val filledStars = (rating / 2).coerceIn(0, 5)
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(5) { index -> Icon(if (index < filledStars) Icons.Default.Star else Icons.Outlined.StarBorder, if (index == 0) "Plex rating $rating out of 10" else null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp)) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.vote(false) }, modifier = Modifier.size(52.dp)) { Icon(Icons.Default.ThumbDown, "Vote down") }
                FilledIconButton(onClick = { vm.toggle() }, enabled = vm.isActivePlayer, modifier = Modifier.size(64.dp)) {
                    Icon(if (vm.nowPlaying.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause")
                }
                IconButton(onClick = { vm.nextSong() }, enabled = vm.isHost, modifier = Modifier.size(52.dp)) { Icon(Icons.Default.SkipNext, "Next") }
                IconButton(onClick = { vm.vote(true) }, modifier = Modifier.size(52.dp)) { Icon(Icons.Default.ThumbUp, "Vote up") }
            }
            if (vm.isHost) {
                OutlinedButton(onClick = { vm.queueSimilar() }, enabled = vm.isActivePlayer) {
                    Icon(Icons.Default.AutoAwesome, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Queue Track Radio")
                }
            }
            if (vm.isHost && !vm.isActivePlayer) Button({ vm.claim() }) { Text("Take control on this device") }

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
    val url = if (song.coverArt.isBlank()) null else "${vm.serverUrl()}/api/cover-art/${URLEncoder.encode(song.coverArt, "UTF-8")}?size=300&token=${URLEncoder.encode(vm.mediaToken(), "UTF-8")}"
    val shape = RoundedCornerShape(if (size >= 180.dp) 24.dp else 12.dp)
    val coverModifier = Modifier.size(size).clip(shape).then(modifier)
    if (url == null) Icon(Icons.Default.Album, null, coverModifier) else AsyncImage(url, null, coverModifier, contentScale = ContentScale.Crop)
}
