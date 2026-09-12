package io.github.sneedster.harmonicast

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

internal fun roomSummary(vm: HarmonicastViewModel): String = when {
    HarmonicastMediaService.roomShareState.value.checkingAccess -> "Checking Plex access…"
    vm.nearbyRoomState.connected -> "Joined room ${vm.nearbyRoomState.roomCode}"
    HarmonicastMediaService.roomShareState.value.enabled -> "Hosting room ${HarmonicastMediaService.roomShareState.value.roomCode}"
    else -> "Join or host a listening room"
}

internal fun shareText(context: Context, text: String, title: String) {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }, title))
}

@Composable internal fun RoomsScreen(vm: HarmonicastViewModel, onBack: () -> Unit, isActive: Boolean = true) {
    val context = LocalContext.current
    val room = HarmonicastMediaService.roomShareState.value
    val joined = vm.nearbyRoomState
    val television = isTvDevice()
    var sharing by rememberSaveable { mutableStateOf<String?>(null) }
    val hostPermissions = remember { bluetoothPermissions(advertise = true) }
    val joinPermissions = remember { bluetoothPermissions(advertise = false) }
    fun granted(permissions: Array<String>) = permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    val hostPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (hostPermissions.all { result[it] == true || ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) vm.setGuestControl(true)
        else vm.error = "Nearby devices permission is required to open a Bluetooth room"
    }
    val joinPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (joinPermissions.all { result[it] == true || ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) vm.scanNearbyRoom()
        else vm.error = "Nearby devices permission is required to find a room"
    }
    BackHandler(isActive) { onBack() }
    LaunchedEffect(room.enabled) { if (!room.enabled) sharing = null }
    TvFocusPage("rooms", isActive) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            SettingsHeading("Rooms", onBack)
            SettingsDescription(roomSummary(vm))
            when {
                joined.connected -> {
                    Text("Room ${joined.roomCode}", style = MaterialTheme.typography.titleLarge)
                    if (vm.plexAccess.canOfferPlayback) {
                        if (vm.offeringRoomPlayback) {
                            SettingsDescription(NativePlaybackReceiver.state.value.message)
                            OutlinedButton(onClick = vm::stopOfferingRoomPlayback, enabled = !joined.busy, modifier = Modifier.tvFocusFeedback()) { Text("Stop playing here") }
                        } else {
                            SettingsDescription("Let the host play here. Both devices need the same Wi-Fi for audio.")
                            Button(onClick = vm::offerRoomPlayback, enabled = !joined.busy, modifier = Modifier.tvFocusFeedback()) { Text("Offer this device as player") }
                        }
                    }
                    OutlinedButton(onClick = vm::leaveNearbyRoom, modifier = Modifier.tvFocusFeedback()) { Text("Leave room") }
                }
                room.enabled -> {
                    Text("Room ${room.roomCode}", style = MaterialTheme.typography.headlineSmall)
                    SettingsDescription(if (room.nearbyAvailable) "Bluetooth room is ready" else "Bluetooth is unavailable; same-Wi-Fi access still works")
                    RoomAcquisitionToggle(vm)
                    Text("Room playback", style = MaterialTheme.typography.titleMedium)
                    if (HarmonicastMediaService.nativeOutputActive.value) {
                        Button(onClick = vm::takeBackPlayback, modifier = Modifier.tvFocusFeedback()) { Text("Play on this device") }
                    } else {
                        val devices = HarmonicastMediaService.roomPlaybackDevices.value
                        if (devices.isEmpty()) SettingsDescription("An app signed in to a Plex music library can join this room and offer to play the music.")
                        devices.forEach { (id, name) -> OutlinedButton(onClick = { vm.transferPlayback(id) }, modifier = Modifier.tvFocusFeedback()) { Text("Play on $name") } }
                    }
                    if (HarmonicastMediaService.nativeOutputStatus.value.isNotBlank()) SettingsDescription(HarmonicastMediaService.nativeOutputStatus.value)
                    HorizontalDivider()
                    Button(onClick = { sharing = "guests" }, modifier = Modifier.tvFocusFeedback()) { Text("Invite guests") }
                    OutlinedButton(onClick = { sharing = "display" }, modifier = Modifier.tvFocusFeedback()) { Text("Open room display") }
                    SettingsDescription("Guests can search, request tracks, follow the queue, and vote. Plex credentials and host controls stay on this device.")
                    SettingsDescription("Closes after 30 minutes without guest activity, or after 4 hours total. Leaving this page keeps the room open.")
                    OutlinedButton(onClick = { vm.setGuestControl(false) }, modifier = Modifier.tvFocusFeedback()) { Text("End room") }
                }
                else -> {
                    Text("Join a room", style = MaterialTheme.typography.titleLarge)
                    SettingsDescription("Find a nearby host to request music or offer this device for playback.")
                    Button(onClick = { if (granted(joinPermissions)) vm.scanNearbyRoom() else joinPermissionLauncher.launch(joinPermissions) }, enabled = !joined.scanning, modifier = Modifier.tvFocusFeedback()) {
                        Text(if (joined.scanning) "Looking for nearby rooms…" else "Join nearby room")
                    }
                    if (joined.availableRooms.size > 1) joined.availableRooms.forEach { code -> OutlinedButton(onClick = { vm.joinNearbyRoom(code) }, modifier = Modifier.tvFocusFeedback()) { Text("Room $code") } }
                    if (joined.error.isNotBlank()) Text(joined.error, color = MaterialTheme.colorScheme.error)
                    HorizontalDivider()
                    Text("Host a room", style = MaterialTheme.typography.titleLarge)
                    if (vm.plexAccess.canHostRoom) {
                        SettingsDescription("Play your Plex music together. Nearby phones can browse, request, vote, and follow the queue without an account.")
                        RoomAcquisitionToggle(vm)
                        Button(onClick = { if (granted(hostPermissions)) vm.setGuestControl(true) else hostPermissionLauncher.launch(hostPermissions) }, enabled = !room.checkingAccess, modifier = Modifier.tvFocusFeedback()) { Text(if (television) "Open room on this TV" else "Open room") }
                    } else SettingsDescription("Sign in to Plex and choose a music library to host a room. Shared libraries can host too.")
                }
            }
            if (room.error.isNotBlank()) Text(room.error, color = MaterialTheme.colorScheme.error)
        }
    }
    if (sharing != null && room.enabled) {
        val display = sharing == "display"
        val url = if (display) room.displayUrl else room.joinUrl
        val title = if (display) "Open room display" else "Invite guests"
        FocusRestoringAlertDialog(onDismissRequest = { sharing = null }, title = { Text(title) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (display) {
                    SettingsDescription("On the other device, connect to the same Wi-Fi and type this address in its browser:")
                    Text(room.displayEntryUrl, style = MaterialTheme.typography.titleMedium)
                    SettingsDescription("Then enter this display code:")
                    Text(room.displayEntryCode, style = MaterialTheme.typography.headlineMedium)
                    SettingsDescription("Keep this code private: it grants browsing, queue, and playback controls until the room closes.")
                    HorizontalDivider()
                }
                SettingsDescription(if (display) "Or scan this QR code to open the display." else "Guests on the same Wi-Fi can scan this code to browse, request, and vote in a browser. Installing the app is optional.")
                Box(Modifier.widthIn(max = 260.dp).align(Alignment.CenterHorizontally)) { RoomQrCode(url, "$title for room ${room.roomCode}") }
                SettingsDescription("Plex credentials and app settings are not shared.")
            } },
            confirmButton = { TextButton(onClick = { sharing = null }, modifier = Modifier.tvFocusFeedback()) { Text("Close") } },
            dismissButton = { TextButton(onClick = { shareText(context, url, title) }, modifier = Modifier.tvFocusFeedback()) { Text(if (display) "Share display link" else "Share guest link") } })
    }
}
