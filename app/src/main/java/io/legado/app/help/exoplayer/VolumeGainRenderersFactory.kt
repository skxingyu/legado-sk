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
        // 建 sink 时（非音频线程、且每个播放器只一次）从设置播种一次缓存增益，
        // 避免首次播放时缓存还是默认值 1f 而漏掉已配置的增强。
        VolumeGain.refresh(AppConfig.ttsVolumeGain)
        // 与父类默认实现等价，仅追加增益处理器（父类正是 setEnableFloatOutput +
        // setEnableAudioTrackPlaybackParams + build，1.8.0 无 AudioCapabilities setter）
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf<AudioProcessor>(VolumeGainAudioProcessor { VolumeGain.currentFactor }))
            .build()
    }
}
