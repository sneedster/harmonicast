package io.github.sneedster.harmonicast

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable internal fun PrivateTrackRepairAction(song: Song, allowed: Boolean) {
    if (!allowed) return
    val context = LocalContext.current.applicationContext
    val storage = remember { SharedPreferencesProfileStorage(context.getSharedPreferences("harmonicast", Context.MODE_PRIVATE)) }
    val profile = remember { HomeProfileStore(storage) }
    val source = profile.personalSource ?: return
    val account = remember { AcquisitionRuntime.get(context).ownerAccount }
    val accountState by account.state.collectAsState()
    val connection = account.connection() ?: return
    if (connection.apiKeyMode || connection.accountId.isBlank() || !source.canWriteToPlex) return
    val model = remember(source, connection.identity) {
        val plex = LocalPlexClient(storage)
        PrivateTrackRepair(source, storage, connection.identity,
            valid = { profile.personalSource == source && account.connection()?.identity == connection.identity },
            file = { plex.maintenanceFile(source, it) },
            call = { path, method, body -> account.call(path, method, body, connection.identity) })
    }
    LaunchedEffect(model, accountState.available) { model.checkEnabled() }
    val state by model.state.collectAsState()
    if (!state.enabled) return
    var menu by remember(model) { mutableStateOf(false) }
    var opened by remember(model) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Box {
        IconButton(onClick = { menu = true }, modifier = Modifier.tvFocusFeedback()) { Icon(Icons.Default.MoreVert, "Track actions") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Find another copy") }, onClick = {
                menu = false; opened = true
                scope.launch { model.open(song) }
            })
        }
    }
    if (opened) RepairDialog(state, onClose = { opened = false },
        onReplace = { scope.launch { model.replace(it) } },
        onRefresh = { scope.launch { model.refresh() } },
        onSearch = { scope.launch { model.search(song) } })
}

@Composable internal fun RepairDialog(state: RepairState, onClose: () -> Unit,
    onReplace: (Int) -> Unit, onRefresh: () -> Unit, onSearch: () -> Unit) {
    var selected by remember(state.id) { mutableStateOf<Int?>(null) }
    FocusRestoringAlertDialog(onDismissRequest = onClose,
        title = { Text("Find another copy") },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${state.title} — ${state.artist}", style = MaterialTheme.typography.titleMedium)
                Box(Modifier.fillMaxWidth().height(4.dp)) { if (state.working) LinearProgressIndicator(Modifier.fillMaxWidth()) }
                Text(state.message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (state.status == "ready") state.choices.forEach { choice ->
                    OutlinedButton(onClick = { selected = choice.id }, enabled = !state.working,
                        modifier = Modifier.fillMaxWidth().tvFocusFeedback()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text((if (selected == choice.id) "Selected: " else "") + choice.title)
                            Text("${choice.artist} · ${choice.source} · ${choice.quality}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (state.status == "ready") TextButton(onClick = onSearch, enabled = !state.working,
                    modifier = Modifier.tvFocusFeedback()) { Text("New search") }
                if (state.status == "ready") Text("This changes the library file. The old copy stays in MusicGrabber quarantine, where you can restore it. Current playback may continue using its buffered copy.")
                if (state.status in setOf("searching", "replacing")) Text("You can close this window. Reopen Find another copy to check progress.")
            }
        },
        confirmButton = {
            when {
                state.status == "ready" -> TextButton(onClick = { selected?.let(onReplace) }, enabled = selected != null && !state.working, modifier = Modifier.tvFocusFeedback()) { Text("Replace copy") }
                state.status in setOf("unconfirmed", "unknown") -> TextButton(onClick = onRefresh, enabled = !state.working, modifier = Modifier.tvFocusFeedback()) { Text("Check status") }
                state.status in setOf("failed", "empty", "done") -> TextButton(onClick = onSearch, enabled = !state.working, modifier = Modifier.tvFocusFeedback()) { Text("New search") }
            }
        },
        dismissButton = { TextButton(onClick = onClose, modifier = Modifier.tvFocusFeedback()) { Text("Close") } })
}
