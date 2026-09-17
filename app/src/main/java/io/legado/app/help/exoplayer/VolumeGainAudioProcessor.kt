package io.legado.app.help.exoplayer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * 播放端音量增强（数字增益）。
 *
 * 背景：部分在线 TTS 音源默认合成音量偏小，手机音量调到最大仍不足。ExoPlayer 的
 * `setVolume()` 内部被 `Util.constrainValue(volume, 0f, 1f)` 钳制在 [0,1]（已由字节码确认），
 * 无法用于放大，因此必须在解码后的 PCM 上做乘法。
 *
 * ⚠️ 缓冲区读写纪律（此前的无声事故根因，勿改回）：
 * 必须**直接用 ByteBuffer 的 getShort/putShort（或 getFloat/putFloat）**逐样本读写，
 * 最后对本方法内 `replaceOutputBuffer` 返回的那个 buffer 调用**一次** flip。
 * 若改用 `asShortBuffer()`/`asFloatBuffer()` 视图写入，视图的 position 会前进而外层
 * ByteBuffer 的 position 仍为 0，末尾 flip() 会把 limit 置为 0 → 输出 0 字节 → **完全无声**。
 * media3 官方 `GainProcessor.queueInput` 正是前者写法（末尾仅一次 flip），此处与之一致。
 *
 * 未复用官方 `GainProcessor`：其 `GainProvider.isUnityUntil` 在"增益为 1"的区段必须返回
 * 一个有效的非 `Long.MIN_VALUE` 边界，否则其 `queueInput` 的 checkState 会直接抛异常；
 * 而本处理器需要的是"增益全程恒定且可运行时读取"，自建实现更简单也更好维护。
 *
 * 增益在每次 [queueInput] 时读取。注意本处理器是否参与处理链由 [isActive] 决定，而该判定只在
 * `configure()` / `flush()` 时重建，因此播放中调整设置后，需等下一段音频入队触发 flush 才生效
 * （朗读逐句合成天然满足；不承诺长音频流中途即时变化）。
 */
class VolumeGainAudioProcessor(
    private val gainProvider: () -> Float = { 1f },
) : BaseAudioProcessor() {

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        // 与 media3 自带 GainProcessor 一致：只处理 16bit PCM 与 float PCM。
        // 其余编码必须抛 UnhandledAudioFormatException，由 media3 绕过本处理器并原样交给 sink；
        // 若返回 inputAudioFormat 会被视为"已处理"，随后在 queueInput 里按错误宽度解读样本
        // （无声的数据损坏，比崩溃更难归因）。
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> Unit
            else -> throw UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun isActive(): Boolean = super.isActive() && gainProvider() != 1f

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        // 帧对齐校验（照抄官方 GainProcessor）：不做会把非整帧数据按样本错位解读，产生杂音
        check(remaining % inputAudioFormat.bytesPerFrame == 0) { "Queued an incomplete frame." }
        val gain = gainProvider().coerceAtLeast(0f)
        val output = replaceOutputBuffer(remaining)
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> {
                while (inputBuffer.hasRemaining()) {
                    // 数字放大的物理代价就是削波，夹到 short 范围而不是回绕
                    val amplified = (inputBuffer.getShort() * gain).toInt()
                    output.putShort(amplified.coerceIn(MIN_PCM_16, MAX_PCM_16).toShort())
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                while (inputBuffer.hasRemaining()) {
                    output.putFloat((inputBuffer.getFloat() * gain).coerceIn(-1f, 1f))
                }
            }
            else -> error("Unhandled encoding reached queueInput: ${inputAudioFormat.encoding}")
        }
        // 只 flip 这一次；output 的 position 已由上面的 put* 推进
        output.flip()
    }

    private companion object {
        const val MIN_PCM_16 = -32768
        const val MAX_PCM_16 = 32767
    }
}

/**
 * 音量增强的取值与换算唯一入口。
 *
 * 设置值是「增强百分比」：`0` = 不增强（增益 1.0x），上限 [MAX_PERCENT] = 5.0x。
 * 最小位刻意不做衰减（因子不会小于 1）：音量偏小才是待解决的问题，滑条只负责变大。
 */
object VolumeGain {

    /** 增益上限倍数：纯数字放大，超过约 2x 后大声处会削波失真，5x 用于覆盖极低音量音源。 */
    const val MAX_FACTOR = 5f

    /** 设置项上限（百分比）。 */
    const val MAX_PERCENT = ((MAX_FACTOR - 1f) * 100).toInt()

    /** 把百分比设置值换算为线性增益系数，入参越界会被收敛到有效区间。 */
    fun factorFor(percent: Int): Float {
        if (percent <= 0) return 1f
        return 1f + percent.coerceAtMost(MAX_PERCENT) / 100f
    }

    /** 增益系数对应的展示文案，例如 `2.5X` 或 `不增强`。 */
    fun labelFor(percent: Int): String {
        val factor = factorFor(percent)
        return if (factor == 1f) "不增强" else "%.1fX".format(factor)
    }
}
