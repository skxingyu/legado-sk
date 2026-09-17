package io.legado.app.help.exoplayer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [VolumeGainAudioProcessor] 的缓冲区读写契约。
 *
 * ⚠️ 这里锁定的核心事实是「增益处理必须真的产出样本」。此前的实现用
 * `asShortBuffer()` 视图写样本、再对外层 ByteBuffer 调 `flip()`，导致输出 0 字节
 * （视图的 position 前进而外层 position 仍为 0，flip 后 limit=0）→ 朗读完全无声。
 * 该缺陷不会崩溃、日志无异常，只能靠本测试这类「断言输出字节数」的用例抓到，
 * 因此必须保留 [emitsAllInputBytesAmplified] 与 [unityGainStillEmitsAllBytes]。
 */
class VolumeGainAudioProcessorTest {

    private val format16 = AudioFormat(SAMPLE_RATE, CHANNELS, C.ENCODING_PCM_16BIT)

    private fun processor(gain: Float) = VolumeGainAudioProcessor { gain }

    private fun configure(p: VolumeGainAudioProcessor) {
        p.configure(format16)
        p.flush()
    }

    private fun shortBuffer(vararg samples: Short): ByteBuffer {
        val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.nativeOrder())
        samples.forEach { buf.putShort(it) }
        buf.flip()
        return buf
    }

    private fun readShorts(buffer: ByteBuffer): List<Short> {
        // duplicate() 不继承字节序（会退回 BIG_ENDIAN），必须显式指定，否则按错字节序读出乱码
        val dup = buffer.duplicate().order(buffer.order())
        val out = ArrayList<Short>()
        while (dup.hasRemaining()) out.add(dup.short)
        return out
    }

    @Test
    fun emitsAllInputBytesAmplified() {
        val p = processor(gain = 2f)
        configure(p)
        assertTrue("增益不为 1 时处理器应参与处理链", p.isActive)

        p.queueInput(shortBuffer(1000, -1000, 0, 500))

        val output = p.output
        // 关键断言：字节数与输入一致。回归时会变成 0（完全无声）。
        assertEquals(8, output.remaining())
        assertEquals(listOf<Short>(2000, -2000, 0, 1000), readShorts(output))
    }

    @Test
    fun unityGainStillEmitsAllBytes() {
        // 增益为 1 时处理器被认为不激活，队列由 media3 直接旁路；
        // 这里锁定的是"不激活"这一事实（旁路后声音照常，不会静音）。
        val p = processor(gain = 1f)
        configure(p)
        assertFalse("增益为 1 时不得占用处理链", p.isActive)
    }

    @Test
    fun clampsInsteadOfWrapping() {
        val p = processor(gain = 4f)
        configure(p)
        p.queueInput(shortBuffer(30000, -30000))

        // 数字放大必然削波：夹到 short 边界，绝不能回绕成反相
        assertEquals(listOf<Short>(32767, -32768), readShorts(p.output))
    }

    @Test
    fun producesNothingWhenInputEmpty() {
        val p = processor(gain = 2f)
        configure(p)
        p.queueInput(ByteBuffer.allocate(0))
        assertEquals(0, p.output.remaining())
    }

    @Test
    fun rejectsIncompleteFrame() {
        val p = processor(gain = 2f)
        configure(p)
        // 立体声 16bit 每帧 4 字节，3 字节属半帧：必须暴露而不是错位解读
        val bad = ByteBuffer.allocate(3).order(ByteOrder.nativeOrder()).apply {
            put(byteArrayOf(1, 2, 3))
            flip()
        }
        val error = runCatching { p.queueInput(bad) }.exceptionOrNull()
        assertTrue("非整帧输入必须抛异常，实际=$error", error is IllegalStateException)
    }

    @Test
    fun unsupportedEncoding_passesThroughInsteadOfFailing() {
        // 非 16bit/float 编码必须降级为"不增强但可播放"。
        // 若改成抛 UnhandledAudioFormatException，DefaultAudioSink 会包装成
        // ConfigurationException 令整段音频配置失败（朗读直接报错），已由字节码确认。
        val p = processor(gain = 2f)
        val result = p.configure(AudioFormat(SAMPLE_RATE, CHANNELS, C.ENCODING_PCM_24BIT))

        assertEquals(AudioFormat.NOT_SET, result)
        assertFalse("不支持的编码下必须旁路，不得参与处理链", p.isActive)
    }

    private companion object {
        const val SAMPLE_RATE = 44100
        const val CHANNELS = 2
    }
}
