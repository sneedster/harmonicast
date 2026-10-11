package io.github.sneedster.harmonicast

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable internal fun MixPresetsContent(vm: HarmonicastViewModel) {
    var name by rememberSaveable { mutableStateOf("") }
    var remove by remember { mutableStateOf<MixPreset?>(null) }
    Text("Mix presets", style = MaterialTheme.typography.titleMedium)
    SettingsDescription("Save the balance, selection preference, repeat window, and discovery mode. A matching name replaces that preset. Rating consent and rating adjustments stay as they are.")
    val enabled = vm.isPersonalMode && vm.isHost && !vm.settingsSaving
    val builtIn = listOf(MixPreset("familiar", "Familiar favorites", 10, 3, 7),
        MixPreset("discovery", "Mostly undiscovered", 2, 1, 14), MixPreset("deep", "Deep cuts", 6, 0, 30, MixDiscovery.UNDERPLAYED))
    (builtIn + vm.mixPresets).forEach { preset ->
        Row(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { vm.applyMixPreset(preset) }, enabled = enabled, modifier = Modifier.weight(1f).tvFocusFeedback()) { Text(preset.name) }
            if (preset in vm.mixPresets) TextButton(onClick = { remove = preset }, enabled = enabled, modifier = Modifier.tvFocusFeedback()) { Text("Remove") }
        }
    }
    RemoteTextField(name, { name = it.take(40) }, label = { Text("Preset name") }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
    Button(onClick = { if (vm.saveMixPreset(name)) name = "" }, enabled = enabled && name.isNotBlank(), modifier = Modifier.tvFocusFeedback()) { Text("Save current mix") }
    HorizontalDivider()
    Text("Rediscovery", style = MaterialTheme.typography.titleMedium)
    MixDiscovery.entries.forEach { mode ->
        OutlinedButton(onClick = { vm.selectMixDiscovery(mode) }, enabled = enabled, modifier = Modifier.fillMaxWidth().tvFocusFeedback()) {
            Text((if (vm.mixDiscovery == mode) "✓ " else "") + mode.title)
        }
        SettingsDescription(mode.description)
    }
    SettingsDescription("Uses bounded library candidates and respects your repeat window. Applies to the next automatic batch; queued requests stay in place. Start your mix after clearing Artist Radio to switch from radio.")
    remove?.let { preset -> FocusRestoringAlertDialog(onDismissRequest = { remove = null }, title = { Text("Remove ${preset.name}?") },
        text = { Text("Your current mix settings will stay in place.") },
        confirmButton = { TextButton(onClick = { vm.removeMixPreset(preset.id); remove = null }, modifier = Modifier.tvFocusFeedback()) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { remove = null }, modifier = Modifier.tvFocusFeedback()) { Text("Cancel") } }) }
}

@Composable internal fun SoundProfilesContent(eq: EqualizerStore) {
    val context = LocalContext.current
    val store = remember(context) { SoundProfileStore(context) }
    val settings by eq.state.collectAsState()
    var profiles by remember { mutableStateOf(store.read()) }
    var name by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var remove by remember { mutableStateOf<SoundProfile?>(null) }
    Text("Sound profiles", style = MaterialTheme.typography.titleMedium)
    SettingsDescription("Save all ten bands, preamp, and bypass state for this device. Choose a profile when you switch speakers or headphones. A matching name replaces it.")
    profiles.forEach { profile -> Row(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { eq.update(profile.settings); status = "${profile.name} applied" }, modifier = Modifier.weight(1f).tvFocusFeedback()) { Text(profile.name) }
        TextButton(onClick = { remove = profile }, modifier = Modifier.tvFocusFeedback()) { Text("Remove") }
    } }
    RemoteTextField(name, { name = it.take(40) }, label = { Text("Sound profile name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Button(onClick = {
        try { store.save(name, settings); profiles = store.read(); status = "${name.trim()} saved"; name = ""; error = "" }
        catch (e: Exception) { error = e.message ?: "Could not save this profile" }
    }, enabled = name.isNotBlank(), modifier = Modifier.tvFocusFeedback()) { Text("Save current sound") }
    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    if (status.isNotBlank()) SettingsDescription(status)
    remove?.let { profile -> FocusRestoringAlertDialog(onDismissRequest = { remove = null }, title = { Text("Remove ${profile.name}?") },
        text = { Text("The current equalizer settings will stay in place.") },
        confirmButton = { TextButton(onClick = { try { store.remove(profile.name); profiles = store.read(); remove = null } catch (e: Exception) { error = e.message ?: "Could not remove this profile" } }, modifier = Modifier.tvFocusFeedback()) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { remove = null }, modifier = Modifier.tvFocusFeedback()) { Text("Cancel") } }) }
}

