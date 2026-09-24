package io.github.sneedster.harmonicast

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable internal fun EqualizerSettings() {
    val context = LocalContext.current
    val store = remember(context) { EqualizerStore.get(context) }
    val settings by store.state.collectAsState()
    EqualizerContent(settings, store::update)
}

/** Fixed octave bands: no editable frequencies, widths, points or hidden selection. */
@Composable internal fun EqualizerContent(settings: EqSettings, change: (EqSettings) -> Unit) {
    SettingsToggle("Enable equalizer", "Adjust the sound on this device. Your settings save automatically.", settings.enabled, true) {
        change(settings.copy(enabled = it))
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text("10-band equalizer", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { change(settings.copy(points = EqSettings.defaults)) }, modifier = Modifier.tvFocusFeedback()) { Text("Reset to flat") }
    }
    Text(if (settings.enabled) "Slide up to boost, down to cut." else "Off · Turn on to hear your adjustments.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Keep every fader visible with comfortable touch targets on phones.
        val bandsPerRow = if (maxWidth >= 600.dp) 10 else 5
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            EqSettings.defaults.indices.chunked(bandsPerRow).forEach { indices ->
                EqualizerBandRow(indices, settings, change)

            }
        }
    }
    if (isTvDevice()) SettingsDescription("Left/right selects a control. Up/down adjusts the selected band.")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("−12 dB", style = MaterialTheme.typography.labelSmall)
        Text("0 = unchanged", style = MaterialTheme.typography.labelSmall)
        Text("+12 dB", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable private fun EqualizerBandRow(indices: List<Int>, settings: EqSettings, change: (EqSettings) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            indices.forEach { index ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text(eqBandLabels[index], style = MaterialTheme.typography.labelMedium) }
            }
        }
        Box(Modifier.fillMaxWidth().height(160.dp)) {
            Canvas(Modifier.matchParentSize()) {
                val gap = 4.dp.toPx()
                val bandWidth = (size.width - gap * (indices.size - 1)) / indices.size
                val inset = 2.dp.toPx() // Half of the native slider's 4 dp thumb width, rotated.
                fun x(i: Int) = bandWidth / 2 + i * (bandWidth + gap)
                fun y(index: Int) = inset + ((12 - settings.points.getOrElse(index) { EqSettings.defaults[index] }.gain) / 24).toFloat() * (size.height - 2 * inset)
                val path = Path().apply {
                    moveTo(x(0), y(indices.first()))
                    for (i in 1 until indices.size) {
                        val mid = (x(i - 1) + x(i)) / 2
                        cubicTo(mid, y(indices[i - 1]), mid, y(indices[i]), x(i), y(indices[i]))
                    }
                }
                drawPath(path, colors.primary.copy(alpha = 0.6f), style = Stroke(2.dp.toPx()))
            }
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                indices.forEach { index ->
                    val point = settings.points.getOrElse(index) { EqSettings.defaults[index] }
                    VerticalBandSlider(point.gain.toFloat(), "${eqBandLabels[index]} Hz", Modifier.weight(1f).fillMaxHeight()) { gain ->
                        val bands = EqSettings.defaults.mapIndexed { i, band ->
                            band.copy(gain = if (i == index) gain.toDouble() else settings.points.getOrElse(i) { band }.gain)
                        }
                        change(settings.copy(points = bands))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            indices.forEach { index ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(eqGainLabel(settings.points.getOrElse(index) { EqSettings.defaults[index] }.gain), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

internal val eqBandLabels = listOf("31.5", "63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
internal fun eqGainLabel(gain: Double): String = if (gain == 0.0) "0 dB" else String.format(Locale.ROOT, "%+.1f dB", gain)

/** Rotates a native Material slider, preserving its touch and accessibility actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun VerticalBandSlider(value: Float, label: String, modifier: Modifier, change: (Float) -> Unit) {
    val focusManager = LocalFocusManager.current
    val thumbColor = MaterialTheme.colorScheme.primary
    Layout(modifier = modifier, content = {
        Slider(value = value.coerceIn(-12f, 12f), onValueChange = change, valueRange = -12f..12f, steps = 47,
            thumb = { Box(Modifier.size(4.dp, 32.dp).background(thumbColor, RoundedCornerShape(2.dp))) },
            modifier = Modifier.graphicsLayer { rotationZ = 270f }.tvFocusFeedback().onPreviewKeyEvent { event ->
                if (event.key == Key.DirectionLeft || event.key == Key.DirectionRight) {
                    if (event.type == KeyEventType.KeyDown) focusManager.moveFocus(if (event.key == Key.DirectionLeft) FocusDirection.Previous else FocusDirection.Next)
                    return@onPreviewKeyEvent true
                }
                val delta = when (event.key) {
                    Key.DirectionUp -> 0.5f
                    Key.DirectionDown -> -0.5f
                    else -> return@onPreviewKeyEvent false
                }
                if (event.type == KeyEventType.KeyDown) change((value + delta).coerceIn(-12f, 12f))
                true
            }.semantics { contentDescription = label; stateDescription = eqGainLabel(value.toDouble()) })
    }) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val slider = measurables.single().measure(Constraints.fixed(height, width))
        layout(width, height) { slider.place((width - slider.width) / 2, (height - slider.height) / 2) }
    }
}
