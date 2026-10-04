package io.github.sneedster.harmonicast

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import android.content.Context
import java.nio.ByteBuffer
import kotlin.math.*
import kotlinx.coroutines.delay

/** Small, decimated PCM window. Audio processing never waits for the UI or draws anything. */
internal object DemoAudio {
    @Volatile var enabled = false
    @Volatile var levels: List<Float> = List(8) { 0f }; private set
    @Volatile var updatedAt = 0L; private set
    private val samples = DoubleArray(512)
    private var count = 0
    private var skip = 0
    @Synchronized fun consume(pcm: ByteBuffer, channels: Int, sampleRate: Int) {
        if (!enabled) { count = 0; skip = 0; return }
        val input = pcm.duplicate().order(pcm.order())
        while (input.remaining() >= channels * 2) {
            var sample = 0.0
            repeat(channels) { sample += input.short / 32768.0 }
            if (++skip % 4 != 0) continue
            samples[count++] = sample / channels
            if (count == samples.size) {
                levels = spectrum(samples, sampleRate / 4)
                updatedAt = android.os.SystemClock.elapsedRealtime()
                count = 0
            }
        }
    }
    internal fun spectrum(samples: DoubleArray, rate: Int): List<Float> =
        listOf(60.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 3500.0, 5000.0).map { frequency ->
            if (frequency >= rate / 2.0) return@map 0f
            val k = (frequency * samples.size / rate).roundToInt().coerceAtLeast(1)
            val coefficient = 2 * cos(2 * PI * k / samples.size)
            var previous = 0.0; var older = 0.0
            samples.forEachIndexed { index, sample ->
                val window = .5 - .5 * cos(2 * PI * index / (samples.size - 1))
                val next = sample * window + coefficient * previous - older
                older = previous; previous = next
            }
            val magnitude = sqrt(max(0.0, previous * previous + older * older - coefficient * previous * older)) / samples.size
            (sqrt(magnitude) * 3).toFloat().coerceIn(0f, 1f)
        }
}

internal enum class DemoScene(val title: String) { OFF("Off"), PLASMA("Plasma"), STARFIELD("Starfield"), WIREFRAME("Wireframe") }
internal class DemoSettings(context: Context) {
    private val prefs = context.getSharedPreferences("device_visualizer", Context.MODE_PRIVATE)
    var scene: DemoScene
        get() = runCatching { DemoScene.valueOf(prefs.getString("scene", "OFF")!!) }.getOrDefault(DemoScene.OFF)
        set(value) { prefs.edit().putString("scene", value.name).apply() }
    var neon: Boolean
        get() = prefs.getBoolean("neon", false)
        set(value) { prefs.edit().putBoolean("neon", value).apply() }
}

@Composable internal fun DemoVisualizer(scene: DemoScene, neon: Boolean, playing: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var time by remember { mutableFloatStateOf(0f) }
    var levels by remember { mutableStateOf(List(8) { 0f }) }
    val reducedMotion = android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    DisposableEffect(scene, playing) {
        DemoAudio.enabled = scene != DemoScene.OFF && playing && !reducedMotion
        onDispose { DemoAudio.enabled = false }
    }
    LaunchedEffect(scene, playing, reducedMotion) {
        if (!playing || reducedMotion) { levels = List(8) { 0f }; return@LaunchedEffect }
        while (true) {
            delay(50)
            time += .05f
            val fresh = android.os.SystemClock.elapsedRealtime() - DemoAudio.updatedAt < 500
            levels = levels.mapIndexed { i, old -> old * .65f + (if (fresh) DemoAudio.levels[i] else 0f) * .35f }
        }
    }
    Canvas(modifier.semantics { contentDescription = "${scene.title} music visualization" }) {
        val energy = levels.average().toFloat()
        val primary = if (neon) Color.Cyan else colors.primary
        val secondary = if (neon) Color.Magenta else colors.secondary
        when(scene) {
            DemoScene.OFF -> Unit
            DemoScene.PLASMA -> {
                val columns = 48; val rows = 28
                val w = size.width / columns; val h = size.height / rows
                for (y in 0 until rows) for (x in 0 until columns) {
                    val phase = sin(x * .17f + time) + sin(y * .25f - time * .7f) + sin(hypot(x - 24f, y - 14f) * .35f - time * (1 + energy * 2))
                    val blend = ((phase + 3) / 6).coerceIn(0f, 1f)
                    val color = androidx.compose.ui.graphics.lerp(primary, secondary, blend).copy(alpha = if (neon) .12f + blend * (.35f + energy * .3f) else .07f + blend * (.12f + energy * .3f))
                    drawRect(color, Offset(x * w, y * h), androidx.compose.ui.geometry.Size(w, h))
                }
            }
            DemoScene.STARFIELD -> repeat(140) { i ->
                val z = (((i * .618033f + time * (.1f + energy * .3f)) % 1f) + .04f)
                val angle = i * 2.39996f
                val radius = ((i * .4142f) % 1f) * min(size.width, size.height) * .09f / z
                val point = center + Offset(cos(angle) * radius, sin(angle) * radius)
                drawCircle(primary.copy(alpha = (1 - z).coerceIn(.1f, .8f)), (1f / z).coerceAtMost(6f), point)
                drawLine(secondary.copy(alpha = .35f), point, center + (point - center) * (1 + energy * .12f), 1.5f)
            }
            DemoScene.WIREFRAME -> {
                val vertices = (0..7).map { i ->
                    val x = if (i and 1 == 0) -1f else 1f; val y = if (i and 2 == 0) -1f else 1f; val z = if (i and 4 == 0) -1f else 1f
                    val xx = x * cos(time * .7f) - z * sin(time * .7f)
                    val zz = x * sin(time * .7f) + z * cos(time * .7f)
                    val yy = y * cos(time * .4f) - zz * sin(time * .4f)
                    val depth = y * sin(time * .4f) + zz * cos(time * .4f) + 4
                    center + Offset(xx, yy) * (min(size.width, size.height) * (.8f + energy * .3f) / depth)
                }
                for (i in 0..7) for (bit in listOf(1, 2, 4)) {
                    val j = i xor bit
                    if (j > i) drawLine(primary.copy(alpha = .6f), vertices[i], vertices[j], 2f + energy * 3f)
                }
                // Copper-style horizontal bands echo the music's eight frequency bands.
                levels.forEachIndexed { i, level ->
                    val y = size.height * (.82f + i * .018f)
                    drawLine(secondary.copy(alpha = .2f + level * .5f), Offset(size.width * .2f, y), Offset(size.width * (.2f + .6f * level), y), 4f)
                }
            }
        }
    }
}