@Composable internal fun VisualizerSettingsContent() {
    val context = LocalContext.current
    val store = remember(context) { DemoSettings(context) }
    var scene by remember { mutableStateOf(store.scene) }
    var neon by remember { mutableStateOf(store.neon) }
    Text("TV visualizer", style = MaterialTheme.typography.titleMedium)
    SettingsDescription("Demoscene-inspired backgrounds on this TV's Now Playing screen. Music drives the motion. Playback controls stay in front. Off by default; Android's reduced-motion setting pauses animation.")
    DemoScene.entries.forEach { choice ->
        OutlinedButton(onClick = { scene = choice; store.scene = choice }, modifier = Modifier.fillMaxWidth().tvFocusFeedback()) { Text((if (scene == choice) "✓ " else "") + choice.title) }
    }
    SettingsToggle("Classic neon", "Use cyan and magenta instead of your selected palette.", neon, scene != DemoScene.OFF) { neon = it; store.neon = it }
}

@Composable internal fun OfflineScreen(vm: HarmonicastViewModel, back: () -> Unit) {
    val context = LocalContext.current
    val source = vm.personalSource
    val store = remember(context) { OfflineStore(context) }
    if (source == null) { Column(Modifier.padding(24.dp)) { SettingsHeading("Downloads", back); SettingsDescription("Connect a Plex library to download music.") }; return }
    key(OfflineStore.scope(source)) {
        val manager = remember(context) { WorkManager.getInstance(context) }
        val works by remember(source) { manager.getWorkInfosByTagFlow(OfflineStore.tag(source)) }.collectAsState(initial = emptyList())
        val cacheWorks by remember(source) { manager.getWorkInfosByTagFlow(OfflineStore.cacheTag(source)) }.collectAsState(initial = emptyList())
        val cacheSettings = remember { QueueCacheSettings(context) }
        var cacheEnabled by remember { mutableStateOf(cacheSettings.enabled) }
        var cacheCount by remember { mutableIntStateOf(cacheSettings.count) }
        var cacheMobile by remember { mutableStateOf(!cacheSettings.wifiOnly) }
        var cached by remember { mutableStateOf(store.cachedSongs(source)) }
        var cacheBytes by remember { mutableLongStateOf(store.cachedBytes(source)) }
        fun updateCache() {
            context.startService(android.content.Intent(context, HarmonicastMediaService::class.java).setAction(HarmonicastMediaService.CACHE_SETTINGS_ACTION))
        }
        LaunchedEffect(cacheWorks) { cached = store.cachedSongs(source); cacheBytes = store.cachedBytes(source) }
        var tracks by remember { mutableStateOf(store.songs(source)) }
        var bytes by remember { mutableLongStateOf(store.bytes(source)) }
        var wifi by remember { mutableStateOf(vm.downloadWifiOnly) }
        var remove by remember { mutableStateOf<Song?>(null) }
        var clear by remember { mutableStateOf(false) }
        LaunchedEffect(works) { tracks = store.songs(source); bytes = store.bytes(source) }
        LaunchedEffect(Unit) { while (true) { delay(2000); tracks = store.songs(source); bytes = store.bytes(source); cached = store.cachedSongs(source); cacheBytes = store.cachedBytes(source) } }
        val pending = works.filter { !it.state.isFinished }
        val failed = works.filter { it.state == WorkInfo.State.FAILED }
        LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { SettingsHeading("Downloads", back) }
            item {
                Text("Queue cache", style = MaterialTheme.typography.titleMedium)
                SettingsDescription("Keep the current track and upcoming queue ready for patchy reception. Replenishes while connected; older cached tracks rotate out. Saved downloads stay separate.")
                SettingsToggle("Preload queue", "Cache audio automatically on this playback device.", cacheEnabled, true) {
                    cacheEnabled = it; cacheSettings.enabled = it; updateCache()
                }
                SettingsToggle("Allow mobile data", "Top up the queue cache while driving. Uses your data allowance.", cacheMobile, cacheEnabled) {
                    cacheMobile = it; cacheSettings.wifiOnly = !it; updateCache()
                }
                Text("Cache window: $cacheCount tracks", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(5, 12, 25, 50).forEach { count ->
                        OutlinedButton(onClick = { cacheCount = count; cacheSettings.count = count; updateCache() }, enabled = cacheEnabled,
                            modifier = Modifier.weight(1f).tvFocusFeedback()) { Text((if (cacheCount == count) "✓ " else "") + count) }
                    }
                }
                val currentCacheWorks = store.currentCacheWorks(source, cacheWorks)
                val waiting = currentCacheWorks.count { !it.state.isFinished }
                SettingsDescription("${cached.size} cached · ${cacheBytes / (1024 * 1024)} MB of 512 MB" +
                    (if (waiting > 0) " · $waiting waiting or downloading" else "") + ". Saved tracks in the queue also play locally. Total offline storage is limited to 5 GB.")
                val failure = currentCacheWorks.firstOrNull { it.state == WorkInfo.State.FAILED }
                if (failure != null) {
                    SettingsDescription(failure.outputData.getString("error") ?: "Some tracks could not be preloaded. Reconnect to Plex and retry.")
                    TextButton(onClick = { updateCache() }, enabled = cacheEnabled, modifier = Modifier.tvFocusFeedback()) { Text("Retry queue cache") }
                }
                HorizontalDivider()
                Text("Saved downloads", style = MaterialTheme.typography.titleMedium)
            }
            item { SettingsDescription("${tracks.size} ${if (tracks.size == 1) "track" else "tracks"} · ${bytes / (1024 * 1024)} MB of 5 GB. Downloads stay on this device and work without Plex connectivity. Sign-out removes them. Audio only; artwork is not downloaded.") }
            item { SettingsToggle("Wi-Fi downloads only", "Wait for an unmetered connection. This applies to new downloads.", wifi, true) { wifi = it; vm.saveDownloadWifiOnly(it) } }
            item {
                Row {
                    Button(onClick = { vm.playOffline(tracks) }, enabled = tracks.isNotEmpty() && vm.isActivePlayer, modifier = Modifier.tvFocusFeedback()) { Text("Play downloads") }
                    TextButton(onClick = { store.cancel(source) }, enabled = pending.isNotEmpty(), modifier = Modifier.tvFocusFeedback()) { Text("Cancel downloads") }
                }
                if (pending.isNotEmpty()) SettingsDescription("${pending.size} downloads waiting or running. Wi-Fi-only downloads wait for an unmetered connection.")
                if (tracks.isEmpty() && pending.isEmpty()) SettingsDescription("Download an album, playlist, queued songs, or the current track to listen offline.")
            }
            items(pending, key = { "pending:${it.id}" }) { work ->
                Column {
                    Text(work.progress.getString("title") ?: "Track download")
                    val downloaded = work.progress.getLong("bytes", 0L)
                    val total = work.progress.getLong("total", -1L)
                    if (work.state == WorkInfo.State.RUNNING) {
                        if (total > 0) LinearProgressIndicator(progress = { (downloaded.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        SettingsDescription("Downloading · ${downloaded / (1024 * 1024)} MB")
                    } else SettingsDescription("Waiting for a connection or a download slot")
                    TextButton(onClick = { store.cancelWork(source, work) }, modifier = Modifier.tvFocusFeedback()) { Text("Cancel") }
                }
            }
            items(failed, key = { "failed:${it.id}" }) { work ->
                Column {
                    Text(work.outputData.getString("title") ?: "Track download")
                    Text(work.outputData.getString("error") ?: "Download failed. Reconnect to Plex and try again.", color = MaterialTheme.colorScheme.error)
                    Row {
                        TextButton(onClick = { vm.retryOffline(work.outputData.getString("track")); manager.pruneWork() }, enabled = work.outputData.getString("track") != null, modifier = Modifier.tvFocusFeedback()) { Text("Retry") }
                        TextButton(onClick = { manager.pruneWork() }, modifier = Modifier.tvFocusFeedback()) { Text("Dismiss finished downloads") }
                    }
                }
            }
            items(tracks, key = { it.id }) { track ->
                Column {
                    Text(track.title, style = MaterialTheme.typography.titleMedium)
                    SettingsDescription(track.artist)
                    Row {
                        TextButton(onClick = { vm.playOffline(listOf(track)) }, enabled = vm.isActivePlayer, modifier = Modifier.tvFocusFeedback()) { Text("Play") }
                        TextButton(onClick = { vm.add(track) }, enabled = vm.isActivePlayer, modifier = Modifier.tvFocusFeedback()) { Text("Queue") }
                        TextButton(onClick = { remove = track }, modifier = Modifier.tvFocusFeedback()) { Text("Remove download") }
                    }
                }
            }
            if (tracks.isNotEmpty()) item { TextButton(onClick = { clear = true }, modifier = Modifier.tvFocusFeedback()) { Text("Remove all downloads") } }
        }
        if (remove != null || clear) FocusRestoringAlertDialog(onDismissRequest = { remove = null; clear = false },
            title = { Text(if (clear) "Remove all downloads?" else "Remove ${remove!!.title}?") },
            text = { Text("Removes saved audio from this device. Your Plex library is unchanged. Stop offline playback before removing its audio.") },
            confirmButton = { TextButton(onClick = { if (clear) store.clear(source) else store.remove(source, remove!!.id); remove = null; clear = false; tracks = store.songs(source); bytes = store.bytes(source) }, modifier = Modifier.tvFocusFeedback()) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { remove = null; clear = false }, modifier = Modifier.tvFocusFeedback()) { Text("Cancel") } })
    }
}
