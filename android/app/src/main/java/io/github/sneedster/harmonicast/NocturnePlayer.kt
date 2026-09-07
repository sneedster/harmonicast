package io.github.sneedster.harmonicast

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun NocturnePlayer(vm: HarmonicastViewModel, search: (String) -> Unit) {
    val song = vm.nowPlaying.song
    var details by remember(song?.id) { mutableStateOf(false) }
    BackHandler(details) { details = false }
    if (details && song != null) {
        ArtistDiscoveryPage(vm, song) { details = false }
        return
    }
    if (song == null) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            DisplayTitle("Make room for music.")
            Button(onClick = { vm.startRandomPlayback() }, enabled = vm.isActivePlayer) { Text("Start your mix") }
        }
        return
    }
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp)) {
        val wide = maxWidth >= 650.dp
        val artSize = if (wide) minOf(maxWidth * .42f, maxHeight - 32.dp).coerceAtLeast(100.dp)
            else minOf(maxWidth - 24.dp, maxHeight * .42f).coerceAtLeast(100.dp)
        val artwork: @Composable () -> Unit = {
            Crossfade(song, animationSpec = tween(280), label = "Now playing artwork") { track ->
                Box(Modifier.shadow(28.dp, RoundedCornerShape(24.dp), spotColor = colors.primary.copy(alpha = .4f))
                    .background(colors.surfaceVariant, RoundedCornerShape(24.dp))
                    .pointerInput(track.id, vm.isHost) {
                        var dx = 0f; var dy = 0f
                        detectDragGestures(onDragStart = { dx = 0f; dy = 0f }, onDrag = { change, amount ->
                            dx += amount.x; dy += amount.y; change.consume()
                        }, onDragEnd = {
                            if (dy < -80.dp.toPx() && -dy > kotlin.math.abs(dx)) { details = true; vm.loadArtistDiscovery(track) }
                            else if (dx < -80.dp.toPx() && vm.isHost) vm.nextSong()
                        })
                    }) { Cover(vm, track, artSize) }
            }
        }
        val information: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (vm.nowPlaying.isPlaying) "NOW PLAYING" else "PAUSED", color = colors.primary, letterSpacing = 3.sp, fontSize = 11.sp)
                Text(song.title, fontFamily = FontFamily.Serif, fontSize = if (wide) 36.sp else 27.sp, lineHeight = if (wide) 40.sp else 31.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { search(song.artist) }, contentPadding = PaddingValues(0.dp)) {
                    Text(song.artist, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (song.album.isNotBlank()) TextButton(onClick = { search(song.album) }, contentPadding = PaddingValues(0.dp)) { Text(song.album + (song.year?.let { " · $it" } ?: ""), color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(5) { index -> Icon(if (index < ((song.rating ?: 0.0) / 2).toInt()) Icons.Default.Star else Icons.Outlined.StarBorder,
                        if (index == 0) "Plex rating ${song.rating ?: 0.0} out of 10" else null, Modifier.size(17.dp), tint = colors.primary) }
                }
                PhonePlayerControls(vm, floating = true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { vm.queueSimilar() }, enabled = vm.isActivePlayer) { Icon(Icons.Default.Radio, null, Modifier.size(19.dp)); Text(" Track Radio") }
                    TextButton(onClick = { details = true; vm.loadArtistDiscovery(song) }) { Icon(Icons.Default.Info, null, Modifier.size(19.dp)); Text(" Discover") }
                }
            }
        }
        if (wide) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
            artwork()
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { information() }
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            artwork(); information()
        }
    }
}
