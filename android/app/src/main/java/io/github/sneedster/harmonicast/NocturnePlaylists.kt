package io.github.sneedster.harmonicast

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

@Composable internal fun NocturnePlaylists(vm: HarmonicastViewModel, library: MusicLibrary) {
    val focusState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableStateOf("") }
    var attempt by remember { mutableIntStateOf(0) }
    var result by remember(library) { mutableStateOf<Result<List<PlexPlaylist>>?>(null) }
    LaunchedEffect(library, attempt) {
        result = null
        result = try { Result.success(library.playlists()) }
        catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
    }
    val playlist = result?.getOrNull()?.firstOrNull { it.id == selected }
    BackHandler(selected != null) { selected = null }
    if (playlist != null) {
        focusState.SaveableStateProvider(playlist.id) {
            TvFocusPage("playlist:${playlist.id}") { PlaylistDestination(vm, library, playlist) { selected = null } }
        }
        return
    }
    focusState.SaveableStateProvider("list") {
    TvFocusPage("playlists:list") {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RemoteTextField(filter, { filter = it }, singleLine = true, label = { Text("Filter playlists") },
                shape = RoundedCornerShape(18.dp), modifier = Modifier.weight(1f))
            IconButton(onClick = { attempt++ }, modifier = Modifier.tvFocusFeedback()) { Icon(Icons.Default.Refresh, "Refresh playlists") }
        }
        when {
            result == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            result!!.isFailure -> { Text("Couldn’t load playlists."); TextButton(onClick = { attempt++ }, modifier = Modifier.tvFocusFeedback()) { Text("Try again") } }
            result!!.getOrThrow().isEmpty() -> Text("No audio playlists in this Plex account. Playlists belong to their creator.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> {
                val visible = result!!.getOrThrow().filter { it.title.contains(filter, ignoreCase = true) }.sortedBy { it.title.lowercase() }
                if (visible.isEmpty()) Text("No playlists match this filter.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyVerticalGrid(state = gridState, columns = GridCells.Adaptive(180.dp), contentPadding = PaddingValues(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(visible, key = { it.id }) { entry ->
                        var focused by remember { mutableStateOf(false) }
                        val colors = MaterialTheme.colorScheme
                        Surface(onClick = { selected = entry.id }, shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.tvFocusFeedback().then(Modifier.onFocusChanged { focused = it.hasFocus }),
                            border = BorderStroke(if (focused) 2.dp else 1.dp, colors.primary.copy(alpha = if (focused) 1f else .2f))) {
                            Column(Modifier.background(Brush.linearGradient(listOf(colors.secondaryContainer, colors.surface))).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(Icons.AutoMirrored.Filled.QueueMusic, null, Modifier.size(48.dp), tint = colors.primary)
                                Text(entry.title, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                Text("${entry.trackCount} tracks", color = colors.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
    }
    }
}

@Composable private fun PlaylistDestination(vm: HarmonicastViewModel, library: MusicLibrary, playlist: PlexPlaylist, back: () -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    var offset by remember(library, playlist.id) { mutableIntStateOf(0) }
    var nextOffset by remember(library, playlist.id) { mutableStateOf<Int?>(null) }
    var tracks by remember(library, playlist.id) { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember(library, playlist.id) { mutableStateOf(true) }
    var failed by remember(library, playlist.id) { mutableStateOf(false) }
    LaunchedEffect(library, playlist.id, offset, attempt) {
        loading = true; failed = false
        try {
            val page = library.playlistPage(playlist.id, offset)
            tracks = tracks + page.tracks
            nextOffset = page.nextOffset
        } catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true }
        finally { loading = false }
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { TextButton(onClick = back, modifier = Modifier.tvFocusFeedback()) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null); Text(" Playlists") } }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("PLEX PLAYLIST", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                DisplayTitle(playlist.title)
                Text("${playlist.trackCount} tracks", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${tracks.size} playable tracks loaded", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { vm.loadPlaylist(playlist, PlaylistAction.PLAY) }, enabled = vm.isActivePlayer && tracks.isNotEmpty(), modifier = Modifier.tvFocusFeedback()) { Icon(Icons.Default.PlayArrow, null); Text("Play") }
                    OutlinedButton(onClick = { vm.loadPlaylist(playlist, PlaylistAction.SHUFFLE) }, enabled = vm.isActivePlayer && tracks.isNotEmpty(), modifier = Modifier.tvFocusFeedback()) { Text("Shuffle") }
                }
                Row {
                    TextButton(onClick = { vm.loadPlaylist(playlist, PlaylistAction.NEXT) }, enabled = vm.isActivePlayer && tracks.isNotEmpty(), modifier = Modifier.tvFocusFeedback()) { Text("Play next") }
                    TextButton(onClick = { vm.loadPlaylist(playlist, PlaylistAction.QUEUE) }, enabled = vm.isActivePlayer && tracks.isNotEmpty(), modifier = Modifier.tvFocusFeedback()) { Text("Add to queue") }
                }
            }
        }
        items(tracks) { song -> SongRow(vm, song, true) }
        item {
            when {
                loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                failed -> { Text("Couldn’t load playlist tracks."); TextButton(onClick = { attempt++ }, modifier = Modifier.tvFocusFeedback()) { Text("Try again") } }
                nextOffset != null -> OutlinedButton(onClick = { offset = nextOffset!! }, modifier = Modifier.tvFocusFeedback().then(Modifier.fillMaxWidth())) { Text("Load more tracks") }
                tracks.isEmpty() -> Text("No playable tracks in this playlist.")
            }
        }
    }
}
