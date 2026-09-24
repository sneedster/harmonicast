package io.github.sneedster.harmonicast

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class CurveEqualizerTest {
    @Test fun peakAndCutMatchSpecifiedGainAtCenter() {
        for (rate in listOf(22050, 44100, 48000, 96000)) for (gain in listOf(-12.0, -3.0, 0.0, 6.0, 12.0)) {
            val coefficient = EqCoefficients.forPoint(EqPoint(1500.0, gain), rate)
            assertEquals(gain, 20 * log10(coefficient.magnitude(1500.0, rate)), 1e-8)
        }
    }
    @Test fun calculatedResponseMatchesProcessedSineIncludingExplicitPreamp() {
        val rate = 48000
        val settings = EqSettings(true, listOf(EqPoint(700.0, 5.0, 1.3), EqPoint(2300.0, -7.0, 0.5)), preampDb = -2.0)
        for (frequency in listOf(80.0, 700.0, 2300.0, 11000.0)) {
            val bank = EqFilterBank(settings, rate, 2)
            var inputEnergy = 0.0
            var outputEnergy = 0.0
            repeat(rate) { i ->
                val input = sin(2 * PI * frequency * i / rate)
                val output = bank.sample(input, 0)
                assertEquals(0.0, bank.sample(0.0, 1), 0.0)
                if (i >= rate / 2) { inputEnergy += input * input; outputEnergy += output * output }
            }
            assertEquals(eqResponseDb(settings, frequency, rate) + settings.preampDb, 10 * log10(outputEnergy / inputEnergy), 0.01)
        }
    }
    @Test fun tenthBandIsProcessed() {
        val rate = 48000
        val settings = EqSettings(true, EqSettings.defaults.mapIndexed { i, band -> if (i == 9) band.copy(gain = -6.0) else band })
        val bank = EqFilterBank(settings.validated(), rate, 1)
        var inputEnergy = 0.0
        var outputEnergy = 0.0
        repeat(rate) { i ->
            val input = sin(2 * PI * 16000 * i / rate)
            val output = bank.sample(input, 0)
            if (i > rate / 2) { inputEnergy += input * input; outputEnergy += output * output }
        }
        assertEquals(-6.0, 10 * log10(outputEnergy / inputEnergy), 0.01)
    }
    @Test fun bypassAndFlatAreIdentityAndChannelsStayIndependent() {
        for (settings in listOf(EqSettings(preampDb = 12.0), EqSettings(true))) {
            val bank = EqFilterBank(settings, 44100, 2)
            repeat(10000) { i ->
                val input = sin(i.toDouble())
                assertEquals(input, bank.sample(input, 0), 1e-10)
                assertEquals(0.0, bank.sample(0.0, 1), 0.0)
            }
        }
    }
    @Test fun extremeFiltersRemainFiniteAndDecayAtDifferentSampleRates() {
        for (rate in listOf(8000, 22050, 44100, 48000, 96000, 192000)) {
            for (gain in listOf(-12.0, 12.0)) {
                val settings = EqSettings(true, EqSettings.defaults.map { it.copy(gain = gain) })
                val bank = EqFilterBank(settings, rate, 1)
                var tail = 0.0
                repeat(rate * 2) { i ->
                    val value = bank.sample(if (i == 0) 1.0 else 0.0, 0)
                    assertTrue(value.isFinite())
                    assertTrue(abs(value) < 1000)
                    if (i > rate * 2 - 100) tail = max(tail, abs(value))
                }
                assertTrue("rate=$rate gain=$gain tail=$tail", tail < 0.001)
            }
        }
    }
    @Test fun malformedValuesAndPreampAreBounded() {
        val safe = EqSettings(true, List(12) { EqPoint(Double.NaN, 999.0, -1.0) }, preampDb = 999.0).validated()
        assertEquals(10, safe.points.size)
        assertEquals(EqPoint(1000.0, 12.0, 0.25), safe.points.first())
        assertEquals(12.0, safe.preampDb, 0.0)
        assertEquals(0.0, safe.copy(preampDb = Double.NaN).validated().preampDb, 0.0)
    }
}
