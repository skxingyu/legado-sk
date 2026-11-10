package io.legado.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 无回调看门狗阈值的行为断言（直接调真实纯函数）。
 *
 * 实机事故（2026-11-10，雷电模拟器直连 MultiTTS，句长限制 32）：
 * 第 85 段 169 字，`onStart` 后 16 秒零回调（MultiTTS 不发 onRangeStart），
 * 固定 15 秒阈值到点 → 重试本段（听成「回段首重读」）→ 二次超时直接跳段。
 * 阈值必须覆盖本段预计朗读时长，且要有上限防止语速采样异常时无限放大。
 */
class TtsStallDeadlineTest {

    private val base = 15_000L

    @Test
    fun emptyParagraphKeepsBaseTimeout() {
        assertEquals(base, ttsStallDeadlineMs(0, 480.0 / 60_000.0))
    }

    @Test
    fun longParagraphIsGivenItsEstimatedSpeechTime() {
        // 169 字 / 480 字每分钟 = 21.125 秒
        assertEquals(base + 21_125L, ttsStallDeadlineMs(169, 480.0 / 60_000.0))
    }

    @Test
    fun shortParagraphStaysCloseToBase() {
        // 40 字 ≈ 5 秒
        assertEquals(base + 5_000L, ttsStallDeadlineMs(40, 480.0 / 60_000.0))
    }

    @Test
    fun invalidRateFallsBackToBase() {
        assertEquals(base, ttsStallDeadlineMs(169, 0.0))
        assertEquals(base, ttsStallDeadlineMs(169, -0.008))
    }

    @Test
    fun estimateIsCapped() {
        // 1e6 字按 480 字每分钟要 125 秒以上，封顶 120 秒额外余量
        assertEquals(base + 120_000L, ttsStallDeadlineMs(1_000_000, 480.0 / 60_000.0))
    }
}
