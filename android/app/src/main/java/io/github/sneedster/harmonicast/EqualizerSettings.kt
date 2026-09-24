package io.github.sneedster.harmonicast

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.round

@Composable internal fun EqualizerSettings() {
    val context = LocalContext.current
    val store = remember(context) { EqualizerStore.get(context) }
    val settings by store.state.collectAsState()
    EqualizerContent(settings, store::update)
}

/** One continuous, fixed-frequency control surface at every screen width. */
@Composable internal fun EqualizerContent(settings: EqSettings, change: (EqSettings) -> Unit) {
    var selected by remember { mutableIntStateOf(4) }
    var presetsOpen by remember { mutableStateOf(false) }
    val currentChange by rememberUpdatedState(change)
    val currentSettings by rememberUpdatedState(settings)
    fun adjust(index: Int, gain: Double) {
        val bands = EqSettings.defaults.mapIndexed { i, band ->
            band.copy(gain = if (i == index) round(gain.coerceIn(-12.0, 12.0) * 2) / 2 else currentSettings.points.getOrElse(i) { band }.gain)
        }
        currentChange(currentSettings.copy(points = bands))
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("10-band equalizer", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { change(settings.copy(points = EqSettings.defaults, preampDb = 0.0)) }, modifier = Modifier.tvFocusFeedback()) { Text("Reset to flat") }
        }
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { presetsOpen = true }, modifier = Modifier.fillMaxWidth().tvFocusFeedback().semantics { contentDescription = "Equalizer preset" }) {
                Text("Preset: ${equalizerPresetName(settings)}", Modifier.weight(1f))
                Icon(Icons.Default.ArrowDropDown, null)
            }
            DropdownMenu(expanded = presetsOpen, onDismissRequest = { presetsOpen = false }) {
                equalizerPresets.forEach { preset ->
                    DropdownMenuItem(text = { Text(preset.name) }, onClick = { change(preset.applyTo(settings)); presetsOpen = false })
                }
            }
        }
        EqualizerGraph(settings, selected, { selected = it }, ::adjust)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${eqBandLabels[selected]} Hz · ${eqGainLabel(settings.points.getOrElse(selected) { EqSettings.defaults[selected] }.gain)}",
                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = { adjust(selected, currentSettings.points[selected].gain - 0.5) }, modifier = Modifier.tvFocusFeedback()) {
                Icon(Icons.Default.Remove, "Decrease selected band")
            }
            IconButton(onClick = { adjust(selected, currentSettings.points[selected].gain + 0.5) }, modifier = Modifier.tvFocusFeedback()) {
                Icon(Icons.Default.Add, "Increase selected band")
            }
        }
        Text(if (isTvDevice()) "Left/right selects a band. Up/down adjusts it." else "Drag a point up or down. Tap a band to fine-tune.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    SettingsToggle("Enable equalizer", "Applies on this device. Adjustments save automatically.", settings.enabled, true) {
        change(settings.copy(enabled = it))
    }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Preamp", style = MaterialTheme.typography.titleMedium)
            Text(eqGainLabel(settings.preampDb), style = MaterialTheme.typography.bodyMedium)
        }
        Slider(value = settings.preampDb.toFloat(), onValueChange = { change(settings.copy(preampDb = it.toDouble())) },
            valueRange = -12f..12f, steps = 47,
            modifier = Modifier.fillMaxWidth().tvFocusFeedback().semantics { contentDescription = "Equalizer preamp" })
        Text("Overall level. Lower it if boosts sound distorted.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun EqualizerGraph(settings: EqSettings, selected: Int, select: (Int) -> Unit, change: (Int, Double) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    val latestChange by rememberUpdatedState(change)
    val latestSelect by rememberUpdatedState(select)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            EqSettings.defaults.indices.forEach { i ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(eqCompactGain(settings.points.getOrElse(i) { EqSettings.defaults[i] }.gain),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 0.sp), maxLines = 1)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(220.dp)) {
            Canvas(Modifier.matchParentSize()) {
                val lane = size.width / 10
                val inset = 12.dp.toPx()
                fun x(i: Int) = lane * (i + 0.5f)
                fun y(i: Int) = inset + ((12 - settings.points.getOrElse(i) { EqSettings.defaults[i] }.gain) / 24).toFloat() * (size.height - inset * 2)
                listOf(inset, size.height / 2, size.height - inset).forEach { y ->
                    drawLine(colors.outlineVariant.copy(alpha = 0.5f), Offset(x(0), y), Offset(x(9), y), 1.dp.toPx())
                }
                drawLine(colors.primary.copy(alpha = 0.16f), Offset(x(selected), inset), Offset(x(selected), size.height - inset), 1.dp.toPx())
                val curve = Path().apply {
                    moveTo(x(0), y(0))
                    for (i in 1..9) {
                        val mid = (x(i - 1) + x(i)) / 2
                        cubicTo(mid, y(i - 1), mid, y(i), x(i), y(i))
                    }
                }
                val fill = Path().apply {
                    addPath(curve); lineTo(x(9), size.height / 2); lineTo(x(0), size.height / 2); close()
                }
                drawPath(fill, colors.primary.copy(alpha = 0.12f))
                drawPath(curve, colors.primary, style = Stroke(2.dp.toPx()))
                for (i in 0..9) {
                    if (i == selected) drawCircle(colors.primary.copy(alpha = 0.2f), 10.dp.toPx(), Offset(x(i), y(i)))
                    drawCircle(colors.primary, 5.dp.toPx(), Offset(x(i), y(i)))
                }
            }
            Row(Modifier.fillMaxSize()) {
                EqSettings.defaults.indices.forEach { i ->
                    val point = settings.points.getOrElse(i) { EqSettings.defaults[i] }
                    val requester = remember { FocusRequester() }
                    Box(Modifier.weight(1f).fillMaxHeight().focusRequester(requester)
                        .onFocusChanged { if (it.isFocused) select(i) }
                        .onPreviewKeyEvent { event ->
                            val direction = when (event.key) {
                                Key.DirectionLeft -> FocusDirection.Previous
                                Key.DirectionRight -> FocusDirection.Next
                                else -> null
                            }
                            if (direction != null) {
                                if (event.type == KeyEventType.KeyDown) focusManager.moveFocus(direction)
                                true
                            } else if (event.key == Key.DirectionUp || event.key == Key.DirectionDown) {
                                if (event.type == KeyEventType.KeyDown) change(i, point.gain + if (event.key == Key.DirectionUp) 0.5 else -0.5)
                                true
                            } else false
                        }.semantics {
                            contentDescription = "${eqBandLabels[i]} Hz"
                            stateDescription = eqGainLabel(point.gain)
                            progressBarRangeInfo = ProgressBarRangeInfo(point.gain.toFloat(), -12f..12f, 47)
                            setProgress { latestChange(i, it.toDouble()); true }
                            onClick("Select band") { latestSelect(i); requester.requestFocus(); true }
                        }.focusable()
                        .pointerInput(i) { detectTapGestures { latestSelect(i); requester.requestFocus() } }
                        .pointerInput(i) {
                            val inset = 12.dp.toPx()
                            var dragY = 0f
                            fun update(y: Float) = latestChange(i, (12 - 24 * ((y - inset) / (size.height - inset * 2))).toDouble())
                            detectDragGestures(onDragStart = { position ->
                                latestSelect(i); dragY = position.y; update(dragY)
                            }) { event, amount -> event.consume(); dragY += amount.y; update(dragY) }
                        })
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            eqBandLabels.forEach { label ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 0.sp), maxLines = 1)
                }
            }
        }
    }
}

internal val eqBandLabels = listOf("31.5", "63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
internal fun eqCompactGain(gain: Double): String = if (gain == 0.0) "0" else String.format(Locale.ROOT, "%+.1f", gain)
internal fun eqGainLabel(gain: Double): String = if (gain == 0.0) "0 dB" else String.format(Locale.ROOT, "%+.1f dB", gain)
