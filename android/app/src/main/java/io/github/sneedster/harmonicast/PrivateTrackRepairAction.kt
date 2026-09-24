package io.github.sneedster.harmonicast

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
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

@Composable internal fun PrivateRepairVersion() {
    val context = LocalContext.current
    val storage = remember { SharedPreferencesProfileStorage(context.getSharedPreferences("harmonicast", Context.MODE_PRIVATE)) }
    val toggle = remember { PrivateRepairSwitch(storage) }
    Text("Installed version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.clickable {
            if (HomeProfileStore(storage).personalSource?.canWriteToPlex == true) {
                toggle.tap(android.os.SystemClock.elapsedRealtime())?.let {
                    Toast.makeText(context, if (it) "Private track repair enabled" else "Private track repair disabled", Toast.LENGTH_SHORT).show()
                }
            }
        })
}

@Composable internal fun PrivateTrackRepairAction(song: Song, allowed: Boolean) {
    if (!allowed) return
    val context = LocalContext.current.applicationContext
    val storage = remember { SharedPreferencesProfileStorage(context.getSharedPreferences("harmonicast", Context.MODE_PRIVATE)) }
    if (!PrivateRepairSwitch(storage).enabled) return
    val profile = remember { HomeProfileStore(storage) }
    val source = profile.personalSource ?: return
    if (!source.canWriteToPlex) return
    val account = remember { AcquisitionRuntime.get(context).ownerAccount }
    val accountState by account.state.collectAsState()
    val connection = account.connection() ?: return
    val model = remember(source, connection.identity) {
        val plex = LocalPlexClient(storage)
        val valid = { profile.personalSource == source && account.connection()?.identity == connection.identity && PrivateRepairSwitch(storage).enabled }
        PrivateTrackRepair(storage, "${connection.identity}|${plexIdentity(source)}", valid,
            checkAccount = { account.check(true) }, file = { plex.badFile(source, it) },
            delete = { plex.deleteBadFile(source, it, valid) }, deleted = { plex.badFileDeleted(source, it) },
            call = { path, method, body -> account.call(path, method, body, connection.identity) })
    }
    // Observe account changes even when an old connection is still persisted.
    if (!accountState.configured && account.connection() == null) return
    val state by model.state.collectAsState()
    var menu by remember(model) { mutableStateOf(false) }
    var opened by remember(model) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Box {
        IconButton(onClick = { menu = true }, modifier = Modifier.tvFocusFeedback()) { Icon(Icons.Default.MoreVert, "Track actions") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Replace bad file") }, onClick = {
                menu = false; opened = true
                scope.launch { model.open(song) }
            })
        }
    }
    if (opened) RepairDialog(state, onClose = { opened = false },
        onReplace = { scope.launch { model.replace() } }, onRefresh = { scope.launch { model.refresh() } })
}

@Composable internal fun RepairDialog(state: RepairState, onClose: () -> Unit,
    onReplace: () -> Unit, onRefresh: () -> Unit) {
    FocusRestoringAlertDialog(onDismissRequest = onClose,
        title = { Text("Replace bad file") },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${state.title} — ${state.artist}", style = MaterialTheme.typography.titleMedium)
                Box(Modifier.fillMaxWidth().height(4.dp)) { if (state.working) LinearProgressIndicator(Modifier.fillMaxWidth()) }
                Text(state.message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        },
        confirmButton = {
            when (state.status) {
                "ready" -> TextButton(onClick = onReplace, enabled = !state.working, modifier = Modifier.tvFocusFeedback()) { Text("Delete and replace") }
                "delete_unknown", "acquiring" -> TextButton(onClick = onRefresh, enabled = !state.working, modifier = Modifier.tvFocusFeedback()) { Text("Check status") }
                "deleted", "request_failed" -> TextButton(onClick = onRefresh, enabled = !state.working, modifier = Modifier.tvFocusFeedback()) { Text("Request fresh copy") }
            }
        },
        dismissButton = { TextButton(onClick = onClose, modifier = Modifier.tvFocusFeedback()) { Text("Close") } })
}
