package io.legado.app.help.exoplayer

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import io.legado.app.help.config.AppConfig

/**
 * 带播放端音量增强的 [DefaultRenderersFactory]。
 *
 * ExoPlayer 没有公开的 `setAudioSink(...)`，注入自定义 AudioSink 的唯一入口是
 * RenderersFactory（`buildAudioRenderers` 内部会调用 [buildAudioSink]）。这里只重写
 * [buildAudioSink]，把增益处理器挂到 media3 默认音频处理链上，其余行为与默认完全一致。
 */
class VolumeGainRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        // 与父类默认实现等价，仅追加增益处理器；增益值从设置读取，播放中调整在下次 flush 生效
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf<AudioProcessor>(VolumeGainAudioProcessor(::currentGain)))
            .build()
    }

    private fun currentGain(): Float = VolumeGain.factorFor(AppConfig.ttsVolumeGain)
}
