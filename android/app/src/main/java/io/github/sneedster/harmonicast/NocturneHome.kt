package io.github.sneedster.harmonicast

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private class CollectionState {
    var entries by mutableStateOf(emptyList<LibraryEntry>())
    var offset by mutableStateOf<Int?>(0)
    var loading by mutableStateOf(false)
    var failed by mutableStateOf(false)
}
private val LocalBrowsePages = staticCompositionLocalOf<MutableMap<String, CollectionState>> { error("Missing browse scope") }

@Composable internal fun NocturneHome(vm: HarmonicastViewModel) {
    val configuration = LocalConfiguration.current
    val wide = configuration.screenWidthDp >= 840
    val useRail = wide || configuration.screenWidthDp > configuration.screenHeightDp
    val compactLandscape = useRail && configuration.screenHeightDp < 500
    val colors = MaterialTheme.colorScheme
    var destination by rememberSaveable { mutableStateOf("Home") }
    var auxiliaryReturn by rememberSaveable { mutableStateOf("Home") }
    var roomsFromSettings by rememberSaveable { mutableStateOf(false) }
    var settingsSession by rememberSaveable { mutableIntStateOf(0) }
    // Source changes dispose collection data, including authenticated art URLs.
    val library = vm.browseLibrary
    key(library) {
        val pages = remember { mutableMapOf<String, CollectionState>() }
        CompositionLocalProvider(LocalBrowsePages provides pages) {
        val stack = remember { mutableStateListOf<LibraryEntry>() }
        val stateHolder = rememberSaveableStateHolder()
        fun navigate(name: String) { stack.clear(); destination = name }
        fun closeAuxiliary() {
            if (destination == "Rooms" && roomsFromSettings) {
                destination = "Settings"
                roomsFromSettings = false
            } else destination = auxiliaryReturn
        }
        fun openSettings() {
            if (destination == "Settings") return
            if (destination !in listOf("Settings", "Rooms")) auxiliaryReturn = destination
            settingsSession++
            roomsFromSettings = false
            destination = "Settings"
        }
        fun openRooms() {
            if (destination == "Rooms") return
            roomsFromSettings = destination == "Settings"
            if (!roomsFromSettings) auxiliaryReturn = destination
            destination = "Rooms"
        }
        BackHandler(stack.isNotEmpty() || destination != "Home") {
            if (destination in listOf("Settings", "Rooms")) closeAuxiliary()
            else if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else destination = "Home"
        }
        val destinations = listOf("Home" to Icons.Default.Home, "Library" to Icons.Default.LibraryMusic,
            "Search" to Icons.Default.Search, "Queue" to Icons.AutoMirrored.Filled.QueueMusic)
        val screenKey = if (destination in listOf("Settings", "Rooms")) destination else stack.lastOrNull()?.id ?: destination
        Column(Modifier.fillMaxSize().drawWithCache {
            val wash = Brush.verticalGradient(listOf(colors.surfaceVariant.copy(alpha = .65f), colors.background, colors.background))
            val halo = Brush.radialGradient(listOf(colors.primary.copy(alpha = .16f), Color.Transparent),
                center = Offset(size.width * .68f, -size.height * .18f), radius = size.width * .62f)
            val edge = Brush.radialGradient(listOf(colors.secondary.copy(alpha = .07f), Color.Transparent),
                center = Offset(size.width, size.height * .8f), radius = size.width * .45f)
            onDrawBehind {
                if (wide) { drawRect(Color.Black); drawRect(halo); drawRect(edge) }
                else drawRect(wash)
            }
        }
            .windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = if (wide) 28.dp else 20.dp, vertical = if (compactLandscape) 0.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.GraphicEq, null, tint = colors.primary, modifier = Modifier.size(22.dp))
                Text("HARMONICAST", Modifier.padding(start = 10.dp).weight(1f), fontSize = 12.sp, letterSpacing = 3.sp)
                TextButton(onClick = { openRooms() }, modifier = Modifier.tvFocusFeedback()) {
                    Icon(Icons.Default.Sensors, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("Rooms", fontSize = 12.sp)
                }
                IconButton(onClick = { openSettings() }, modifier = Modifier.tvFocusFeedback()) { Icon(Icons.Default.Settings, "Settings") }
            }
            Row(Modifier.weight(1f)) {
                if (useRail) {
                    NavigationRail(containerColor = Color.Transparent, modifier = Modifier.width(if (compactLandscape) 80.dp else 104.dp)) {
                        destinations.forEach { (name, icon) ->
                            NavigationRailItem(selected = destination == name, onClick = { navigate(name) },
                                icon = { Icon(icon, name) }, label = if (compactLandscape) null else ({ Text(name) }), modifier = Modifier.tvFocusFeedback().then(Modifier.padding(vertical = if (compactLandscape) 0.dp else 8.dp)))
                        }
                    }
                }
                Crossfade(screenKey, Modifier.weight(1f), animationSpec = tween(220), label = "Browse destination") { route ->
                    stateHolder.SaveableStateProvider(route) {
                        TvFocusPage(route, route == screenKey) {
                        val entry = stack.lastOrNull { it.id == route }
                        when {
                            entry?.kind == BrowseKind.ARTISTS -> CollectionBrowser(vm, library, wide, entry, { stack.add(it) }) { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
                            entry != null -> AlbumPage(vm, library, entry) { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
                            route == "Home" -> DiscoveryHome(vm, library, wide, { stack.add(it) }, { navigate("Library") }, { navigate("Player") })
                            route == "Library" -> CollectionBrowser(vm, library, wide, null, { stack.add(it) }) {}
                            route == "Search" -> Search(vm) { stack.add(it) }
                            route == "Queue" -> Queue(vm)
                            route == "Settings" -> SettingsScreen(vm, onBack = { closeAuxiliary() }, onRooms = { openRooms() }, session = settingsSession, isActive = route == screenKey)
                            route == "Rooms" -> RoomsScreen(vm, onBack = { closeAuxiliary() }, isActive = route == screenKey)
                            route == "Player" -> NocturnePlayer(vm) { vm.query = it; vm.search(); navigate("Search") }
                        }
                    }
                    }
                }
            }
            if (destination != "Player") BrowseMiniPlayer(vm) { navigate("Player") }
            if (!useRail) NavigationBar(containerColor = colors.background, windowInsets = WindowInsets(0, 0, 0, 0)) {
                destinations.forEach { (name, icon) -> NavigationBarItem(selected = destination == name,
                    onClick = { navigate(name) }, icon = { Icon(icon, name) }, label = { Text(name, fontSize = 11.sp) }) }
            }
            val message = vm.error.ifBlank { vm.notice }.ifBlank { vm.automaticMixStatus }
            if (message.isNotBlank()) Text(message, Modifier.padding(horizontal = 20.dp, vertical = 4.dp), color = colors.primary, maxLines = 2, fontSize = 12.sp)
        }
        }
    }
}

@Composable private fun BrowseMiniPlayer(vm: HarmonicastViewModel, open: () -> Unit) {
    val song = vm.nowPlaying.song ?: return
    val colors = MaterialTheme.colorScheme
    Surface(onClick = open, color = colors.surfaceVariant.copy(alpha = .85f), shape = RoundedCornerShape(16.dp),
        modifier = Modifier.tvFocusFeedback().then(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth()), border = BorderStroke(1.dp, colors.primary.copy(alpha = .15f))) {
        Column {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Cover(vm, song, 42.dp)
                Column(Modifier.weight(1f)) {
                    Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = colors.onSurfaceVariant)
                }
                IconButton(onClick = { vm.toggle() }, enabled = vm.isActivePlayer, modifier = Modifier.tvFocusFeedback()) { Icon(if (vm.nowPlaying.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (vm.nowPlaying.isPlaying) "Pause" else "Play") }
                IconButton(onClick = { vm.nextSong() }, enabled = vm.isHost, modifier = Modifier.tvFocusFeedback()) { Icon(Icons.Default.SkipNext, "Next track") }
            }
            if (song.duration > 0) LinearProgressIndicator(progress = { (vm.playbackPosition / song.duration).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(2.dp), drawStopIndicator = {})
        }
    }
}

@Composable internal fun DisplayTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, fontFamily = FontFamily.Serif, fontSize = 30.sp, lineHeight = 36.sp, color = MaterialTheme.colorScheme.onSurface)
}

