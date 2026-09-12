package io.github.sneedster.harmonicast

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.automirrored.filled.StarHalf
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
            Button(onClick = { vm.startRandomPlayback() }, enabled = vm.isActivePlayer, modifier = Modifier.tvFocusFeedback()) { Text("Start your mix") }
        }
        return
    }
    val colors = MaterialTheme.colorScheme
    TrackSwipePage(song.id, vm.isHost, vm.isActivePlayer, vm::nextSong, vm::previousSong) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val wide = maxWidth >= 480.dp && maxWidth > maxHeight
        val compact = wide && maxHeight < 380.dp
        val artSize = if (wide) minOf(maxWidth * .36f, maxHeight - if (compact) 56.dp else 16.dp).coerceAtLeast(64.dp)
            else minOf(maxWidth - 24.dp, maxHeight * .42f).coerceAtLeast(100.dp)
        val artwork: @Composable () -> Unit = {
            Crossfade(song, animationSpec = tween(280), label = "Now playing artwork") { track ->
                Box(Modifier.shadow(28.dp, RoundedCornerShape(24.dp), spotColor = colors.primary.copy(alpha = .4f))
                    .background(colors.surfaceVariant, RoundedCornerShape(24.dp))
                    .pointerInput(track.id, vm.isHost) {
                        var upward = 0f
                        detectVerticalDragGestures(onDragStart = { upward = 0f }, onVerticalDrag = { change, amount ->
                            upward -= amount; change.consume()
                        }, onDragEnd = {
                            if (upward > 80.dp.toPx()) { details = true; vm.loadArtistDiscovery(track) }
                        })
                    }) { Cover(vm, track, artSize) }
            }
        }
        val information: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 8.dp)) {
                if (!compact) Text(if (vm.nowPlaying.isPlaying) "NOW PLAYING" else "PAUSED", color = colors.primary, letterSpacing = 3.sp, fontSize = 11.sp)
                Text(song.title, fontFamily = FontFamily.Serif,
                    fontSize = if (compact) 24.sp else if (wide) 36.sp else 27.sp,
                    lineHeight = if (compact) 28.sp else if (wide) 40.sp else 31.sp,
                    minLines = if (compact) 1 else 2,
                    maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
                Text(song.artist, fontSize = if (compact) 16.sp else 18.sp,
                    color = colors.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().tvFocusFeedback().clickable { search(song.artist) }
                        .heightIn(min = 40.dp).wrapContentHeight(Alignment.CenterVertically))
                if (song.album.isNotBlank()) Text(song.album + (song.year?.let { " · $it" } ?: ""),
                    color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().tvFocusFeedback().clickable { search(song.album) }
                        .heightIn(min = 40.dp).wrapContentHeight(Alignment.CenterVertically))
                PlexRatingStars(song.rating)
            }
        }
        val extraActions: @Composable () -> Unit = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                if (compact) {
                    IconButton(onClick = { vm.queueSimilar() }, enabled = vm.isActivePlayer, modifier = Modifier.tvFocusFeedback()) { Icon(Icons.Default.Radio, "Track Radio") }
                    IconButton(onClick = { details = true; vm.loadArtistDiscovery(song) }, modifier = Modifier.tvFocusFeedback()) { Icon(Icons.Default.Info, "Discover") }
                } else {
                    TextButton(onClick = { vm.queueSimilar() }, enabled = vm.isActivePlayer, modifier = Modifier.tvFocusFeedback()) { Icon(Icons.Default.Radio, null, Modifier.size(19.dp)); Text(" Track Radio") }
                    TextButton(onClick = { details = true; vm.loadArtistDiscovery(song) }, modifier = Modifier.tvFocusFeedback()) { Icon(Icons.Default.Info, null, Modifier.size(19.dp)); Text(" Discover") }
                }
            }
        }
        val controls: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth()) {
                PhonePlayerControls(vm, floating = true, compact = compact)
                if (!compact) extraActions()
            }
        }
        if (wide) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(if (compact) 20.dp else 36.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(artSize), horizontalAlignment = Alignment.CenterHorizontally) {
                artwork()
                if (compact) extraActions()
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 16.dp)) {
                // Keep transport visible; long metadata or large fonts can scroll independently.
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) { information() }
                controls()
            }
        } else Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Transport and discovery actions own their space. Artwork and metadata
            // can scroll on shorter screens without pushing buttons below navigation.
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                artwork()
                information()
            }
            controls()
        }
    }
    }
}

/** Plex stores tenths on a ten-point scale; votes move one point (half a star). */
@Composable internal fun PlexRatingStars(rating: Double?, modifier: Modifier = Modifier) {
    val value = (rating ?: 0.0).coerceIn(0.0, 10.0)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(5) { index ->
            val icon = when {
                value >= (index + 1) * 2 -> Icons.Default.Star
                value >= index * 2 + 1 -> Icons.AutoMirrored.Filled.StarHalf
                else -> Icons.Outlined.StarBorder
            }
            Icon(icon, if (index == 0) "Plex rating $value out of 10" else null,
                Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text(if (rating == null) "Unrated" else String.format(java.util.Locale.ROOT, "%.1f / 10", value),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
