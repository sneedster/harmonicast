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
    var downloadGateway by mutableStateOf<AcquisitionSetupGateway?>(null); private set
    var downloadMessage by mutableStateOf(""); private set
    var preparation by mutableStateOf<SharedPlexPreparation?>(null); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf(""); private set
    private var operation: Job? = null
    private var generation = 0

    fun cancel() { generation++; operation?.cancel(); busy = false }
    fun clear() { cancel(); closeDownload(); preparation = null; message = "" }
    fun openDownload(context: android.content.Context) {
        if (downloadGateway?.state?.value?.url?.isNotBlank() == true) return
        closeDownload()
        val next = AcquisitionSetupGateway(context.applicationContext)
        try { next.start(); downloadGateway = next; downloadMessage = "" }
        catch (_: Exception) {
            viewModelScope.launch { next.close() }
            downloadMessage = "Connect this device to private Wi-Fi or Ethernet, then try opening the download page again."
        }
    }
    fun closeDownload() {
        val old = downloadGateway; downloadGateway = null; downloadMessage = ""
        if (old != null) viewModelScope.launch { old.close() }
    }
    override fun onCleared() {
        val old = downloadGateway
        if (old != null) kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { old.close() }
    }
    fun run(action: suspend () -> SharedPlexPreparation) {
        if (busy) return
        val expected = ++generation
        // Keep the publication subtree mounted: its disposal clears the tested login.
        // The service revalidates the reviewed record before any mutation.
        busy = true; message = "Checking Plex and preparing your setup…"
        operation = viewModelScope.launch {
            try {
                val result = action()
                if (expected == generation) { preparation = result; message = "" }
            } catch (_: TimeoutCancellationException) {
                if (expected == generation) message = "Plex took too long to respond. Choose Check again to resume setup."
            } catch (e: CancellationException) { throw e }
            catch (e: SharedPlexSetupProblem) { if (expected == generation) message = e.message.orEmpty() }
            catch (e: PlexRequestFailure) {
                if (expected == generation) message = when (e.status) {
                    401, 403 -> "Plex denied access (HTTP ${e.status}). Reconnect as the server owner, then choose Check again."
                    404 -> "Plex could not find the setup item (HTTP 404). Let the library scan finish, then choose Check again."
                    else -> "Plex rejected the setup request (HTTP ${e.status}). Choose Check again to reload the album before retrying."
                }
            }
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
    var exporting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            exporting = true
            exportMessage = "Saving setup ZIP…"
            exportMessage = try {
                withContext(Dispatchers.IO) {
                    context.assets.open("shared-plex/harmonicast-plex-setup.zip").use { input ->
                        checkNotNull(context.contentResolver.openOutputStream(uri)).use { output -> input.copyTo(output) }
                    }
                }
                "ZIP saved. Extract it into the dedicated Harmonicast folder that Plex can read."
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { "The ZIP could not be saved. Choose a writable location and try again." }
            finally { exporting = false }
        }
    }
    LaunchedEffect(source, isActive) {
        if (!isActive || source == null || !vm.plexAccess.canManageAcquisition) model.clear()
        else model.run { service.inspect(source) }
    }
    DisposableEffect(source) { onDispose { model.clear() } }
    if (source == null || !vm.plexAccess.canManageAcquisition) return
    Text("Shared access", style = MaterialTheme.typography.titleMedium)
    SharedAccessStatus(model.preparation, model.busy, model.message)
    SettingsDescription("${source.serverName} · ${source.libraryName}")
    if (!expanded) {
        OutlinedButton(onClick = { expanded = true; model.run { service.inspect(source) } }, modifier = Modifier.tvFocusFeedback()) {
            Text(if (model.preparation?.stage == SharedPlexPreparation.Stage.PUBLISHED) "Manage shared access" else "Set up shared access")
        }
        return
    }

    val state = model.preparation
    SharedPlexSetupContent(state, model.busy || exporting, model.message, folder, { folder = it }, exportMessage,
        onExport = { exporter.launch("harmonicast-plex-setup.zip") },
        onCheck = { model.run { service.inspect(source, folder) } },
        onPrepare = { model.run { service.prepare(source, folder, state) } },
        publicationContent = {
            if (state != null) SharedPublicationSettings(vm, source, state, service, model.busy) { action -> model.run(action) }
        },
        downloadContent = {
            val gateway = model.downloadGateway
            val download = gateway?.state?.collectAsState()?.value
            if (download?.url?.isNotBlank() == true) {
                Text(download.url, style = MaterialTheme.typography.titleMedium)
                SettingsDescription(download.message)
                SettingsDescription("Download and extract the ZIP from that computer. No pairing code or MusicGrabber login is needed.")
                TextButton(onClick = model::closeDownload, modifier = Modifier.tvFocusFeedback()) { Text("Close download page") }
            } else {
                OutlinedButton(onClick = { model.openDownload(context) }, enabled = !model.busy && !exporting,
                    modifier = Modifier.tvFocusFeedback()) { Text("Download from a computer") }
                val feedback = model.downloadMessage.ifBlank { download?.message.orEmpty() }
                if (feedback.isNotBlank()) Text(feedback, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall)
            }
        })
    TextButton(onClick = { model.cancel(); model.closeDownload(); expanded = false }, modifier = Modifier.tvFocusFeedback()) { Text("Close shared setup") }
}

