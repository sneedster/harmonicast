package io.github.sneedster.harmonicast

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.*

@Composable internal fun EqualizerSettings() {
    val context = LocalContext.current
    val store = remember(context) { EqualizerStore.get(context) }
    val settings by store.state.collectAsState()
    val sampleRate by store.sampleRate.collectAsState()
    EqualizerContent(settings, sampleRate, store::update)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun EqualizerContent(settings: EqSettings, sampleRate: Int, change: (EqSettings) -> Unit) {
    var selection by rememberSaveable { mutableIntStateOf(0) }
    val selected = selection.coerceIn(0, max(0, settings.points.lastIndex))
    fun updatePoint(point: EqPoint) {
        change(settings.copy(points = settings.points.mapIndexed { i, p -> if (i == selected) point.validated() else p }))
    }
    SettingsToggle("Enable equalizer", "Saved on this device. Playback here uses this curve, including music sent from another device.", settings.enabled, true) {
        change(settings.copy(enabled = it))
    }
    Text(if (settings.enabled) "Your sound" else "Curve preview · bypassed", style = MaterialTheme.typography.titleLarge)
    SettingsDescription("Drag a point to shape the sound. Tap an empty spot to add one. Select a point below for precise adjustments.")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("±${eqPlotRange(settings).toInt()} dB", style = MaterialTheme.typography.labelSmall)
        Text("Bass → treble", style = MaterialTheme.typography.labelSmall)
    }
    EqualizerGraph(settings, sampleRate, selected, { selection = it }, change)
    EqFrequencyAxis()
    SettingsDescription("Curve shows combined tone changes before automatic headroom: −${formatEq(settings.headroomDb)} dB${if (!settings.enabled) " (bypassed)" else ""}. Boosting may lower overall volume.")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        settings.points.forEachIndexed { index, point ->
            FilterChip(selected = selected == index, onClick = { selection = index },
                label = { Text("${index + 1} · ${frequencyLabel(point.frequency)}") },
                modifier = Modifier.tvFocusFeedback().semantics { contentDescription = "Select point ${index + 1}" })
        }
    }
    if (settings.points.isEmpty()) SettingsDescription("Flat response. Add a point to start shaping your sound.")
    settings.points.getOrNull(selected)?.let { point ->
        Text("Point ${selected + 1}", style = MaterialTheme.typography.titleMedium)
        EqAdjustment("Frequency", frequencyLabel(point.frequency), log10(point.frequency).toFloat(),
            log10(20.0).toFloat()..log10(20000.0).toFloat(), 0.025f) { updatePoint(point.copy(frequency = 10.0.pow(it.toDouble()))) }
        EqAdjustment("Gain", "${if (point.gain > 0) "+" else ""}${formatEq(point.gain)} dB", point.gain.toFloat(), -12f..12f, 0.5f) {
            updatePoint(point.copy(gain = it.toDouble()))
        }
        EqAdjustment("Width", "Q ${formatEq(point.q)} · ${if (point.q < 1) "broad" else if (point.q < 3) "medium" else "narrow"}",
            -ln(point.q).toFloat(), -ln(8.0).toFloat()..-ln(0.25).toFloat(), 0.12f) { updatePoint(point.copy(q = exp(-it.toDouble()))) }
        TextButton(onClick = {
            change(settings.copy(points = settings.points.filterIndexed { index, _ -> index != selected }))
            selection = max(0, selected - 1)
        }, modifier = Modifier.tvFocusFeedback()) { Text("Remove point") }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = {
            selection = settings.points.size
            change(settings.copy(points = settings.points + EqPoint(1000.0)))
        }, enabled = settings.points.size < 8, modifier = Modifier.tvFocusFeedback()) { Text("Add point") }
        OutlinedButton(onClick = { selection = 0; change(settings.copy(points = EqSettings.defaults)) }, modifier = Modifier.tvFocusFeedback()) { Text("Reset to flat") }
    }
    SettingsDescription("${settings.points.size} of 8 points · Changes save automatically. ${sampleRate / 1000.0} kHz response preview; high-frequency points adapt to the playing track.")
}

@Composable private fun EqAdjustment(label: String, valueLabel: String, value: Float, range: ClosedFloatingPointRange<Float>, step: Float, change: (Float) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedIconButton(onClick = { change((value - step).coerceIn(range)) }, enabled = value > range.start,
                modifier = Modifier.tvFocusFeedback()) { Icon(Icons.Default.Remove, "Decrease $label") }
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium)
                Text(valueLabel, style = MaterialTheme.typography.titleSmall)
            }
            OutlinedIconButton(onClick = { change((value + step).coerceIn(range)) }, enabled = value < range.endInclusive,
                modifier = Modifier.tvFocusFeedback()) { Icon(Icons.Default.Add, "Increase $label") }
        }
        Slider(value = value.coerceIn(range), onValueChange = change, valueRange = range,
            modifier = Modifier.fillMaxWidth().tvFocusFeedback().onPreviewKeyEvent { event ->
                val delta = when (event.key) { Key.DirectionLeft -> -step; Key.DirectionRight -> step; else -> return@onPreviewKeyEvent false }
                if (event.type == KeyEventType.KeyDown) change((value + delta).coerceIn(range))
                true
            }.semantics { contentDescription = label; stateDescription = valueLabel })
    }
}

