package io.legado.app.help.exoplayer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 朗读音量增强的换算契约。
 *
 * 这里锁定的是「滑条只增大、不衰减」和「越界收敛」两条行为；一旦被破坏，
 * 用户最关心的"最小位不改变音量"会回归成静音或衰减。
 */
class VolumeGainTest {

    @Test
    fun minimumPosition_doesNotChangeVolume() {
        // 滑条最小位 = 不增强，绝不能变成衰减
        assertEquals(1f, VolumeGain.factorFor(0))
        assertEquals(1f, VolumeGain.factorFor(AppConfigDefaultVolume))
        assertEquals("不增强", VolumeGain.labelFor(0))
    }

    @Test
    fun percentMapsToLinearFactor() {
        assertEquals(1.5f, VolumeGain.factorFor(50))
        assertEquals(2f, VolumeGain.factorFor(100))
        assertEquals(VolumeGain.MAX_FACTOR, VolumeGain.factorFor(VolumeGain.MAX_PERCENT))
    }

    @Test
    fun outOfRange_isClamped_neverAttenuates() {
        // 负数（含历史脏值）必须收敛为不增强，而不是小于 1 的衰减
        assertEquals(1f, VolumeGain.factorFor(-100))
        // 超过上限收敛到上限
        assertEquals(VolumeGain.MAX_FACTOR, VolumeGain.factorFor(Int.MAX_VALUE))
        assertEquals(5f, VolumeGain.MAX_FACTOR)
        assertEquals(400, VolumeGain.MAX_PERCENT)
    }

    private companion object {
        /** 与 AppConfig.defaultVolumeGain 保持一致；单测无法读 SharedPreferences。 */
        const val AppConfigDefaultVolume = 0
    }
}
