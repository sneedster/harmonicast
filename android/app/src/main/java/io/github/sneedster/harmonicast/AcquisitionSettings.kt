package io.github.sneedster.harmonicast

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalAutofillTree
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*

internal class AcquisitionSettingsModel : ViewModel() {
    var gateway by mutableStateOf<AcquisitionSetupGateway?>(null); private set
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var key by mutableStateOf("")
    var url by mutableStateOf("")
    var rememberLogin by mutableStateOf(true)
    var keyMode by mutableStateOf(false)
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf(""); private set
    private var operation: Job? = null
    fun run(action: suspend () -> Unit) {
        if (busy) return
        operation = viewModelScope.launch {
            busy = true; message = ""
            try { action() } catch (e: CancellationException) { throw e } catch (e: Exception) { message = safeAcquisitionError(e) }
            finally { busy = false }
        }
    }
    fun start(context: Context, account: MusicGrabberAccount) = run {
        gateway?.close()
        val next = AcquisitionSetupGateway(context.applicationContext, account)
        try { next.start(); gateway = next } catch (e: Exception) { next.close(); throw e }
    }
    fun closeSetup() { val old = gateway; gateway = null; if (old != null) viewModelScope.launch { old.close() } }
    fun clearSecrets() { password = ""; key = "" }
    override fun onCleared() {
        val old = gateway
        if (old != null) CoroutineScope(Dispatchers.IO).launch { old.close() }
        clearSecrets()
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable private fun LoginField(value: String, change: (String) -> Unit, title: String, secret: Boolean = false, username: Boolean = false) {
    val autofill = LocalAutofill.current
    val tree = LocalAutofillTree.current
    val currentChange by rememberUpdatedState(change)
    val node = remember(title) { AutofillNode(onFill = { currentChange(it) }, autofillTypes = when {
        username -> listOf(AutofillType.Username)
        secret && title == "Password" -> listOf(AutofillType.Password)
        else -> emptyList()
    }) }
    DisposableEffect(node) { tree += node; onDispose { tree.children.remove(node.id) } }
    RemoteTextField(value, change, Modifier.fillMaxWidth().onGloballyPositioned { node.boundingBox = it.boundsInWindow() }
        .onFocusChanged { if (it.hasFocus && node.autofillTypes.isNotEmpty()) autofill?.requestAutofillForNode(node) else autofill?.cancelAutofillForNode(node) },
        label = { Text(title) }, allowVoice = false,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else if (title.contains("URL")) KeyboardType.Uri else KeyboardType.Text))
    if (secret) {
        val context = LocalContext.current
        TextButton(onClick = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.let(change) }, modifier = Modifier.tvFocusFeedback()) { Text("Paste $title") }
    }
}