@Composable private fun EqualizerGraph(settings: EqSettings, sampleRate: Int, selected: Int, select: (Int) -> Unit, change: (EqSettings) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val latest by rememberUpdatedState(settings)
    val latestChange by rememberUpdatedState(change)
    val latestSelect by rememberUpdatedState(select)
    // Leave space around the plot so edge points remain reachable at full gain.
    Canvas(Modifier.fillMaxWidth().height(220.dp).semantics {
        contentDescription = "Equalizer response curve. Use the point controls below to adjust frequency, gain and width."
    }.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val padding = 18.dp.toPx()
            val w = (size.width - 2 * padding).coerceAtLeast(1f)
            val h = (size.height - 2 * padding).coerceAtLeast(1f)
            val range = eqPlotRange(latest)
            fun location(p: EqPoint) = Offset(padding + eqX(p.frequency) * w, padding + ((range - p.gain) / (2 * range)).toFloat() * h)
            fun pointAt(position: Offset, q: Double) = EqPoint(eqFrequency(((position.x - padding) / w).coerceIn(0f, 1f)),
                (range - ((position.y - padding) / h) * 2 * range).toDouble().coerceIn(-12.0, 12.0), q)
            var gestureSettings = latest
            val nearest = latest.points.indices.minByOrNull { (location(latest.points[it]) - down.position).getDistance() }
            var index = nearest?.takeIf { (location(latest.points[it]) - down.position).getDistance() <= 28.dp.toPx() }
            if (index == null && latest.points.size < 8) {
                index = latest.points.size
                gestureSettings = latest.copy(points = latest.points + pointAt(down.position, 0.8))
                latestChange(gestureSettings)
            }
            val active = index ?: return@awaitEachGesture
            latestSelect(active)
            down.consume()
            while (true) {
                val event = awaitPointerEvent()
                val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!pointer.pressed) break
                if (pointer.position != pointer.previousPosition) {
                    val state = gestureSettings
                    state.points.getOrNull(active)?.let { p ->
                        gestureSettings = state.copy(points = state.points.mapIndexed { i, old -> if (i == active) pointAt(pointer.position, p.q) else old })
                        latestChange(gestureSettings)
                    }
                }
                pointer.consume()
            }
        }
    }) {
        val padding = 18.dp.toPx()
        val w = size.width - 2 * padding
        val h = size.height - 2 * padding
        val range = eqPlotRange(settings)
        fun y(db: Double) = padding + ((range - db.coerceIn(-range, range)) / (2 * range)).toFloat() * h
        drawRoundRect(colors.surfaceVariant.copy(alpha = 0.35f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()))
        listOf(-range, -range / 2, 0.0, range / 2, range).forEach { db ->
            drawLine(colors.onSurfaceVariant.copy(alpha = if (db == 0.0) 0.55f else 0.18f), Offset(padding, y(db)), Offset(size.width - padding, y(db)))
        }
        listOf(20.0, 100.0, 1000.0, 10000.0, 20000.0).forEach { hz ->
            val x = padding + eqX(hz) * w
            drawLine(colors.onSurfaceVariant.copy(alpha = 0.18f), Offset(x, padding), Offset(x, padding + h))
        }
        val path = Path()
        repeat(241) { step ->
            val x = step / 240f
            val frequency = eqFrequency(x).coerceAtMost(sampleRate * 0.499)
            val value = eqResponseDb(settings, frequency, sampleRate)
            if (step == 0) path.moveTo(padding, y(value)) else path.lineTo(padding + x * w, y(value))
        }
        drawPath(path, if (settings.enabled) colors.primary else colors.onSurfaceVariant, style = Stroke(2.5.dp.toPx()))
        settings.points.forEachIndexed { index, point ->
            val center = Offset(padding + eqX(point.frequency) * w, y(point.gain))
            if (selected == index) drawCircle(colors.primary.copy(alpha = 0.18f), 15.dp.toPx(), center)
            drawCircle(colors.surface, 7.dp.toPx(), center)
            drawCircle(colors.primary, 7.dp.toPx(), center, style = Stroke(if (selected == index) 3.dp.toPx() else 1.5.dp.toPx()))
        }
    }
}
internal fun eqX(frequency: Double): Float = (log10(frequency / 20) / 3).toFloat()
internal fun eqFrequency(x: Float): Double = 20 * 10.0.pow(x * 3.0)
private fun frequencyLabel(hz: Double) = if (hz >= 1000) "${formatEq(hz / 1000)} kHz" else "${hz.roundToInt()} Hz"
private fun formatEq(value: Double) = String.format(Locale.ROOT, "%.1f", value)

private fun eqPlotRange(settings: EqSettings): Double = max(12.0, ceil(max(settings.points.sumOf { max(0.0, it.gain) }, settings.points.sumOf { max(0.0, -it.gain) }) / 6) * 6)

@Composable private fun EqFrequencyAxis() {
    val ticks = listOf(20.0 to "20 Hz", 100.0 to "100", 1000.0 to "1k", 10000.0 to "10k", 20000.0 to "20k")
    Layout(content = { ticks.forEach { Text(it.second, style = MaterialTheme.typography.labelSmall) } }, modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
        val labels = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val width = constraints.maxWidth
        val padding = 18.dp.roundToPx()
        layout(width, labels.maxOf { it.height }) {
            labels.forEachIndexed { index, label ->
                val x = (padding + eqX(ticks[index].first) * (width - 2 * padding) - label.width / 2).roundToInt()
                label.place(x.coerceIn(0, (width - label.width).coerceAtLeast(0)), 0)
            }
        }
    }
}
