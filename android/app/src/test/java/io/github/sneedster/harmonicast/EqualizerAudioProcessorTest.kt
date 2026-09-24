package io.github.sneedster.harmonicast

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EqualizerAudioProcessorTest {
    private fun buffer(samples: ShortArray) = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder()).apply {
        samples.forEach { putShort(it) }; flip()
    }
    private fun output(processor: EqualizerAudioProcessor, samples: ShortArray): ShortArray {
        processor.queueInput(buffer(samples))
        val result = processor.output
        return ShortArray(result.remaining() / 2) { result.short }
    }
    @Test fun bypassIsBitExactAndEndOfStreamDrains() {
        val p = EqualizerAudioProcessor({ EqSettings() })
        p.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)); p.flush()
        val samples = shortArrayOf(-32768, 32767, -1, 0, 1234, -2345)
        assertArrayEquals(samples, output(p, samples))
        p.queueEndOfStream()
        assertTrue(p.isEnded)
        p.reset()
    }
    @Test fun liveChangesBypassAndFlushDoNotLeakOldAudio() {
        var state = EqSettings()
        val p = EqualizerAudioProcessor({ state })
        p.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)); p.flush()
        state = EqSettings(true, listOf(EqPoint(1000.0, -12.0, 1.0)))
        val input = ShortArray(48000 * 2) { i -> if (i % 2 == 1) 0 else (12000 * sin(2 * PI * 1000 * (i / 2) / 48000)).roundToInt().toShort() }
        val processed = output(p, input)
        assertTrue(processed.filterIndexed { index, _ -> index % 2 == 1 }.all { it == 0.toShort() })
        val ratio = processed.takeLast(24000).sumOf { it.toDouble().pow(2) } / input.takeLast(24000).sumOf { it.toDouble().pow(2) }
        assertEquals(-12.0, 10 * log10(ratio), 0.02)
        state = state.copy(enabled = false)
        output(p, input)
        assertArrayEquals(input, output(p, input))
        state = state.copy(enabled = true)
        p.flush()
        assertTrue(output(p, ShortArray(1024)).all { it == 0.toShort() })
        p.configure(AudioProcessor.AudioFormat(22050, 1, C.ENCODING_PCM_16BIT)); p.flush()
        assertEquals(1024, output(p, ShortArray(1024)).size)
    }
    @Test fun updatesDuringFadeConvergeToLatestAndOutputStaysBounded() {
        var state = EqSettings()
        val p = EqualizerAudioProcessor({ state })
        p.configure(AudioProcessor.AudioFormat(48000, 1, C.ENCODING_PCM_16BIT)); p.flush()
        repeat(30) { step ->
            state = EqSettings(true, List(8) { EqPoint(1000.0 + step * 50, if (step % 2 == 0) 12.0 else -12.0, 8.0) })
            val result = output(p, ShortArray(256) { if (it % 2 == 0) 32767 else -32768 })
            assertEquals(256, result.size)
        }
        state = EqSettings(false)
        repeat(12) { output(p, ShortArray(256) { 3210 }) }
        assertArrayEquals(ShortArray(256) { 3210 }, output(p, ShortArray(256) { 3210 }))
    }
    @Test(expected = AudioProcessor.UnhandledAudioFormatException::class)
    fun unsupportedEncodingIsExplicit() {
        EqualizerAudioProcessor({ EqSettings() }).configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_FLOAT))
    }
    @Test fun sinkRequiresDecodedPcmAndRejectsOffload() {
        val sink = PcmEqualizerSink(androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(RuntimeEnvironment.getApplication()).build())
        val pcm = androidx.media3.common.Format.Builder().setSampleMimeType(androidx.media3.common.MimeTypes.AUDIO_RAW)
            .setSampleRate(48000).setChannelCount(2).setPcmEncoding(C.ENCODING_PCM_16BIT).build()
        val encoded = pcm.buildUpon().setSampleMimeType(androidx.media3.common.MimeTypes.AUDIO_AAC).build()
        assertTrue(sink.supportsFormat(pcm))
        assertFalse(sink.supportsFormat(encoded))
        assertEquals(androidx.media3.exoplayer.audio.AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(encoded))
        assertFalse(sink.getFormatOffloadSupport(encoded).isFormatSupported)
        sink.reset()
    }
    @Test fun settingsPersistSeparatelyFromSourceAndCorruptionFallsBackOff() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("device_equalizer", Context.MODE_PRIVATE).edit().clear().commit()
        val settings = EqSettings(true, listOf(EqPoint(240.0, -4.0, 1.2)))
        EqualizerStore(context).update(settings)
        context.getSharedPreferences("harmonicast", Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals(settings, EqualizerStore(context).state.value)
        assertEquals(EqSettings(), EqualizerStore.decode("broken"))
        assertFalse(EqualizerStore.decode(null).enabled)
        assertTrue(EqualizerStore.decode("{\"enabled\":true,\"points\":[]}").points.isEmpty())
    }
}