@Composable private fun DiscoveryHome(vm: HarmonicastViewModel, library: MusicLibrary, wide: Boolean, open: (LibraryEntry) -> Unit, collection: () -> Unit, player: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                DisplayTitle("Your next obsession.", Modifier.weight(1f))
                TextButton(onClick = collection, modifier = Modifier.tvFocusFeedback()) { Text("Your library →") }
            }
        }
        item {
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FeatureCard("Your automatic mix", "Favorites and fresh discoveries", Icons.Default.AutoAwesome, Modifier.weight(1f), vm.isActivePlayer) { vm.startRandomPlayback(); player() }
                FeatureCard("Track Radio", "Follow the sound of this track", Icons.Default.Radio, Modifier.weight(1f), vm.nowPlaying.song != null && vm.isActivePlayer) { vm.queueSimilar() }
            }
        }
        item { AlbumShelf("Fresh in your library", library, BrowseOrder.RECENT, wide, open) }
        item { AlbumShelf("Recently played", library, BrowseOrder.PLAYED, wide, open) }
    }
}

@Composable private fun FeatureCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, enabled: Boolean = true, action: () -> Unit) {
    val wide = LocalConfiguration.current.screenWidthDp >= 840
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val lift by animateFloatAsState(if (focused && wide) 1.025f else 1f, tween(180), label = "Discovery focus")
    Surface(onClick = action, enabled = enabled, modifier = Modifier.tvFocusFeedback().then(modifier.fillMaxHeight()
        .onFocusChanged { focused = it.hasFocus }.graphicsLayer { scaleX = lift; scaleY = lift }
        .shadow(if (wide) 20.dp else 0.dp, RoundedCornerShape(20.dp), spotColor = colors.primary.copy(alpha = .35f))),
        shape = RoundedCornerShape(20.dp),
        color = colors.secondaryContainer.copy(alpha = .7f),
        border = BorderStroke(if (focused) 2.dp else 1.dp, colors.primary.copy(alpha = if (focused) .95f else .25f))) {
        if (wide) Row(Modifier.background(Brush.linearGradient(listOf(colors.primary.copy(alpha = .2f), colors.surface, colors.secondaryContainer.copy(alpha = .5f))))
            .padding(22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(44.dp).background(colors.primary.copy(alpha = .16f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = colors.primary)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun AlbumShelf(title: String, library: MusicLibrary, order: BrowseOrder, wide: Boolean, open: (LibraryEntry) -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    var result by remember(library, order) { mutableStateOf<Result<LibraryPage>?>(null) }
    LaunchedEffect(library, order, attempt) {
        result = null
        result = try { Result.success(library.browse(BrowseKind.ALBUMS, order)) }
        catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        when {
            result == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            result!!.isFailure -> RetryMessage { attempt++ }
            result!!.getOrThrow().entries.isEmpty() -> Text("No albums here yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> LazyRow(contentPadding = PaddingValues(vertical = if (wide) 10.dp else 0.dp, horizontal = if (wide) 6.dp else 0.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(result!!.getOrThrow().entries.take(12), key = { it.id }) { entry -> ArtworkTile(entry, Modifier.width(if (wide) 146.dp else 138.dp)) { open(entry) } }
            }
        }
    }
}

@Composable private fun ArtworkTile(entry: LibraryEntry, modifier: Modifier = Modifier, open: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val wide = LocalConfiguration.current.screenWidthDp >= 840
    val scale by animateFloatAsState(if (focused) { if (wide) 1.055f else 1.025f } else 1f, tween(180), label = "Artwork focus")
    val colors = MaterialTheme.colorScheme
    Surface(onClick = open, modifier = Modifier.tvFocusFeedback().then(modifier.onFocusChanged { focused = it.hasFocus }.graphicsLayer { scaleX = scale; scaleY = scale }
        .shadow(if (wide) { if (focused) 24.dp else 10.dp } else 0.dp, RoundedCornerShape(14.dp),
            spotColor = if (focused) colors.primary else Color.Black)),
        shape = RoundedCornerShape(14.dp), color = if (focused) colors.secondaryContainer else colors.surface.copy(alpha = if (wide) .9f else .3f),
        border = BorderStroke(if (focused) 2.dp else 1.dp, if (focused) colors.primary else colors.onSurface.copy(alpha = if (wide) .13f else .07f))) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(if (entry.kind == BrowseKind.ARTISTS) CircleShape else RoundedCornerShape(10.dp)).background(colors.surfaceVariant), contentAlignment = Alignment.Center) {
                Icon(if (entry.kind == BrowseKind.ARTISTS) Icons.Default.Person else Icons.Default.Album, null, Modifier.size(42.dp), tint = colors.primary.copy(alpha = .6f))
                if (entry.artwork != null) AsyncImage(entry.artwork, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(entry.subtitle.ifBlank { entry.year?.toString().orEmpty() }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = colors.onSurfaceVariant)
        }
    }
}

@Composable private fun RetryMessage(retry: () -> Unit) {
    Column { Text("Couldn’t load your library.", color = MaterialTheme.colorScheme.onSurfaceVariant); TextButton(onClick = retry, modifier = Modifier.tvFocusFeedback()) { Text("Try again") } }
}

@Composable private fun CollectionBrowser(vm: HarmonicastViewModel, library: MusicLibrary, wide: Boolean, parent: LibraryEntry?, open: (LibraryEntry) -> Unit, back: () -> Unit) {
    var category by rememberSaveable { mutableStateOf("Albums") }
    var draftQuery by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(draftQuery) {
        if (draftQuery.isNotBlank()) kotlinx.coroutines.delay(300)
        query = draftQuery.trim()
    }
    var biography by remember { mutableStateOf(false) }
    if (biography && parent != null) FocusRestoringAlertDialog(onDismissRequest = { biography = false },
        title = { Text(parent.title) }, text = { Text(parent.summary, Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { biography = false }, modifier = Modifier.tvFocusFeedback()) { Text("Close") } })
    var order by rememberSaveable { mutableStateOf(BrowseOrder.RECENT.name) }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (parent != null) IconButton(onClick = back, modifier = Modifier.tvFocusFeedback()) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            if (parent != null) {
                AsyncImage(parent.artwork, null, Modifier.padding(vertical = 12.dp).size(if (wide) 88.dp else 64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentScale = ContentScale.Crop)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("ARTIST", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, letterSpacing = 2.sp)
                    DisplayTitle(parent.title)
                    Text("Albums & releases", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    if (parent.summary.isNotBlank()) TextButton(onClick = { biography = true }, modifier = Modifier.tvFocusFeedback()) { Text("About the artist") }
                }
            } else DisplayTitle("Your library", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (parent == null) listOf("Albums", "Artists", "Playlists").forEach { label ->
                FilterChip(selected = category == label, onClick = { category = label }, label = { Text(label) }, modifier = Modifier.tvFocusFeedback())
            }
        }
        if (category == "Playlists" && parent == null) { NocturnePlaylists(vm, library); return@Column }
        if (parent == null) Row(verticalAlignment = Alignment.CenterVertically) {
            RemoteTextField(draftQuery, { draftQuery = it }, singleLine = true, label = { Text("Find ${category.lowercase()}") },
                shape = RoundedCornerShape(18.dp), modifier = Modifier.weight(1f),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { query = draftQuery.trim() }))
            IconButton(onClick = { query = draftQuery.trim() }, modifier = Modifier.tvFocusFeedback()) { Icon(Icons.Default.Search, "Find in library") }
            if (query.isNotEmpty()) IconButton(onClick = { query = ""; draftQuery = "" }, modifier = Modifier.tvFocusFeedback()) { Icon(Icons.Default.Close, "Clear library filter") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrowseOrder.entries.filter { it != BrowseOrder.PLAYED }.forEach { choice ->
                TextButton(onClick = { order = choice.name }, modifier = Modifier.tvFocusFeedback()) { Text(choice.label, color = if (order == choice.name) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        key(category, order, parent?.id, query) {
            PagedCollection(library, if (category == "Artists" && parent == null) BrowseKind.ARTISTS else BrowseKind.ALBUMS, BrowseOrder.valueOf(order), parent?.id, wide, query, open)
        }
    }
}

@Composable private fun PagedCollection(library: MusicLibrary, kind: BrowseKind, order: BrowseOrder, parent: String?, wide: Boolean, query: String, open: (LibraryEntry) -> Unit) {
    val state = LocalBrowsePages.current.getOrPut("${kind.name}:${order.name}:$parent:$query") { CollectionState() }
    var entries by state::entries
    var offset by state::offset
    var loading by state::loading
    var failed by state::failed
    val scope = rememberCoroutineScope()
    fun load() {
        val start = offset ?: return
        if (loading) return
        loading = true; failed = false
        scope.launch {
            try {
                val page = library.browse(kind, order, start, parent, query)
                entries = (entries + page.entries).distinctBy { it.id }
                offset = page.nextOffset
            } catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true }
            finally { loading = false }
        }
    }
    LaunchedEffect(Unit) { if (entries.isEmpty()) load() }
    LazyVerticalGrid(columns = if (wide) GridCells.Adaptive(142.dp) else GridCells.Fixed(2),
        contentPadding = PaddingValues(top = 10.dp, start = 6.dp, end = 6.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(entries, key = { it.id }) { entry -> ArtworkTile(entry) { open(entry) } }
        item(span = { GridItemSpan(maxLineSpan) }) {
            when {
                loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(12.dp))
                failed -> RetryMessage { load() }
                offset != null -> TextButton(onClick = { load() }, modifier = Modifier.tvFocusFeedback().then(Modifier.fillMaxWidth())) { Text("Load more") }
                entries.isEmpty() -> Text("No ${kind.label.lowercase()} found.", Modifier.padding(20.dp))
            }
        }
    }
}

@Composable internal fun AlbumPage(vm: HarmonicastViewModel, library: MusicLibrary, entry: LibraryEntry, back: () -> Unit) {
    val albumListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val backFocus = remember { FocusRequester() }
    val television = isTvDevice()
    LaunchedEffect(entry.id) {
        if (television) {
            albumListState.scrollToItem(0)
            // Apply the album entry default after the surrounding page restores focus.
            withFrameNanos { }
            withFrameNanos { }
            backFocus.requestFocus()
        }
    }
    var attempt by remember { mutableIntStateOf(0) }
    var result by remember(entry.id, library) { mutableStateOf<Result<List<Song>>?>(null) }
    LaunchedEffect(entry.id, library, attempt) {
        result = null
        result = try { Result.success(library.albumTracks(entry.id)) } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
    }
    LazyColumn(state = albumListState, contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick = back, modifier = Modifier.tvFocusFeedback().focusRequester(backFocus)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null); Text(" Back") } }
        item {
            val wide = LocalConfiguration.current.screenWidthDp >= 840
            val art: @Composable () -> Unit = {
                AsyncImage(entry.artwork, entry.title, Modifier.size(if (wide) 150.dp else 180.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentScale = ContentScale.Crop)
            }
            val details: @Composable () -> Unit = {
                AlbumInformation(vm, entry, result)
            }
            if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                art(); Column(Modifier.weight(1f)) { details() }
            } else Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) { art(); details() }
        }
        when {
            result == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            result!!.isFailure -> item { RetryMessage { attempt++ } }
            result!!.getOrThrow().isEmpty() -> item { Text("No tracks in this album.") }
            else -> items(result!!.getOrThrow(), key = { it.id }) { song -> SongRow(vm, song, true) }
        }
    }
}

@Composable private fun AlbumInformation(vm: HarmonicastViewModel, entry: LibraryEntry, result: Result<List<Song>>?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DisplayTitle(entry.title)
        Text(entry.subtitle, color = MaterialTheme.colorScheme.primary)
        Text(listOfNotNull(entry.year?.toString(), result?.getOrNull()?.let { "${it.size} ${if (it.size == 1) "track" else "tracks"}" }).joinToString(" · "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { vm.loadAlbum(entry, PlaylistAction.PLAY) }, enabled = vm.isActivePlayer && result?.isSuccess == true, modifier = Modifier.tvFocusFeedback()) { Icon(Icons.Default.PlayArrow, null); Text("Play album") }
            OutlinedButton(onClick = { vm.loadAlbum(entry, PlaylistAction.SHUFFLE) }, enabled = vm.isActivePlayer && result?.isSuccess == true, modifier = Modifier.tvFocusFeedback()) { Text("Shuffle") }
        }
        Row {
            TextButton(onClick = { vm.loadAlbum(entry, PlaylistAction.NEXT) }, enabled = vm.isActivePlayer && result?.isSuccess == true, modifier = Modifier.tvFocusFeedback()) { Text("Play next") }
            TextButton(onClick = { vm.loadAlbum(entry, PlaylistAction.QUEUE) }, enabled = vm.isActivePlayer && result?.isSuccess == true, modifier = Modifier.tvFocusFeedback()) { Text("Add to queue") }
        }
    }
}
