package io.legado.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 书源音频「段内位置预测」的发布门槛（纯函数行为断言）。
 *
 * 门槛同时管页码与位置事件：段落变化、用户显式传送、或预测出的页变了才发布；
 * 否则每个轮询周期都会 post，白白驱动阅读页跟随判定与高亮重算。
 */
class PredictedTextPositionTest {

    @Test
    fun `publishes only when paragraph changes or predicted page differs`() {
        assertTrue(shouldPublishTextPosition(paragraphChanged = true, syncView = false, predictedPage = 3, pageIndex = 3))
        assertTrue(shouldPublishTextPosition(paragraphChanged = false, syncView = true, predictedPage = 3, pageIndex = 3))
        assertTrue(shouldPublishTextPosition(paragraphChanged = false, syncView = false, predictedPage = 4, pageIndex = 3))
        assertFalse(shouldPublishTextPosition(paragraphChanged = false, syncView = false, predictedPage = 3, pageIndex = 3))
    }
}
