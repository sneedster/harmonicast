package io.github.sneedster.harmonicast

import android.content.Context
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** Always stays in the PCM chain, so bypass can change without rebuilding the player. */
@UnstableApi
internal class EqualizerAudioProcessor(
    private val settings: () -> EqSettings,
    private val onSampleRate: (Int) -> Unit = {},
) : BaseAudioProcessor() {
    private var bank: EqFilterBank? = null
    private var incoming: EqFilterBank? = null
    private var fadeFrame = 0
    private var fadeFrames = 1

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }
    override fun onFlush() {
        if (inputAudioFormat.sampleRate <= 0) return
        bank = EqFilterBank(settings().validated(), inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        incoming = null
        fadeFrame = 0
        fadeFrames = (inputAudioFormat.sampleRate * 0.025).toInt().coerceAtLeast(1)
        onSampleRate(inputAudioFormat.sampleRate)
    }
    override fun onReset() { bank = null; incoming = null }
    override fun queueInput(inputBuffer: ByteBuffer) {
        val channels = inputAudioFormat.channelCount
        val frameSize = channels * 2
        val bytes = inputBuffer.remaining() / frameSize * frameSize
        if (bytes == 0) return
        val output = replaceOutputBuffer(bytes)
        val desired = settings().validated()
        if (incoming == null && bank?.settings != desired) {
            incoming = EqFilterBank(desired, inputAudioFormat.sampleRate, channels)
            fadeFrame = 0
        }
        repeat(bytes / frameSize) {
            val next = incoming
            val mix = if (next == null) 0.0 else (fadeFrame + 1).toDouble() / fadeFrames
            repeat(channels) { channel ->
                val sample = inputBuffer.short.toDouble()
                val oldValue = bank?.sample(sample, channel) ?: sample
                val value = if (next == null) oldValue else oldValue * (1 - mix) + next.sample(sample, channel) * mix
                output.putShort(value.roundToInt().coerceIn(-32768, 32767).toShort())
            }
            if (next != null && ++fadeFrame >= fadeFrames) { bank = next; incoming = null }
        }
        output.flip()
    }
}

@UnstableApi
internal fun equalizerRenderers(context: Context): DefaultRenderersFactory {
    val store = EqualizerStore.get(context)
    return object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
            PcmEqualizerSink(DefaultAudioSink.Builder(context)
                // PCM decoding is required: compressed passthrough and float output skip processors.
                .setEnableFloatOutput(false)
                .setAudioProcessors(arrayOf(EqualizerAudioProcessor({ store.state.value }, { store.sampleRate.value = it })))
                .build())
    }
}

/** Keep route tracking, but require decoding before audio reaches our processor. */
@UnstableApi
internal class PcmEqualizerSink(sink: AudioSink) : ForwardingAudioSink(sink) {
    override fun supportsFormat(format: Format): Boolean = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED
    override fun getFormatSupport(format: Format): Int =
        if (format.sampleMimeType == MimeTypes.AUDIO_RAW) super.getFormatSupport(format) else AudioSink.SINK_FORMAT_UNSUPPORTED
    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport = AudioOffloadSupport.DEFAULT_UNSUPPORTED
}
