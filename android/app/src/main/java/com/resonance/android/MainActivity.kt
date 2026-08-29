package com.resonance.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

// Playback control is delegated to ResonanceMediaService via MediaController.
// The ViewModel only reads state for display and forwards user commands.
class ResonanceViewModel : ViewModel() {
    private lateinit var context: Context
    private lateinit var api: Api
    private var socket: okhttp3.WebSocket? = null
    var ready by mutableStateOf(false); private set
    var loading by mutableStateOf(false); var error by mutableStateOf("")
    var queue by mutableStateOf<List<Song>>(emptyList()); var nowPlaying by mutableStateOf(NowPlaying())
    var isHost by mutableStateOf(false); var isActivePlayer by mutableStateOf(false)
    var configured by mutableStateOf(true)
    var needsPlexSetup by mutableStateOf(false); private set
    var isPlexSetupOwner by mutableStateOf(false); private set
    var plexServers by mutableStateOf<List<PlexServer>>(emptyList()); private set
    var plexLibraries by mutableStateOf<List<PlexLibrary>>(emptyList()); private set
    var selectedPlexServer by mutableStateOf<PlexServer?>(null); private set
    var plexSourceLabel by mutableStateOf(""); private set
    var results by mutableStateOf<List<Song>>(emptyList()); var query by mutableStateOf("")
    var controller by mutableStateOf<MediaController?>(null); private set
    var savedBaseUrl by mutableStateOf("")

    fun initialize(appContext: Context) {
        if (::api.isInitialized) return
        context = appContext
        api = Api(context.getSharedPreferences("resonance", Context.MODE_PRIVATE))
        savedBaseUrl = api.base
        
        val sessionToken = SessionToken(context, ComponentName(context, ResonanceMediaService::class.java))
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture.addListener({
            controller = controllerFuture.get()
        }, MoreExecutors.directExecutor())
        
        ready = api.base.isNotBlank() && api.token.isNotBlank()
        if (ready) {
            refresh()
        }
    }

    fun serverUrl() = savedBaseUrl
    fun mediaToken() = if (::api.isInitialized) api.token else ""
    fun setServer(url: String) { api.setBase(url); savedBaseUrl = api.base; error = "" }
    fun authUrl(): String = "${api.base}/api/auth/plex?mobile_redirect=resonance%3A%2F%2Fauth"
    
    fun receiveAuth(uri: Uri) {
        android.util.Log.d("ResonanceAuth", "receiveAuth called with: $uri")
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
                    socket?.close(1000, null)
                    socket = api.websocket { refresh() }
                    if (isHost) loadPlexSource()
                } else if (needsPlexSetup && isPlexSetupOwner) {
                    loadPlexServers()
                }
            } catch (e: Exception) {
                error = e.message ?: "Could not connect"
                if ((e.message ?: "").contains("Authentication required")) ready = false
            } finally {
                loading = false
            }
        }
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
            try {
                results = songs(JSONArray(api.json("search?q=" + URLEncoder.encode(query, "UTF-8"))))
            } catch (e: Exception) {
                error = e.message ?: "Search failed"
            }
        }
    }

    fun add(song: Song) = action("queue", "POST", JSONObject().put("song", songJson(song))) { refresh() }
    fun vote(up: Boolean) = action("vote", "POST", JSONObject().put("vote", if (up) "up" else "down"))
    fun claim() = action("player/claim", "POST", JSONObject()) { refresh() }

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

    fun nextSong() { controller?.seekToNext() }

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
        socket?.close(1000, null)
        controller?.let { MediaController.releaseFuture(com.google.common.util.concurrent.Futures.immediateFuture(it)) }
    }

    private fun songs(a: JSONArray) = List(a.length()) { song(a.getJSONObject(it)) }
    private fun song(o: JSONObject) = Song(o.optString("id"), o.optString("title"), o.optString("artist"), o.optString("album"), o.optInt("duration"), o.optString("coverArt"), o.optString("addedByEmail"), o.optBoolean("isManual", true))
    private fun songJson(s: Song) = JSONObject().put("id", s.id).put("title", s.title).put("artist", s.artist).put("album", s.album).put("duration", s.duration).put("coverArt", s.coverArt)
}

class MainActivity : ComponentActivity() {
    private var authUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authUri = intent?.data
        setContent {
            val vm: ResonanceViewModel = viewModel()
            LaunchedEffect(Unit) { vm.initialize(applicationContext) }
            LaunchedEffect(authUri) { authUri?.let(vm::receiveAuth) }
            ResonanceApp(vm)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        authUri = intent.data
    }
}