@Composable internal fun AcquisitionSettings(vm: HarmonicastViewModel, isActive: Boolean) {
    val context = LocalContext.current
    val acquisition = remember { AcquisitionRuntime.get(context) }
    val account = acquisition.account
    val state by account.state.collectAsState()
    val model: AcquisitionSettingsModel = viewModel(key = "acquisition-settings")
    var advanced by rememberSaveable { mutableStateOf(false) }
    val canConfigure = vm.isPersonalMode && vm.canWriteToPlex
    LaunchedEffect(Unit) {
        account.connection()?.let { model.url = it.url; model.username = if (it.apiKeyMode) "" else it.username; model.keyMode = it.apiKeyMode; model.rememberLogin = it.password.isNotBlank() }
        account.check()
    }
    LaunchedEffect(isActive, canConfigure) { if (!isActive || !canConfigure) { model.closeSetup(); model.clearSecrets() } }
    DisposableEffect(Unit) { onDispose { if ((context as? android.app.Activity)?.isChangingConfigurations != true) { model.closeSetup(); model.clearSecrets() } } }
    SettingsDescription("Connect your existing MusicGrabber service. Its URL must be reachable from this app, including through a VPN such as Tailscale.")
    if (!canConfigure) {
        SettingsDescription("Music acquisition requires an owner Plex library. Shared libraries support listening only.")
        return
    }
    SettingsDescription(state.message)
    if (state.configured) {
        SettingsDescription("${state.url}\n${state.username}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { model.run { account.check(true) } }, enabled = !model.busy, modifier = Modifier.tvFocusFeedback()) { Text("Test connection") }
            TextButton(onClick = { model.run { account.disconnect(); model.clearSecrets(); acquisition.roomAllowed.value = false } }, enabled = !model.busy, modifier = Modifier.tvFocusFeedback()) { Text("Disconnect") }
        }
    }
    val gateway = model.gateway
    if (gateway != null) {
        val setup by gateway.state.collectAsState()
        SettingsDescription(setup.message)
        if (setup.url.isNotBlank()) {
            Text(setup.url, style = MaterialTheme.typography.titleMedium)
            Text("Pairing code: ${setup.code}", style = MaterialTheme.typography.headlineSmall)
            Box(Modifier.size(180.dp)) { RoomQrCode(setup.url, "Computer setup address") }
        }
        setup.staged?.let { candidate ->
            Text("${candidate.url}\n${candidate.username}")
            Button(onClick = { model.run { gateway.save(); model.closeSetup() } }, enabled = !model.busy, modifier = Modifier.tvFocusFeedback()) { Text("Save connection") }
        }
        OutlinedButton(onClick = model::closeSetup, modifier = Modifier.tvFocusFeedback()) { Text("Close setup") }
    } else {
        Button(onClick = { model.start(context, account) }, enabled = !model.busy, modifier = Modifier.tvFocusFeedback()) { Text("Set up from another device") }
        Text("Or enter on this device", style = MaterialTheme.typography.titleMedium)
        LoginField(model.url, { model.url = it }, "MusicGrabber URL")
        if (!model.keyMode) {
            LoginField(model.username, { model.username = it }, "Username", username = true)
            LoginField(model.password, { model.password = it }, "Password", secret = true)
            SettingsToggle("Remember login on this device", "Save the login securely to reconnect when the session expires. When off, you will need to sign in again.", model.rememberLogin, !model.busy) { model.rememberLogin = it }
        }
        TextButton(onClick = { advanced = !advanced }, modifier = Modifier.tvFocusFeedback()) { Text("Advanced settings") }
        if (advanced || model.keyMode) {
            SettingsToggle("Use API key instead", "For services configured to use an API key.", model.keyMode, !model.busy) { model.keyMode = it; model.clearSecrets() }
            if (model.keyMode) LoginField(model.key, { model.key = it }, "API key", secret = true)
        }
        Button(onClick = { model.run {
            val candidate = account.validate(AcquisitionLogin(model.url, model.username, model.password, model.rememberLogin, model.keyMode, model.key))
            try { require(vm.isPersonalMode && vm.canWriteToPlex) { "An owner Plex library is required" }; account.save(candidate); model.clearSecrets() } catch (e: Exception) { account.revoke(candidate); throw e }
        } }, enabled = !model.busy && model.url.isNotBlank(), modifier = Modifier.tvFocusFeedback()) { Text("Connect") }
    }
    if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (model.message.isNotBlank()) Text(model.message, color = MaterialTheme.colorScheme.error)
    AcquisitionRequestList(acquisition)
}

@Composable internal fun AcquisitionRequestList(acquisition: AcquisitionCoordinator, participant: String? = null) {
    val requests by acquisition.requests.collectAsState()
    if (requests.any { participant == null || it.participant == participant }) {
        Text("Acquisition requests", style = MaterialTheme.typography.titleMedium)
        requests.filter { participant == null || it.participant == participant }.takeLast(10).reversed().forEach {
            Text("${it.recording.title} — ${it.message}", style = MaterialTheme.typography.bodyMedium)
            if (it.status == "unconfirmed") TextButton(onClick = { acquisition.dismissUnconfirmed(it.id) }, modifier = Modifier.tvFocusFeedback()) { Text("Dismiss after checking MusicGrabber") }
        }
    }
}
