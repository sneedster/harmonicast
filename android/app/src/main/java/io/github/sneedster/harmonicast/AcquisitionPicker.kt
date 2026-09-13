package io.github.sneedster.harmonicast

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

internal data class CatalogLocation(val query: String, val mode: String = "search", val parent: String = "", val offset: Int = 0)
@Composable internal fun AcquisitionPicker(query: String, mode: String, close: () -> Unit,
    browse: suspend (CatalogLocation) -> CatalogPage, submit: suspend (String) -> JSONObject,
    status: suspend () -> JSONArray) {
    var location by remember { mutableStateOf(CatalogLocation(query, mode)) }
    val back = remember { mutableStateListOf<CatalogLocation>() }
    var page by remember { mutableStateOf(CatalogPage(emptyList())) }
    var busy by remember { mutableStateOf(false) }
    var lookupError by remember { mutableStateOf("") }
    var submissionError by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf<JSONObject?>(null) }
    var pending by remember { mutableStateOf<List<String>>(emptyList()) }
    var retry by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    fun goBack() { if (back.isEmpty()) close() else location = back.removeAt(back.lastIndex) }
    LaunchedEffect(location, retry) {
        busy = true; lookupError = ""; page = CatalogPage(emptyList())
        try { page = browse(location) } catch (e: CancellationException) { throw e } catch (e: Exception) { lookupError = safeAcquisitionError(e) }
        finally { busy = false }
    }
    LaunchedEffect(Unit) {
        while (true) {
            try { val a = status()
                val selectedId = submitted?.optString("id").orEmpty()
                if (selectedId.isNotBlank()) {
                    (0 until a.length()).map { a.getJSONObject(it) }
                        .firstOrNull { it.optString("id") == selectedId }?.let { submitted = it }
                }
                pending = (0 until a.length()).toList().takeLast(5).map {
                val j = a.getJSONObject(it); "${j.optJSONObject("recording")?.optString("title").orEmpty()} — ${j.optString("message")}" }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            delay(5000)
        }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler { goBack() }
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.95f), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(20.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { goBack() }, modifier = Modifier.tvFocusFeedback()) { Text("Back") }
                    TextButton(onClick = close, modifier = Modifier.tvFocusFeedback()) { Text("Close") }
                }
                Text("Find music", style = MaterialTheme.typography.headlineSmall)
                Text(location.query, style = MaterialTheme.typography.titleMedium)
                Text("Known library matches are marked. Other tracks are checked before downloading.", style = MaterialTheme.typography.bodySmall)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (lookupError.isNotBlank()) {
                    Text(lookupError)
                    TextButton(onClick = { retry++ }, modifier = Modifier.tvFocusFeedback()) { Text("Retry lookup") }
                }
                submitted?.let { request ->
                    Text("${request.optJSONObject("recording")?.optString("title").orEmpty()} — ${request.optString("message", "Request accepted")}")
                }
                if (submissionError.isNotBlank()) Text(submissionError)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!busy && page.entries.isEmpty() && lookupError.isBlank()) item { Text("No matching music found") }
                    items(page.entries, key = { it.id }) { entry ->
                        AcquisitionCatalogRow(entry, busy) {
                                    if (entry.kind == "recording") scope.launch {
                                        busy = true; submissionError = ""
                                        try { submitted = submit(entry.id) }
                                        catch (e: CancellationException) { throw e } catch (e: Exception) { submissionError = safeAcquisitionError(e) }
                                        finally { busy = false }
                                    } else {
                                        back += location
                                        location = CatalogLocation(entry.artist.ifBlank { entry.title }, if (entry.kind == "artist") "albums" else "tracks", entry.id)
                                    }
                        }
                    }
                    if (page.more) item { OutlinedButton(onClick = { back += location; location = location.copy(offset = page.offset + page.entries.size) }, enabled = !busy, modifier = Modifier.tvFocusFeedback()) { Text("More results") } }
                    items(pending) { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}

@Composable internal fun PersonalAcquisitionEntry(vm: HarmonicastViewModel) {
    val context = LocalContext.current
    val acquisition = remember { AcquisitionRuntime.get(context) }
    val state by acquisition.account.state.collectAsState()
    val grant = acquisition.shared?.state?.collectAsState()?.value
    var artist by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<String?>(null) }
    val term = vm.query.trim()
    LaunchedEffect(term, vm.searchLoading, vm.sharedSetupSource()) {
        artist = false; picker = null
        if (term.isNotBlank() && !vm.searchLoading) {
            if (acquisition.checkAccess()) {
                artist = try { vm.searchLibrary.artist(term)?.name?.equals(term, true) == true }
                catch (e: CancellationException) { throw e } catch (_: Exception) { false }
            }
        }
    }
    if (vm.plexAccess.canSubmitAcquisition && state.available && (vm.canWriteToPlex || grant?.available == true) && term.isNotBlank() && !vm.searchLoading) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            if (vm.results.isEmpty()) OutlinedButton(onClick = { picker = "search" }, modifier = Modifier.tvFocusFeedback()) { Text("Search connected music sources") }
            if (artist) OutlinedButton(onClick = { picker = "artist" }, modifier = Modifier.tvFocusFeedback()) { Text("Find songs by this artist") }
        }
    }
    picker?.let { mode -> AcquisitionPicker(term, mode, { picker = null },
        browse = { acquisition.browseCatalog(it.query, it.mode, it.parent, it.offset) },
        submit = { acquisition.submit(it).json(true) },
        status = { JSONArray().apply { acquisition.visible("Owner").forEach { put(it.json(true)) } } }) }
}

