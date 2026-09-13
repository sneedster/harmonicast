package io.github.sneedster.harmonicast

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*

internal class SharedPublicationModel : ViewModel() {
    var url by mutableStateOf("")
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var candidate by mutableStateOf<AcquisitionConnection?>(null); private set
    var gateway by mutableStateOf<AcquisitionSetupGateway?>(null); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf(""); private set
    var account: MusicGrabberAccount? = null; private set
    private var operation: Job? = null
    private var generation = 0
    fun initialize(initialUrl: String = "", valid: () -> Boolean) {
        if (account != null) return
        if (url.isBlank()) url = initialUrl
        val memory = object : ProfileStorage {
            private val values = java.util.concurrent.ConcurrentHashMap<String, String>()
            override fun read(key: String) = values[key]
            override fun write(values: Map<String, String>) { this.values.putAll(values) }
        }
        account = MusicGrabberAccount(memory, AndroidSecretCipher(), restricted = true, contextValid = valid)
    }
    fun run(action: suspend () -> Unit) {
        if (busy) return
        val version = generation
        busy = true; message = "Checking the dedicated account…"
        operation = viewModelScope.launch {
            try { withTimeout(60_000) { action() }; if (generation == version) message = "" }
            catch (_: TimeoutCancellationException) { if (generation == version) message = "Account check timed out. Check the service address and try again." }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == version) message = safeAcquisitionError(e) }
            finally { if (generation == version) busy = false }
        }
    }
    fun test() = run {
        val tested = requireNotNull(account).validate(AcquisitionLogin(url, username, password))
        candidate?.let { account?.revoke(it) }
        candidate = tested; password = ""
    }
    fun startComputer(context: Context) = run {
        gateway?.close()
        val next = AcquisitionSetupGateway(context.applicationContext, requireNotNull(account), initialUrl = url)
        try { next.start(); gateway = next } catch (e: Exception) { next.close(); throw e }
    }
    fun clear() {
        generation++; operation?.cancel(); busy = false; password = ""; message = ""
        val old = candidate; candidate = null
        val page = gateway; gateway = null
        val service = account
        account = null
        CoroutineScope(Dispatchers.IO).launch { old?.let { service?.revoke(it) }; page?.close() }
    }
    override fun onCleared() { clear() }
}

