package io.legado.app.ui.main.bookshelf

import io.legado.app.data.entities.BookGroup
import io.legado.app.ui.main.bookshelf.style1.resolveSavedSecondaryGroupId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 书架标签行（二级分组）选中态的恢复规则。
 *
 * 缺陷锁：冷启动/分组列表变化时必须沿用上次选中的分组，只有该分组已不可见
 * （被隐藏、删除或不属于当前主分类）才退回默认分组。
 */
class SecondaryGroupRestoreTest {

    private val savedTag = 4L

    @Test
    fun savedGroupStillVisible_restoresIt() {
        assertEquals(
            savedTag,
            resolveSavedSecondaryGroupId(savedTag, listOf(BookGroup.IdAll, savedTag), BookGroup.IdAll)
        )
    }

    @Test
    fun savedGroupHidden_fallsBackToDefault() {
        assertEquals(
            BookGroup.IdAll,
            resolveSavedSecondaryGroupId(savedTag, listOf(BookGroup.IdAll), BookGroup.IdAll)
        )
    }

    @Test
    fun neverSavedSentinel_fallsBackToDefault() {
        // 0 是从未保存过的哨兵，不是合法分组 id
        assertEquals(
            BookGroup.IdAll,
            resolveSavedSecondaryGroupId(0L, listOf(BookGroup.IdAll, savedTag), BookGroup.IdAll)
        )
    }

    @Test
    fun fallbackNotAllGroup_keepsFallback() {
        // 「全部」被用户关闭时，默认分组可能落到第一个可见分组
        assertEquals(
            savedTag,
            resolveSavedSecondaryGroupId(0L, listOf(savedTag, 8L), savedTag)
        )
    }
}