@Composable internal fun RoomAcquisitionToggle(vm: HarmonicastViewModel) {
    val context = LocalContext.current
    val acquisition = remember { AcquisitionRuntime.get(context) }
    val state by acquisition.account.state.collectAsState()
    val grant = acquisition.shared?.state?.collectAsState()?.value
    val allowed by acquisition.roomAllowed.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { acquisition.checkAccess() }
    if (vm.isPersonalMode && vm.plexAccess.canSubmitAcquisition && state.configured && (vm.canWriteToPlex || grant?.rooms == true)) {
        SettingsToggle("Allow music acquisition", "Let guests acquire missing tracks using this device's MusicGrabber connection. Accepted requests finish even if the room ends.", allowed, state.available || allowed) {
            scope.launch { try { acquisition.setRoomAllowed(it) } catch (e: Exception) { vm.error = safeAcquisitionError(e) } }
        }
        if (!state.available) {
            SettingsDescription(state.message)
            TextButton(onClick = { scope.launch { acquisition.checkAccess(true) } }, modifier = Modifier.tvFocusFeedback()) { Text("Retry connection") }
        }
    }
}

@Composable internal fun NearbyAcquisitionEntry(vm: HarmonicastViewModel, query: String) {
    var artist by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf<String?>(null) }
    val room = vm.nearbyRoomState
    var requested by remember(room.roomCode) { mutableStateOf(false) }
    var requestMessages by remember(room.roomCode) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(requested, mode, room.connected) {
        if (requested && mode == null && room.connected) while (true) {
            try {
                val result = vm.acquisitionNearby("acq-status")
                val rows = result.optJSONArray("items") ?: JSONArray()
                requestMessages = (0 until rows.length()).map { val item = rows.getJSONObject(it)
                    "${item.optJSONObject("recording")?.optString("title").orEmpty()} — ${item.optString("message")}" }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            delay(5000)
        }
    }
    requestMessages.forEach { Text(it) }
    LaunchedEffect(query, room.searchQuery, room.searchResults, room.acquisitionAvailable) {
        mode = null; artist = false
        if (query.isNotBlank() && query.trim() == room.searchQuery && room.acquisitionAvailable) {
            delay(500)
            artist = try { vm.acquisitionNearby("acq-entry", query).optBoolean("artist") } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
        }
    }
    if (room.acquisitionAvailable && query.isNotBlank() && query.trim() == room.searchQuery) {
        if (room.searchResults.isEmpty()) OutlinedButton(onClick = { mode = "search" }, modifier = Modifier.tvFocusFeedback()) { Text("Search connected music sources") }
        if (artist) OutlinedButton(onClick = { mode = "artist" }, modifier = Modifier.tvFocusFeedback()) { Text("Find songs by this artist") }
    }
    mode?.let { selected -> AcquisitionPicker(query, selected, { mode = null },
        browse = { CatalogPage.decode(vm.acquisitionNearby("acq-catalog", JSONObject().put("q", it.query).put("mode", it.mode).put("parent", it.parent).toString(), it.offset)) },
        submit = { requested = true; vm.acquisitionNearby("acq-submit", it) },
        status = {
            val all = JSONArray(); var offset = 0
            do { val result = vm.acquisitionNearby("acq-status", offset = offset++)
                val items = result.optJSONArray("items") ?: JSONArray()
                for (i in 0 until items.length()) all.put(items.getJSONObject(i))
            } while (result.optBoolean("more") && offset < 5)
            all
        }) }
}


@Composable internal fun AcquisitionCatalogRow(entry: CatalogEntry, busy: Boolean, choose: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(entry.title, style = MaterialTheme.typography.titleMedium)
            Text(listOf(entry.artist, entry.album, entry.year, entry.detail).filter { it.isNotBlank() }.joinToString(" · "))
            if (entry.inLibrary) Text("In your library", color = MaterialTheme.colorScheme.primary)
            Button(onClick = choose, enabled = !busy, modifier = Modifier.tvFocusFeedback()) {
                Text(if (entry.kind == "recording") (if (entry.inLibrary) "Queue existing track" else "Acquire track")
                    else if (entry.kind == "artist") "Browse releases" else "View tracks")
            }
        }
    }
}
