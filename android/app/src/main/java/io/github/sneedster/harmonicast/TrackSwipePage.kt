package io.github.sneedster.harmonicast

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/** Project a release with constant deceleration; distances and velocity are in pixels. */
internal fun trackSwipeDestination(offset: Float, velocity: Float, width: Float, density: Float): Int {
    if (width <= 0f) return 0
    val projected = offset + velocity * abs(velocity) / (2f * 2400f * density)
    val destination = if (abs(offset) >= width / 2f) offset else projected
    return when {
        destination <= -width / 2f -> -1
        destination >= width / 2f -> 1
        else -> 0
    }
}

/** Translate the whole player while leaving its touch surface stationary. */
@Composable internal fun TrackSwipePage(
    trackId: String?,
    canNext: Boolean,
    canPrevious: Boolean,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    content: @Composable () -> Unit,
) {
    var width by remember { mutableFloatStateOf(0f) }
    var offset by remember { mutableFloatStateOf(0f) }
    var settling by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val currentTrack by rememberUpdatedState(trackId)
    val next by rememberUpdatedState(onNext)
    val previous by rememberUpdatedState(onPrevious)
    Box(Modifier.fillMaxSize().clipToBounds().onSizeChanged { width = it.width.toFloat() }
        .pointerInput(canNext, canPrevious, width) {
            val tracker = VelocityTracker()
            var accepted = false
            suspend fun settle(destination: Float, velocity: Float) {
                val animation = Animatable(offset)
                animation.animateTo(destination, spring(dampingRatio = 1f, stiffness = 220f), initialVelocity = velocity) {
                    offset = value
                }
            }
            fun release(cancelled: Boolean) {
                if (!accepted) return
                accepted = false
                settling = true
                val velocity = if (cancelled) 0f else tracker.calculateVelocity().x
                val direction = if (cancelled) 0 else trackSwipeDestination(offset, velocity, width, density)
                val allowed = (direction < 0 && canNext) || (direction > 0 && canPrevious)
                scope.launch {
                    try {
                        if (allowed) {
                            val oldTrack = currentTrack
                            settle(direction * width, velocity)
                            if (direction < 0) next() else previous()
                            // Playback changes asynchronously. Keep the outgoing track offscreen
                            // until its replacement is ready; Previous may also restart this track.
                            val changed = withTimeoutOrNull(1000) {
                                snapshotFlow { currentTrack }.first { it != oldTrack }
                            } != null
                            if (changed) offset = -direction * width
                        }
                        settle(0f, if (allowed) 0f else velocity)
                    } finally {
                        offset = 0f
                        settling = false
                    }
                }
            }
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (settling || currentTrack == null || (!canNext && !canPrevious)) return@awaitEachGesture
                tracker.resetTracking()
                tracker.addPosition(down.uptimeMillis, down.position)
                fun dragBy(amount: Float) {
                    offset = (offset + amount).coerceIn(if (canNext) -width else 0f, if (canPrevious) width else 0f)
                }
                val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                    change.consume()
                    tracker.addPosition(change.uptimeMillis, change.position)
                    dragBy(overSlop)
                }
                if (drag != null) {
                    accepted = true
                    val completed = horizontalDrag(drag.id) { change ->
                        tracker.addPosition(change.uptimeMillis, change.position)
                        dragBy(change.positionChange().x)
                        change.consume()
                    }
                    // Include finger-up time, so a pause before releasing loses its momentum.
                    currentEvent.changes.firstOrNull { it.id == drag.id }?.let {
                        tracker.addPosition(it.uptimeMillis, it.position)
                    }
                    release(cancelled = !completed)
                }
            }
        }) {
        Box(Modifier.fillMaxSize().graphicsLayer { translationX = offset }) { content() }
    }
}