@Composable internal fun SharedPublicationSettings(vm: HarmonicastViewModel, source: PersonalPlexSource,
    state: SharedPlexPreparation, service: SharedPlexSetup, preparing: Boolean,
    perform: (suspend () -> SharedPlexPreparation) -> Unit) {
    val context = LocalContext.current
    val model: SharedPublicationModel = viewModel(key = "shared-publication-${sharedSourceIdentity(source)}")
    var expanded by remember(source) { mutableStateOf(false) }
    var rooms by remember(source) { mutableStateOf(false) }
    var destinationConfirmed by remember(model.candidate, model.gateway?.state?.collectAsState()?.value?.staged) { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var disable by remember { mutableStateOf(false) }
    model.initialize(AcquisitionRuntime.get(context).ownerAccount.connection()?.url.orEmpty()) {
        vm.sharedSetupSource() == source && vm.plexAccess.canManageAcquisition
    }
    DisposableEffect(source) { onDispose { model.clear() } }
    val staged = model.gateway?.state?.collectAsState()?.value
    val candidate = staged?.staged ?: model.candidate
    val busy = preparing || model.busy
    val published = state.stage == SharedPlexPreparation.Stage.PUBLISHED
    if (!published) Text("Connect a shared account", style = MaterialTheme.typography.titleMedium)
    if (published) {
        val record = remember(state) { SharedAcquisitionRecord.parse(state.summary, source) }
        SettingsDescription("${record.url}\nAccount: ${record.username}\nRoom acquisition: ${if (record.rooms) "permitted" else "not permitted"}")
        SettingsDescription("In Plex, grant the Harmonicast library only to your intended recipients, alongside ${source.libraryName}. Everyone with access to it can read and use the published account.")
        TextButton(onClick = { disable = true }, enabled = !busy, modifier = Modifier.tvFocusFeedback()) { Text("Disable shared access") }
    }
    if (!expanded) {
        OutlinedButton(onClick = { expanded = true }, enabled = !busy, modifier = Modifier.tvFocusFeedback()) {
            Text(if (published) "Update shared account" else "Connect dedicated account")
        }
    } else if (candidate == null) {
        SettingsDescription("Use a separate non-admin MusicGrabber account. A peon account is recommended. Your personal saved connection is not copied here.")
        if (staged?.url?.isNotBlank() == true) {
            Text(staged.url, style = MaterialTheme.typography.titleMedium)
            Text("Pairing code: ${staged.code}", style = MaterialTheme.typography.headlineSmall)
            SettingsDescription("Open this page on your computer and test the dedicated account. Return here to review and publish it. Keep Remember login enabled; no credentials are published by the web page.")
        } else {
            OutlinedButton(onClick = { model.startComputer(context) }, enabled = !busy, modifier = Modifier.tvFocusFeedback()) { Text("Enter shared login from a computer") }
            LoginField(model.url, { model.url = it }, "Shared MusicGrabber HTTPS URL", enabled = !busy)
            LoginField(model.username, { model.username = it }, "Shared username", username = true, enabled = !busy)
            LoginField(model.password, { model.password = it }, "Password", secret = true, enabled = !busy)
            Button(onClick = model::test, enabled = !busy && model.url.isNotBlank() && model.username.isNotBlank() && model.password.isNotEmpty(), modifier = Modifier.tvFocusFeedback()) { Text("Test shared account") }
        }
        if (staged != null) SettingsDescription(staged.message)
    } else {
        SharedAccountReview(source, state, candidate, rooms, destinationConfirmed, busy,
            { rooms = it }, { destinationConfirmed = it }, { confirm = true })
    }
    if (expanded) TextButton(onClick = { model.clear(); expanded = false }, enabled = !preparing, modifier = Modifier.tvFocusFeedback()) { Text("Cancel account setup") }
    Box(Modifier.fillMaxWidth().height(4.dp)) { if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth()) }
    Text(model.message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    if (confirm && candidate != null) FocusRestoringAlertDialog(onDismissRequest = { confirm = false },
        title = { Text("Publish shared access?") },
        text = { Text("Publish ${candidate.username} at ${candidate.url} to ${state.libraryName} on ${source.serverName}, for ${source.libraryName}. Everyone who can read this Plex library receives the account password. Room acquisition: ${if (rooms) "permitted" else "not permitted"}.") },
        confirmButton = { TextButton(onClick = {
            confirm = false
            perform {
                val result = service.publish(source, state, candidate, rooms) {
                    val check = requireNotNull(model.account).validate(AcquisitionLogin(candidate.url, candidate.username, candidate.password))
                    try { require(check.accountId == candidate.accountId && check.role == candidate.role) { "Account changed. Test it again before publishing." } }
                    finally { model.account?.revoke(check) }
                }
                model.clear(); expanded = false; result
            }
        }) { Text("Publish account") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
    if (disable) FocusRestoringAlertDialog(onDismissRequest = { disable = false }, title = { Text("Disable shared access?") },
        text = { Text("Replace the live record with an inactive one. Existing MusicGrabber jobs may continue. Reset the dedicated account's password in MusicGrabber to revoke copied credentials and sessions.") },
        confirmButton = { TextButton(onClick = { disable = false; perform { service.unpublish(source, state) } }) { Text("Disable shared access") } },
        dismissButton = { TextButton(onClick = { disable = false }) { Text("Cancel") } })
}

@Composable internal fun SharedRecipientSettings(vm: HarmonicastViewModel, acquisition: AcquisitionCoordinator) {
    val shared = acquisition.shared ?: return
    val state by shared.state.collectAsState()
    val scope = rememberCoroutineScope()
    val source = vm.sharedSetupSource()
    LaunchedEffect(source) { shared.refresh(true) }
    Text("Shared music acquisition", style = MaterialTheme.typography.titleMedium)
    SettingsDescription("Connection supplied by the owner of ${source?.serverName.orEmpty()} for ${source?.libraryName.orEmpty()}.")
    Text(state.message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    if (state.available) {
        SettingsDescription("${state.url}\nAccount: ${state.username}\nRoom acquisition: ${if (state.rooms) "permitted by the owner" else "not permitted"}")
    }
    Box(Modifier.fillMaxWidth().height(4.dp)) { if (state.checking) LinearProgressIndicator(Modifier.fillMaxWidth()) }
    Button(onClick = { scope.launch { if (state.optedOut) shared.reconnect() else shared.refresh(true) } }, enabled = !state.checking,
        modifier = Modifier.tvFocusFeedback()) { Text(if (state.optedOut) "Reconnect shared access" else "Refresh shared access") }
    if (!state.optedOut) TextButton(onClick = { scope.launch { shared.optOut(); acquisition.roomAllowed.value = false } },
        enabled = !state.checking, modifier = Modifier.tvFocusFeedback()) { Text("Disconnect shared access on this device") }
    SettingsDescription("Plex access is checked before each new request. Downloads already accepted by MusicGrabber may continue if access is removed.")
}

@Composable internal fun SharedAccountReview(source: PersonalPlexSource, state: SharedPlexPreparation,
    candidate: AcquisitionConnection, rooms: Boolean, destinationConfirmed: Boolean, busy: Boolean,
    onRooms: (Boolean) -> Unit, onDestination: (Boolean) -> Unit, onReview: () -> Unit) {
        Text("Review shared account", style = MaterialTheme.typography.titleMedium)
        SettingsDescription("${source.serverName} → ${source.libraryName}\nConfiguration: ${state.libraryName} / ${state.albumName}\n${candidate.url}\nAccount: ${candidate.username} (${candidate.role})")
        SettingsDescription("MusicGrabber path example: ${candidate.destination.ifBlank { "not reported" }}. Album routing may use a different folder. Confirm the actual download appears in ${source.libraryName} in Plex.")
        SettingsToggle("Downloads reach this music library", "I have verified this account's download destination and Plex import/scan into ${source.libraryName}.", destinationConfirmed, !busy, onDestination)
        SettingsToggle("Permit room acquisition", "Approved shared hosts may enable acquisition for their nearby guests. Each room still starts with acquisition off.", rooms, !busy, onRooms)
        SettingsDescription("Plex recipients can read this password, use the account outside Harmonicast, share its job history, and change its password. Removing Plex access stops fresh app reads; rotate the MusicGrabber password to revoke copied credentials.")
        Button(onClick = onReview, enabled = !busy && destinationConfirmed, modifier = Modifier.tvFocusFeedback()) { Text("Review publication") }
}
