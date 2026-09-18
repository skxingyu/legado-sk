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

    @Test
    fun refreshKeepsCachedFactorInSync() {
        // 播放线程只读 currentFactor，必须与设置值换算一致
        VolumeGain.refresh(0)
        assertEquals(1f, VolumeGain.currentFactor)
        VolumeGain.refresh(150)
        assertEquals(VolumeGain.factorFor(150), VolumeGain.currentFactor)
        assertEquals(2.5f, VolumeGain.currentFactor)
        // 越界值经同一入口收敛，避免缓存里出现非法增益
        VolumeGain.refresh(Int.MAX_VALUE)
        assertEquals(VolumeGain.MAX_FACTOR, VolumeGain.currentFactor)
        VolumeGain.refresh(0)
    }

    @Test
    fun qualityCost_appearsOnlyWhenThereIsACost() {
        // 不增强不提示音质代价，避免默认位给用户"已经在牺牲音质"的错觉
        assertEquals(VolumeGain.QualityCost.NONE, VolumeGain.qualityCostFor(0))
        // 削波区（>2x）与普通增强区必须分属不同等级，用户要能区分"变大"和"失真"
        assertEquals(VolumeGain.QualityCost.MILD, VolumeGain.qualityCostFor(100))
        assertEquals(VolumeGain.QualityCost.DISTORTION, VolumeGain.qualityCostFor(300))
        // 边界：恰好 2x 仍属普通增强，超过才升级为失真
        assertEquals(VolumeGain.QualityCost.MILD, VolumeGain.qualityCostFor(100))
        assertEquals(VolumeGain.QualityCost.DISTORTION, VolumeGain.qualityCostFor(101))
        // 越界值走同一收敛逻辑，不得出现第 4 种等级
        assertEquals(
            VolumeGain.QualityCost.DISTORTION,
            VolumeGain.qualityCostFor(Int.MAX_VALUE)
        )
    }

    private companion object {
        /** 与 AppConfig.defaultVolumeGain 保持一致；单测无法读 SharedPreferences。 */
        const val AppConfigDefaultVolume = 0
    }
}
