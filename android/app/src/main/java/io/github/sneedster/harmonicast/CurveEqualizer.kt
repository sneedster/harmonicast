package io.github.sneedster.harmonicast

import kotlin.math.*

/** Device-local parametric points. Q controls width: smaller values are broader. */
internal data class EqPoint(val frequency: Double, val gain: Double = 0.0, val q: Double = 0.8) {
    fun validated() = EqPoint(
        frequency.takeIf { it.isFinite() }?.coerceIn(20.0, 20000.0) ?: 1000.0,
        gain.takeIf { it.isFinite() }?.coerceIn(-12.0, 12.0) ?: 0.0,
        q.takeIf { it.isFinite() }?.coerceIn(0.25, 8.0) ?: 0.8,
    )
}
internal data class EqSettings(val enabled: Boolean = false, val points: List<EqPoint> = defaults) {
    fun validated() = copy(points = points.take(8).map { it.validated() })
    // Conservative bound on combined steady-state boost, including overlapping bands.
    val headroomDb: Double get() = if (enabled) points.sumOf { max(0.0, it.gain) } else 0.0
    companion object { val defaults = listOf(80.0, 350.0, 1500.0, 6500.0).map { EqPoint(it) } }
}

/** RBJ peaking biquad, shared by the renderer and response plot. */
internal data class EqCoefficients(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
    fun magnitude(frequency: Double, sampleRate: Int): Double {
        val w = 2 * PI * frequency / sampleRate
        val nr = b0 + b1 * cos(w) + b2 * cos(2 * w)
        val ni = -b1 * sin(w) - b2 * sin(2 * w)
        val dr = 1 + a1 * cos(w) + a2 * cos(2 * w)
        val di = -a1 * sin(w) - a2 * sin(2 * w)
        return sqrt((nr * nr + ni * ni) / (dr * dr + di * di))
    }
    companion object {
        fun forPoint(raw: EqPoint, sampleRate: Int): EqCoefficients {
            val p = raw.validated()
            val w = 2 * PI * p.frequency.coerceAtMost(sampleRate * 0.45) / sampleRate
            val a = 10.0.pow(p.gain / 40)
            val alpha = sin(w) / (2 * p.q)
            val a0 = 1 + alpha / a
            return EqCoefficients((1 + alpha * a) / a0, -2 * cos(w) / a0,
                (1 - alpha * a) / a0, -2 * cos(w) / a0, (1 - alpha / a) / a0)
        }
    }
}

internal fun eqResponseDb(settings: EqSettings, frequency: Double, sampleRate: Int): Double =
    settings.points.sumOf { 20 * log10(EqCoefficients.forPoint(it, sampleRate).magnitude(frequency, sampleRate).coerceAtLeast(1e-12)) }

/** An independent bank per player and channel; never shares sample history across outputs. */
internal class EqFilterBank(val settings: EqSettings, sampleRate: Int, channels: Int) {
    private val coefficients = if (settings.enabled) settings.points.map { EqCoefficients.forPoint(it, sampleRate) } else emptyList()
    private val z1 = Array(coefficients.size) { DoubleArray(channels) }
    private val z2 = Array(coefficients.size) { DoubleArray(channels) }
    private val preamp = 10.0.pow(-settings.headroomDb / 20)
    fun sample(input: Double, channel: Int): Double {
        var value = input * preamp
        coefficients.forEachIndexed { index, c ->
            val output = c.b0 * value + z1[index][channel]
            z1[index][channel] = c.b1 * value - c.a1 * output + z2[index][channel]
            z2[index][channel] = c.b2 * value - c.a2 * output
            value = output
        }
        return value
    }
}
