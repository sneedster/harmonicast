package io.github.sneedster.harmonicast

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale
import kotlinx.coroutines.launch

internal enum class SettingsCategory(val title: String, val icon: ImageVector) {
    APPEARANCE("Appearance", Icons.Default.Palette),
    PLAYBACK("Playback", Icons.Default.PlayCircle),
    MIX("Automatic mix", Icons.Default.Shuffle),
    RATINGS("Automatic ratings", Icons.Default.Star),
    PLEX("Plex account", Icons.Default.AccountCircle),
    ABOUT("About & updates", Icons.Default.Info),
}

@Composable internal fun SettingsScreen(
    vm: HarmonicastViewModel,
    onBack: (() -> Unit)? = null,
    onRooms: (() -> Unit)? = null,
    session: Int = 0,
    isActive: Boolean = true,
) {
    var selectedName by rememberSaveable { mutableStateOf<String?>(null) }
    var lastCategoryName by rememberSaveable { mutableStateOf(SettingsCategory.APPEARANCE.name) }
    var appliedSession by rememberSaveable { mutableIntStateOf(session) }
    val hubScroll = rememberScrollState()
    val detailBack = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var localRooms by rememberSaveable { mutableStateOf(false) }
    var detailFocused by remember { mutableStateOf(false) }
    val television = isTvDevice()
    val categories = remember { SettingsCategory.entries.associateWith { FocusRequester() } }
    val stateHolder = rememberSaveableStateHolder()
    val updates: AppUpdateViewModel = viewModel()
    if (localRooms) {
        RoomsScreen(vm, onBack = { localRooms = false }, isActive = isActive)
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        val selected = selectedName?.let(SettingsCategory::valueOf)
        val active = selected ?: SettingsCategory.valueOf(lastCategoryName)
        LaunchedEffect(session) {
            if (appliedSession != session) {
                if (!wide) selectedName = null
                appliedSession = session
            }
        }
        fun back() {
            if (!wide && selected != null) selectedName = null
            else if (wide && detailFocused) categories.getValue(active).requestFocus()
            else onBack?.invoke()
        }
        BackHandler(isActive && ((!wide && selected != null) || (wide && detailFocused) || onBack != null)) { back() }
        Row(Modifier.fillMaxSize()) {
            if (wide || selected == null) {
                TvFocusPage("settings-hub", isActive) {
                    Column(
                        (if (wide) Modifier.width(240.dp) else Modifier.fillMaxWidth())
                            .fillMaxHeight().verticalScroll(hubScroll).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SettingsHeading("Settings", onBack)
                        SettingsDescription("Make Harmonicast feel like yours.")
                        Spacer(Modifier.height(8.dp))
                        SettingsCategory.entries.forEach { category ->
                            val summary = when (category) {
                                SettingsCategory.APPEARANCE -> vm.colorSchemeName
                                SettingsCategory.PLAYBACK -> if (television) "Playback on this TV" else if (vm.keepScreenOnWhileCharging) "Stay awake while charging" else "Screen & background playback"
                                SettingsCategory.MIX -> "${vm.ratedTrackShare} rated / ${10 - vm.ratedTrackShare} unrated · ${selectionLabels[vm.musicTuning.selection]}"
                                SettingsCategory.RATINGS -> if (!vm.automaticPlexRatings) "Off" else if (vm.musicTuning.defaultRatings) "On · Default tuning" else "On · Custom tuning"
                                SettingsCategory.PLEX -> vm.plexSourceLabel.ifBlank { "Connect your music library" }
                                SettingsCategory.ABOUT -> "Version ${BuildConfig.VERSION_NAME}" + if (updates.release != null) " · Update available" else ""
                            }
                            Surface(
                                onClick = {
                                    lastCategoryName = category.name
                                    selectedName = category.name
                                    if (television) scope.launch { withFrameNanos { }; detailBack.requestFocus() }
                                },
                                color = if (wide && active == category) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.fillMaxWidth().focusRequester(categories.getValue(category)).tvFocusFeedback(),
                            ) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Icon(category.icon, null, tint = MaterialTheme.colorScheme.primary)
                                    Column(Modifier.weight(1f)) {
                                        Text(category.title, style = MaterialTheme.typography.titleMedium)
                                        Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (!wide) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                                }
                            }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        TextButton(onClick = { if (onRooms != null) onRooms() else localRooms = true }, modifier = Modifier.fillMaxWidth().tvFocusFeedback()) {
                            Icon(Icons.Default.Sensors, null)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Rooms")
                                Text(roomSummary(vm), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    LaunchedEffect(wide, selected == null, isActive) {
                        if (isActive && television && selected == null) {
                            withFrameNanos { }
                            categories.getValue(active).requestFocus()
                        }
                    }
                }
            }
            if (wide) VerticalDivider()
            if (wide || selected != null) {
                stateHolder.SaveableStateProvider(active.name) {
                    TvFocusPage("settings-${active.name}", isActive) {
                        Column(Modifier.weight(1f).fillMaxHeight().onFocusChanged { detailFocused = it.hasFocus }.focusGroup()
                            .verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            SettingsHeading(active.title, {
                                if (wide) categories.getValue(active).requestFocus() else selectedName = null
                            }, detailBack)
                            when (active) {
                                SettingsCategory.APPEARANCE -> AppearanceSettings(vm)
                                SettingsCategory.PLAYBACK -> PlaybackSettings(vm)
                                SettingsCategory.MIX -> MixSettings(vm)
                                SettingsCategory.RATINGS -> RatingSettings(vm)
                                SettingsCategory.PLEX -> PlexAccountSettings(vm)
                                SettingsCategory.ABOUT -> AboutSettings()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable internal fun SettingsHeading(title: String, onBack: (() -> Unit)? = null, backRequester: FocusRequester? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onBack != null) TextButton(onClick = onBack, modifier = (backRequester?.let { Modifier.focusRequester(it) } ?: Modifier).tvFocusFeedback()) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
            Spacer(Modifier.width(6.dp)); Text("Back")
        }
        Text(title, style = MaterialTheme.typography.headlineMedium)
    }
}

@Composable internal fun SettingsDescription(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun AppearanceSettings(vm: HarmonicastViewModel) {
    SettingsDescription("Choose a color scheme for this device. Your choice applies throughout Harmonicast.")
    PlayerPalette.entries.forEach { palette ->
        val colors = playerColors(palette.name)
        Surface(onClick = { vm.selectColorScheme(palette.name) }, shape = MaterialTheme.shapes.medium,
            color = if (vm.colorSchemeName == palette.name) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().tvFocusFeedback()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(color = colors.primary, shape = MaterialTheme.shapes.small, modifier = Modifier.size(32.dp)) {}
                Text(palette.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (vm.colorSchemeName == palette.name) Icon(Icons.Default.Check, "Selected ${palette.name}")
            }
        }
    }
}

@Composable private fun PlaybackSettings(vm: HarmonicastViewModel) {
    val context = LocalContext.current
    if (isTvDevice()) {
        SettingsDescription("This TV manages its own screen and background playback policies. Charging controls apply only to phones and tablets.")
    } else {
        SettingsToggle("Stay awake while charging", "Keep the screen on while Harmonicast is open and connected to power.", vm.keepScreenOnWhileCharging, true, vm::updateKeepScreenOnWhileCharging)
        HorizontalDivider()
        Text("Keep music playing", style = MaterialTheme.typography.titleMedium)
        SettingsDescription("If music stops with the screen off, allow Harmonicast to run without battery optimization in Android settings.")
        OutlinedButton(onClick = { context.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }, modifier = Modifier.tvFocusFeedback()) { Text("Background playback settings") }
    }
}

@Composable internal fun SettingsToggle(title: String, description: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(checked, change, enabled = enabled, modifier = Modifier.tvFocusFeedback().semantics { contentDescription = title })
        }
        SettingsDescription(description)
    }
}

private val strengthLabels = listOf("Off", "Half", "Normal", "Strong", "Double")
private val selectionLabels = listOf("Equal chance", "Mild", "Normal", "Strong", "Very strong")

@Composable internal fun SteppedSetting(title: String, description: String, value: Int, labels: List<String>, enabled: Boolean, change: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        SettingsDescription(description)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedIconButton(onClick = { change(value - 1) }, enabled = enabled && value > 0, modifier = Modifier.tvFocusFeedback()) { Icon(Icons.Default.Remove, "Decrease $title") }
            Text(labels[value], Modifier.weight(1f).semantics { contentDescription = "$title: ${labels[value]}" }, style = MaterialTheme.typography.titleSmall)
            OutlinedIconButton(onClick = { change(value + 1) }, enabled = enabled && value < labels.lastIndex, modifier = Modifier.tvFocusFeedback()) { Icon(Icons.Default.Add, "Increase $title") }
        }
    }
}

@Composable private fun MixSettings(vm: HarmonicastViewModel) {
    val tuning = vm.musicTuning
    SteppedSetting("Avoid recent repeats", "Keep songs out of the automatic mix after they were played or skipped. Uses Plex last-played dates plus listening on this device.",
        ReplayWindow.DAY_OPTIONS.indexOf(vm.replayWindowDays), ReplayWindow.LABELS, vm.isPersonalMode && vm.isHost) { vm.saveReplayWindow(ReplayWindow.DAY_OPTIONS[it]) }
    SettingsDescription("Default: 1 week. Applies to automatic songs already queued as they come up. Explicit requests, Track Radio, and Previous still work. Skips in other apps are only known when Plex records them.")
    if (vm.automaticMixStatus.isNotBlank()) SettingsDescription(vm.automaticMixStatus)
    HorizontalDivider()
    SteppedSetting("Rated-track share", "Choose how many of every ten automatic picks come from rated tracks. The rest explore unrated tracks.",
        vm.ratedTrackShare, (0..10).map { when (it) { 0 -> "All unrated"; 10 -> "All rated"; else -> "$it rated · ${10-it} unrated" } }, vm.isHost && !vm.settingsSaving) { vm.saveRatedTrackShare(it, announce = false) }
    HorizontalDivider()
    SteppedSetting("Prefer higher ratings", "Choose how strongly higher ratings are favored within each candidate pool. Equal chance gives every candidate the same weight.", tuning.selection, selectionLabels, vm.isHost && vm.isPersonalMode) { vm.saveMusicTuning(tuning.copy(selection = it)) }
    SettingsDescription("Changes apply to the next automatic batch. Tracks already queued stay in place. Track Radio and manual requests use their existing order.")
    SettingsDescription("Rated candidates above 1 remain eligible. This preference is separate from the rated/unrated balance and works even when automatic Plex ratings are off.")
    val ratio = selectionWeight(8.0, tuning) / selectionWeight(4.0, tuning)
    SettingsDescription("Example: a track rated 8 has ${decimal(ratio)}× the selection weight of a track rated 4 in the same pool. These are relative weights, not guaranteed library-wide probabilities.")
    OutlinedButton(onClick = { vm.saveMusicTuning(tuning.copy(selection = 2)) }, enabled = vm.isHost && vm.isPersonalMode && tuning.selection != 2, modifier = Modifier.tvFocusFeedback()) { Text("Restore selection preference") }
    if (!vm.isHost) SettingsDescription("Only the host can change automatic mix settings.")
}

@Composable private fun RatingSettings(vm: HarmonicastViewModel) {
    val tuning = vm.musicTuning
    val enabled = vm.isPersonalMode && vm.canWriteToPlex && vm.automaticPlexRatings && vm.isHost
    SettingsToggle("Enable automatic rating changes", "Let completions and skips adjust your Plex song ratings from listening on this device. Off by default.", vm.automaticPlexRatings, vm.isPersonalMode && vm.canWriteToPlex, vm::saveAutomaticPlexRatings)
    SettingsDescription("These changes are saved to Plex, can replace ratings you set yourself, and are visible in other apps using the same account. Turning this off stops future automatic changes; it does not restore earlier ratings.")
    SettingsDescription("Explicit thumbs-up/down votes still change Plex ratings. Play counts and listening history continue to be recorded.")
    if (!vm.canWriteToPlex) SettingsDescription("Automatic ratings are unavailable on a shared read-only source. Sign in with an owner account to use them.")
    else if (!vm.automaticPlexRatings) SettingsDescription("Enable automatic rating changes to adjust the controls below. Your saved tuning is retained while off.")
    HorizontalDivider()
    SteppedSetting("Completion boost", "How much finishing a song raises its rating.", tuning.completion, strengthLabels, enabled) { vm.saveMusicTuning(tuning.copy(completion = it)) }
    SteppedSetting("Skip penalty", "How much skipping lowers a rating. Later skips receive smaller penalties.", tuning.skip, strengthLabels, enabled) { vm.saveMusicTuning(tuning.copy(skip = it)) }
    SteppedSetting("Repeat-play influence", "How Plex's recorded play count increases the completion boost. This uses Plex play count, not just plays on this device.", tuning.repeat, strengthLabels, enabled && tuning.completion != 0) { vm.saveMusicTuning(tuning.copy(repeat = it)) }
    if (tuning.completion == 0) SettingsDescription("Repeat-play influence is inactive while completion boost is Off.")
    var examples by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { examples = !examples }, modifier = Modifier.tvFocusFeedback()) { Text(if (examples) "Hide examples" else "Examples") }
    if (examples) {
        SettingsDescription("Starting from 5.0 / 10:")
        listOf("Finish · 0 Plex plays" to adjustPersonalRating(5.0, "complete", 1.0, 0, tuning),
            "Finish · 10 Plex plays" to adjustPersonalRating(5.0, "complete", 1.0, 10, tuning),
            "Skip at 25%" to adjustPersonalRating(5.0, "skip", .25, 0, tuning),
            "Skip at 75%" to adjustPersonalRating(5.0, "skip", .75, 0, tuning)).forEach { (label, rating) -> Text("$label → ${decimal(rating)} / 10") }
        SettingsDescription("Examples do not change Plex. Adjustments round to tenths and can produce no change. Unrated songs start from 5.0 when first adjusted; ratings stay between 0 and 10.")
    }
    OutlinedButton(onClick = { vm.saveMusicTuning(tuning.resetRatings()) }, enabled = enabled && !tuning.defaultRatings, modifier = Modifier.tvFocusFeedback()) { Text("Restore rating defaults") }
    SettingsDescription("Tuning is saved on this device. During playback transfer, the controlling host's tuning applies.")
}

private fun decimal(value: Double) = String.format(Locale.getDefault(), "%.1f", value)

@Composable private fun PlexAccountSettings(vm: HarmonicastViewModel) {
    val context = LocalContext.current
    var signOut by rememberSaveable { mutableStateOf(false) }
    if (!vm.isPersonalMode) {
        SettingsDescription("Connect a Plex account to play from your own music library on this device.")
        Button(onClick = { vm.beginPersonalSetup { CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(it)) } }, enabled = !vm.loading, modifier = Modifier.tvFocusFeedback()) { Text("Connect Plex") }
    } else {
        Text(vm.plexSourceLabel.ifBlank { "Personal Plex library" }, style = MaterialTheme.typography.titleMedium)
        if (!vm.canWriteToPlex) SettingsDescription("Shared read-only server · playback and local queues are available; Plex ratings and guest hosting are disabled.")
        Button(onClick = vm::beginPersonalSourceChange, enabled = !vm.loading, modifier = Modifier.tvFocusFeedback()) { Text("Change Plex server or library") }
        HorizontalDivider()
        TextButton(onClick = { signOut = true }, modifier = Modifier.tvFocusFeedback()) { Text("Sign out of Plex", color = MaterialTheme.colorScheme.error) }
    }
    if (signOut) FocusRestoringAlertDialog(onDismissRequest = { signOut = false }, title = { Text("Sign out of Plex?") },
        text = { Text("This removes the Plex account and source from this device and clears its saved queue and playback history.") },
        confirmButton = { TextButton(onClick = { signOut = false; vm.signOutPersonalPlex() }, modifier = Modifier.tvFocusFeedback()) { Text("Sign out", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { signOut = false }, modifier = Modifier.tvFocusFeedback()) { Text("Cancel") } })
}

@Composable private fun AboutSettings() {
    val context = LocalContext.current
    var share by rememberSaveable { mutableStateOf(false) }
    UpdateSettings()
    HorizontalDivider()
    SettingsDescription("Harmonicast · Your Plex music, together.")
    OutlinedButton(onClick = { share = true }, modifier = Modifier.tvFocusFeedback()) { Text("Share app") }
    if (share) FocusRestoringAlertDialog(onDismissRequest = { share = false }, title = { Text("Get Harmonicast for Android") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsDescription("Scan to visit harmonicast.app and download Harmonicast for Android.")
            Box(Modifier.widthIn(max = 220.dp).align(Alignment.CenterHorizontally)) { RoomQrCode("https://harmonicast.app", "Download Harmonicast for Android") }
            SettingsDescription("Open the downloaded APK and follow Android’s installation prompts. Browser guests can keep listening and requesting without installing the app.")
        } }, confirmButton = { TextButton(onClick = { share = false }, modifier = Modifier.tvFocusFeedback()) { Text("Close") } },
        dismissButton = if (!isTvDevice()) ({ TextButton(onClick = { shareText(context, "Get Harmonicast for Android: https://harmonicast.app", "Share Harmonicast") }, modifier = Modifier.tvFocusFeedback()) { Text("Share link") } }) else null)
}
