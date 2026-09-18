package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChapterLocator] 是换源后「旧章节位置 → 新章节位置」的定位器，
 * 从 [BookHelp] 原样抽出以保证可在 JVM 单测覆盖。
 *
 * 用例锁定的都是纯函数行为：章节号解析、章节名归一化、相似度匹配与等比回退。
 */
class ChapterLocatorTest {

    private fun chapter(index: Int, title: String) =
        BookChapter(url = "u$index", title = title, bookUrl = "b", index = index)

    private fun toc(vararg titles: String) =
        titles.mapIndexed { index, title -> chapter(index, title) }

    @Test
    fun `finds the same chapter after renumbering by title similarity`() {
        val old = toc("第1章 起点", "第2章 风起", "第3章 云涌")
        val new = toc(
            "第一章 起点",
            "第二章 风起",
            "第三章 云涌",
            "第四章 新增"
        )
        val index = ChapterLocator.findChapterIndex(
            oldDurChapterIndex = 1,
            oldDurChapterName = "第2章 风起",
            newChapterList = new,
            oldChapterListSize = old.size
        )
        assertEquals(1, index)
    }

    @Test
    fun `falls back to chapter number when titles are rewritten`() {
        val new = toc(
            "第1节 完全不同的标题AAA",
            "第2节 完全不同的标题BBB",
            "第3节 完全不同的标题CCC"
        )
        val index = ChapterLocator.findChapterIndex(
            oldDurChapterIndex = 2,
            oldDurChapterName = "第2章 原名",
            newChapterList = new,
            oldChapterListSize = 3
        )
        // 章节名不可比时按章节号命中「第2节」
        assertEquals(1, index)
    }

    @Test
    fun `reading at the very beginning stays at the beginning`() {
        val new = toc("第1章 甲", "第2章 乙")
        assertEquals(
            0,
            ChapterLocator.findChapterIndex(0, "第1章 甲", new, 2)
        )
    }

    @Test
    fun `empty new toc keeps the old index instead of collapsing to zero`() {
        assertEquals(
            7,
            ChapterLocator.findChapterIndex(7, "第7章", emptyList(), 10)
        )
    }

    @Test
    fun `result is always a valid index into the new toc`() {
        val new = toc("第1章 甲", "第2章 乙", "第3章 丙")
        listOf(0, 1, 2, 99, 500).forEach { oldIndex ->
            val index = ChapterLocator.findChapterIndex(
                oldDurChapterIndex = oldIndex,
                oldDurChapterName = "第${oldIndex}章 未知",
                newChapterList = new,
                oldChapterListSize = 500
            )
            assertTrue("index $index out of range for old=$oldIndex", index in new.indices)
        }
    }

    @Test
    fun `chapter number parsing handles chinese and arabic forms`() {
        assertEquals(12, ChapterLocator.chapterNum("第12章 标题"))
        assertEquals(12, ChapterLocator.chapterNum("第十二章 标题"))
        assertEquals(3, ChapterLocator.chapterNum("第3回 标题"))
        assertEquals(-1, ChapterLocator.chapterNum("序章"))
        assertEquals(-1, ChapterLocator.chapterNum(null))
    }

    @Test
    fun `pure chapter name strips numbering and brackets`() {
        assertEquals("风起", ChapterLocator.pureChapterName("第12章 风起"))
        assertEquals("风起", ChapterLocator.pureChapterName("（第12章）风起"))
        assertEquals("", ChapterLocator.pureChapterName(null))
    }

    /** book 级别的重载必须与显式参数版本给出同一结果，否则两条调用路径会漂移。 */
    @Test
    fun `book overload delegates to the explicit argument overload`() {
        val book = Book(bookUrl = "b", name = "书").apply {
            durChapterIndex = 1
            durChapterTitle = "第2章 风起"
            totalChapterNum = 3
        }
        val new = toc("第1章 起点", "第二章 风起", "第3章 云涌")
        assertEquals(
            ChapterLocator.findChapterIndex(1, "第2章 风起", new, 3),
            ChapterLocator.findChapterIndex(book, new)
        )
    }
}