/** Uses the acquisition screen's existing scroll owner, typography, fields, and focus behavior. */
@Composable internal fun SharedPlexSetupContent(
    state: SharedPlexPreparation?, busy: Boolean, message: String, folder: String, onFolder: (String) -> Unit,
    exportMessage: String, onExport: () -> Unit, onCheck: () -> Unit, onPrepare: () -> Unit,
    downloadContent: @Composable () -> Unit = {},
    publicationContent: @Composable () -> Unit = {},
) {
    val stage = state?.stage
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (stage == SharedPlexPreparation.Stage.FOLDER || stage == SharedPlexPreparation.Stage.SCANNING) {
            SettingsDescription("On a computer, download the setup ZIP and extract its contents into a dedicated Harmonicast folder outside your music folders. The audio tags are already filled in; the app handles your server-specific metadata afterward.")
            downloadContent()
            OutlinedButton(onClick = onExport, enabled = !busy, modifier = Modifier.tvFocusFeedback()) { Text("Save setup ZIP on this device") }
        }
        when (stage) {
            SharedPlexPreparation.Stage.FOLDER -> {
                if (state.folder.isNotBlank()) SettingsDescription("Existing Plex folder: ${state.folder}")
                RemoteTextField(folder, onFolder, Modifier.fillMaxWidth(), label = { Text("Folder on Plex server") },
                    enabled = !busy, allowVoice = false)
                SettingsDescription("Enter the Harmonicast folder path Plex sees, not the artist subfolder. For Docker, use the container path and add a mapping if needed. Plex must be able to list the folders and read the audio file, including on NAS/SMB shares.")
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
                SettingsDescription("Plex has not found the setup album yet. Extract the ZIP into ${state.folder.ifBlank { "the dedicated folder" }}. Check Plex's folder mapping and read permissions, then scan again.")
                Button(onClick = onPrepare, enabled = !busy, modifier = Modifier.tvFocusFeedback()) { Text("Scan for setup file") }
            }
            SharedPlexPreparation.Stage.READY -> {
                Text("Library ready", style = MaterialTheme.typography.titleMedium)
                SettingsDescription("${state.libraryName} is prepared and its locked setup record has been verified.")
                SettingsDescription("Shared acquisition is still off. Connect and review a dedicated non-admin MusicGrabber account below to publish access.")
                var showDownload by remember { mutableStateOf(false) }
                TextButton(onClick = { showDownload = !showDownload }, enabled = !busy, modifier = Modifier.tvFocusFeedback()) { Text(if (showDownload) "Hide setup files" else "Setup files and instructions") }
                if (showDownload) downloadContent()
                publicationContent()
            }
            SharedPlexPreparation.Stage.PUBLISHED -> {
                publicationContent()
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


@Composable internal fun SharedAccessStatus(state: SharedPlexPreparation?, busy: Boolean, message: String) {
    val title = when {
        busy -> "Checking shared access…"
        message.isNotBlank() -> "Shared access needs checking"
        state?.stage == SharedPlexPreparation.Stage.PUBLISHED -> "Shared access is on"
        state?.stage == SharedPlexPreparation.Stage.READY -> "Library ready · sharing is off"
        else -> "Shared access is not set up"
    }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (!busy) SettingsDescription(when {
                message.isNotBlank() -> "Open setup and check again to confirm the current Plex status."
                state?.stage == SharedPlexPreparation.Stage.PUBLISHED -> "Your shared account was published and verified in Plex. Share the Harmonicast library with your intended recipients."
                state?.stage == SharedPlexPreparation.Stage.READY -> "Connect a dedicated account to finish sharing."
                else -> "Let selected Plex users request music through a dedicated MusicGrabber account."
            })
        }
    }
}
