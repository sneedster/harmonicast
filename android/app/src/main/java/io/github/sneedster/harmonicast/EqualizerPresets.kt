package io.github.sneedster.harmonicast

/** Original, gentle starting curves. Presets leave the listener's preamp and bypass alone. */
internal data class EqualizerPreset(val name: String, val gains: List<Double>) {
    fun applyTo(settings: EqSettings) = settings.copy(points = EqSettings.defaults.mapIndexed { i, band -> band.copy(gain = gains[i]) })
}

internal val equalizerPresets = listOf(
    EqualizerPreset("Flat", List(10) { 0.0 }),
    EqualizerPreset("Bass lift", listOf(4.0, 3.5, 2.5, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)),
    EqualizerPreset("Warm", listOf(2.0, 2.5, 2.0, 1.0, 0.0, 0.0, -0.5, -1.0, -1.5, -2.0)),
    EqualizerPreset("Vocal", listOf(-2.0, -1.5, -1.0, 0.0, 1.0, 2.5, 3.0, 1.5, 0.0, -0.5)),
    EqualizerPreset("Bright", listOf(0.0, 0.0, -0.5, -0.5, 0.0, 0.5, 1.0, 2.0, 3.0, 2.5)),
    EqualizerPreset("Lively", listOf(3.0, 2.5, 1.0, -0.5, -1.5, -1.0, 0.5, 2.0, 3.0, 2.0)),
)
internal fun equalizerPresetName(settings: EqSettings): String =
    equalizerPresets.firstOrNull { it.gains == settings.points.map { point -> point.gain } }?.name ?: "Custom"
