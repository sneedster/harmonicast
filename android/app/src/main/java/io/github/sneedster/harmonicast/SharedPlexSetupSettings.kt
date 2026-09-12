package io.github.sneedster.harmonicast

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class SharedPlexSetupModel : ViewModel() {
    var preparation by mutableStateOf<SharedPlexPreparation?>(null); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf(""); private set
    private var operation: Job? = null
    private var generation = 0

    fun cancel() { generation++; operation?.cancel(); busy = false }
    fun clear() { cancel(); preparation = null; message = "" }
    fun run(action: suspend () -> SharedPlexPreparation) {
        if (busy) return
        val expected = ++generation
        if (preparation?.stage == SharedPlexPreparation.Stage.READY) preparation = null
        busy = true; message = "Checking Plex and preparing your setup…"
        operation = viewModelScope.launch {
            try {
                val result = action()
                if (expected == generation) { preparation = result; message = "" }
            } catch (_: TimeoutCancellationException) {
                if (expected == generation) message = "Plex took too long to respond. Choose Check again to resume setup."
            } catch (e: CancellationException) { throw e }
            catch (e: SharedPlexSetupProblem) { if (expected == generation) message = e.message.orEmpty() }
            catch (_: Exception) {
                // Never show raw network responses or metadata; both can contain credentials.
                if (expected == generation) message = "Setup could not finish. Check your connection and owner sign-in, then choose Check again to resume."
            } finally { if (expected == generation) busy = false }
        }
    }
}

@Composable internal fun SharedPlexSetupSettings(vm: HarmonicastViewModel, isActive: Boolean) {
    val context = LocalContext.current
    val model: SharedPlexSetupModel = viewModel(key = "shared-plex-setup")
    val service = remember(vm) { vm.sharedSetupService() }
    val source = vm.sharedSetupSource()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var folder by rememberSaveable(source?.machineIdentifier, source?.libraryKey) { mutableStateOf("") }
    var exportMessage by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/flac")) { uri ->
        if (uri != null) scope.launch {
            exportMessage = "Saving setup file…"
            exportMessage = try {
                withContext(Dispatchers.IO) {
                    context.assets.open("shared-plex/01-sharing-proof.flac").use { input ->
                        checkNotNull(context.contentResolver.openOutputStream(uri)).use { output -> input.copyTo(output) }
                    }
                }
                "File saved. Place it in a dedicated folder that Plex can read on your server."
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { "The file could not be saved. Choose a writable location and try again." }
        }
    }
    LaunchedEffect(source, isActive) {
        if (!isActive || source == null || !vm.plexAccess.canManageAcquisition) model.clear()
    }
    DisposableEffect(source) { onDispose { model.clear() } }
    if (source == null || !vm.plexAccess.canManageAcquisition) return
    Text("Shared access", style = MaterialTheme.typography.titleMedium)
    SettingsDescription("Prepare a separate Plex library for people you choose to share music acquisition with.")
    if (!expanded) {
        OutlinedButton(onClick = { expanded = true; model.run { service.inspect(source) } }, modifier = Modifier.tvFocusFeedback()) {
            Text("Set up shared access")
        }
        return
    }
    Text(source.serverName, style = MaterialTheme.typography.titleMedium)
    SettingsDescription("Music destination: ${source.libraryName}")
    val state = model.preparation
    SharedPlexSetupContent(state, model.busy, model.message, folder, { folder = it }, exportMessage,
        onExport = { exporter.launch("01-sharing-proof.flac") },
        onCheck = { model.run { service.inspect(source, folder) } },
        onPrepare = { model.run { service.prepare(source, folder, state) } })
    TextButton(onClick = { model.cancel(); expanded = false }, modifier = Modifier.tvFocusFeedback()) { Text("Close shared setup") }
}

/** Uses the acquisition screen's existing scroll owner, typography, fields, and focus behavior. */
@Composable internal fun SharedPlexSetupContent(
    state: SharedPlexPreparation?, busy: Boolean, message: String, folder: String, onFolder: (String) -> Unit,
    exportMessage: String, onExport: () -> Unit, onCheck: () -> Unit, onPrepare: () -> Unit,
) {
    val stage = state?.stage
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (stage) {
            SharedPlexPreparation.Stage.FOLDER -> {
                SettingsDescription("Save the small setup file and place it in a dedicated folder on your Plex server. This is a one-time step; Harmonicast handles the library and metadata afterward.")
                OutlinedButton(onClick = onExport, enabled = !busy, modifier = Modifier.tvFocusFeedback()) { Text("Save setup file") }
                if (state.folder.isNotBlank()) SettingsDescription("Existing Plex folder: ${state.folder}")
                RemoteTextField(folder, onFolder, Modifier.fillMaxWidth(), label = { Text("Folder on Plex server") },
                    enabled = !busy, allowVoice = false)
                SettingsDescription("Use the folder path Plex sees, including the container path if your server uses Docker.")
                Button(onClick = onPrepare, enabled = !busy && folder.isNotBlank(), modifier = Modifier.tvFocusFeedback()) {
                    Text(if (state.libraryKey.isBlank()) "Create setup library" else "Use this folder")
                }
            }
            SharedPlexPreparation.Stage.REVIEW -> {
                Text("Finish library setup", style = MaterialTheme.typography.titleMedium)
                SettingsDescription("${state.libraryName} · ${state.albumName}")
                SettingsDescription("Harmonicast will save and lock an inactive setup record in this album. This step contains no MusicGrabber login and does not enable shared acquisition.")
                Button(onClick = onPrepare, enabled = !busy, modifier = Modifier.tvFocusFeedback()) { Text("Prepare library") }
            }
            SharedPlexPreparation.Stage.SCANNING -> {
                SettingsDescription("Plex has not found the setup album yet. Make sure the file is in ${state.folder.ifBlank { "the dedicated folder" }}, then scan again.")
                Button(onClick = onPrepare, enabled = !busy, modifier = Modifier.tvFocusFeedback()) { Text("Scan for setup file") }
            }
            SharedPlexPreparation.Stage.READY -> {
                Text("Library ready", style = MaterialTheme.typography.titleMedium)
                SettingsDescription("${state.libraryName} is prepared and its locked setup record has been verified.")
                SettingsDescription("Shared acquisition is still off. Connecting and publishing a restricted MusicGrabber account will be available in a later update.")
            }
            null -> if (!busy) SettingsDescription("Check Plex to find or resume your shared-access setup.")
        }
        // Stable progress region; status is retained inline and announced accessibly.
        Box(Modifier.fillMaxWidth().height(4.dp)) { if (busy) LinearProgressIndicator(Modifier.fillMaxWidth()) }
        Text(message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall)
        if (exportMessage.isNotBlank()) SettingsDescription(exportMessage)
        if (!busy) TextButton(onClick = onCheck, modifier = Modifier.tvFocusFeedback()) { Text("Check again") }
    }
}