@Composable private fun ResonanceApp(vm: ResonanceViewModel) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xffd0a2ff))) {
        if (!vm.ready) Login(vm) else Home(vm)
    }
}

@Composable private fun Login(vm: ResonanceViewModel) {
    var server by remember(vm.savedBaseUrl) { mutableStateOf(vm.serverUrl()) }
    val context = LocalContext.current
    Box(Modifier.fillMaxSize().padding(28.dp), Alignment.Center) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Resonance", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text("Connect to your self-hosted jukebox.")
            OutlinedTextField(server, { server = it }, label = { Text("Server URL") }, placeholder = { Text("https://resonance.example.com") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { vm.setServer(server); CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(vm.authUrl())) }, enabled = server.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Login, null)
                Spacer(Modifier.width(8.dp))
                Text("Sign in with Plex")
            }
            if (vm.error.isNotBlank()) Text(vm.error, color = MaterialTheme.colorScheme.error)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun Home(vm: ResonanceViewModel) {
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
                        Text("Resonance")
                        if (vm.plexSourceLabel.isNotBlank()) {
                            Text(vm.plexSourceLabel, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                actions = {
                    if (vm.loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                val items = listOf("Now playing" to Icons.Default.MusicNote, "Queue" to Icons.AutoMirrored.Filled.QueueMusic, "Search" to Icons.Default.Search)
                items.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Icon(item.second, null) },
                        label = { Text(item.first) }
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> Now(vm)
                1 -> Queue(vm)
                else -> Search(vm)
            }
            if (vm.error.isNotBlank()) {
                Snackbar(modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp), action = {}) {
                    Text(vm.error)
                }
            }
        }
    }
}

@Composable private fun PlexMusicSetup(vm: ResonanceViewModel) {
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

@Composable private fun Now(vm: ResonanceViewModel) {
    val song = vm.nowPlaying.song
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (song != null) {
            Cover(vm, song, 260.dp)
            Text(song.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("${song.artist} · ${song.album}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                IconButton(onClick = { vm.vote(false) }) { Icon(Icons.Default.ThumbDown, "Vote down") }
                FilledIconButton(onClick = { vm.toggle() }, enabled = vm.isActivePlayer) {
                    Icon(if (vm.nowPlaying.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause")
                }
                IconButton(onClick = { vm.nextSong() }, enabled = vm.isActivePlayer) { Icon(Icons.Default.SkipNext, "Next") }
            }
            if (vm.isHost && !vm.isActivePlayer) Button({ vm.claim() }) { Text("Play on this device") }
        } else {
            Spacer(Modifier.height(100.dp))
            Text("Nothing is playing")
            if (vm.isHost) {
                Button({ vm.claim() }, enabled = !vm.isActivePlayer) { Text("Claim this device as player") }
                Button({ vm.startRandomPlayback() }, enabled = vm.isActivePlayer) { Text("Play random music") }
            }
        }
    }
}

@Composable private fun Queue(vm: ResonanceViewModel) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { Text("Queue", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(20.dp)) }
        items(vm.queue, key = { it.id }) { SongRow(vm, it, false) }
    }
}

@Composable private fun Search(vm: ResonanceViewModel) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(vm.query, { vm.query = it }, label = { Text("Search music") }, singleLine = true, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.search() }) { Icon(Icons.Default.Search, "Search") }
        }
        LazyColumn {
            items(vm.results, key = { it.id }) { SongRow(vm, it, true) }
        }
    }
}

@Composable private fun SongRow(vm: ResonanceViewModel, song: Song, add: Boolean) {
    ListItem(
        headlineContent = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text("${song.artist}${if (song.addedByEmail.isNotBlank()) " · ${song.addedByEmail}" else ""}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Cover(vm, song, 48.dp) },
        trailingContent = { if (add) IconButton(onClick = { vm.add(song) }) { Icon(Icons.Default.Add, "Add to queue") } }
    )
    HorizontalDivider()
}

@Composable private fun Cover(vm: ResonanceViewModel, song: Song, size: androidx.compose.ui.unit.Dp) {
    val url = if (song.coverArt.isBlank()) null else "${vm.serverUrl()}/api/cover-art/${URLEncoder.encode(song.coverArt, "UTF-8")}?size=300&token=${URLEncoder.encode(vm.mediaToken(), "UTF-8")}"
    if (url == null) Icon(Icons.Default.Album, null, Modifier.size(size)) else AsyncImage(url, null, Modifier.size(size))
}
