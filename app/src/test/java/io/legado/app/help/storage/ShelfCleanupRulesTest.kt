package io.legado.app.help.storage

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「本机存在、备份不存在」的书籍比对判据。
 *
 * 这些用例都是缺陷锁：删掉对应守卫会让用例失败。
 */
class ShelfCleanupRulesTest {

    private fun book(
        bookUrl: String,
        name: String = "同名书",
        author: String = "同作者",
        type: Int = BookType.text
    ) = Book(
        bookUrl = bookUrl,
        name = name,
        author = author,
        type = type
    )

    @Test
    fun `local only book is reported as candidate`() {
        val local = listOf(book("local-a", name = "书A"), book("local-b", name = "书B"))
        val backup = listOf(book("backup-a", name = "书A"))

        val candidates = ShelfCleanupRules.findLocalOnlyBooks(local, backup)

        assertEquals(1, candidates.size)
        assertEquals("local-b", candidates.first().bookUrl)
    }

    /**
     * ⚠️ 核心约束：判据必须用身份键而非 bookUrl。
     * 换过源的同书 bookUrl 不同，若按 bookUrl 比对会被误判为「本机多余」而被删除。
     */
    @Test
    fun `same book from another source is not a candidate`() {
        val local = listOf(book("local-url", name = "书A", author = "作者"))
        val backup = listOf(book("backup-url", name = "书A", author = "作者"))

        assertTrue(
            "换源后的同书不得被判为本机多余",
            ShelfCleanupRules.findLocalOnlyBooks(local, backup).isEmpty()
        )
    }

    /**
     * ⚠️ 核心约束（D3）：本地书/归档书/未入架书/无书名一律不进候选。
     * 否则 `Book.delete()` 对 isLocal 会调 LocalBook.deletePersistentBookResources() 物理删文件。
     */
    @Test
    fun `local book is never a candidate even when absent from backup`() {
        val localBook = book("file:///sdcard/a.txt", name = "本地书").apply {
            type = BookType.text or BookType.local
        }
        val candidates = ShelfCleanupRules.findLocalOnlyBooks(listOf(localBook), emptyList())

        assertTrue("本地书不得进入删除候选", candidates.isEmpty())
        assertNull("本地书不参与比对", ShelfCleanupRules.keyOf(localBook))
    }

    @Test
    fun `not shelf and blank name books are never candidates`() {
        val notShelf = book("url-notshelf", name = "试读书").apply {
            type = BookType.text or BookType.notShelf
        }
        val blankName = book("url-blank", name = "")

        val candidates = ShelfCleanupRules.findLocalOnlyBooks(
            listOf(notShelf, blankName),
            emptyList()
        )

        assertTrue("未入架书与无书名书不得进入候选", candidates.isEmpty())
    }

    /**
     * ⚠️ 核心约束（D4）：书名/作者要归一化后再比对。
     * 两台设备的同书可能只在首尾空白上不同，不归一会把备份里**确实存在**的书误判为「本机多余」→ 误删。
     */
    @Test
    fun `whitespace differences in author do not cause false positive`() {
        val local = listOf(book("local-url", name = "斗破苍穹", author = "天蚕土豆 "))
        val backup = listOf(book("backup-url", name = " 斗破苍穹 ", author = "天蚕土豆"))

        assertTrue(
            "仅首尾空白不同不得误判为多余（否则会误删用户书籍）",
            ShelfCleanupRules.findLocalOnlyBooks(local, backup).isEmpty()
        )
    }

    @Test
    fun `different media type is a different book`() {
        val localAudio = listOf(book("local-audio", name = "书A").apply { type = BookType.audio })
        val backupText = listOf(book("backup-text", name = "书A"))

        val candidates = ShelfCleanupRules.findLocalOnlyBooks(localAudio, backupText)

        assertEquals("媒体类型不同属不同书，应进入候选", 1, candidates.size)
    }

    @Test
    fun `empty backup reports every eligible local book`() {
        val local = listOf(book("a", name = "书A"), book("b", name = "书B"))

        val candidates = ShelfCleanupRules.findLocalOnlyBooks(local, emptyList())

        assertEquals(2, candidates.size)
    }

    /** 如实报告被排除的本机书数量，避免用户以为功能漏了。 */
    @Test
    fun `excluded local books are counted for reporting`() {
        val localBook = book("file:///a.txt", name = "本地书").apply {
            type = BookType.text or BookType.local
        }
        val normal = book("b", name = "书B")

        assertEquals(1, ShelfCleanupRules.countExcludedLocalBooks(listOf(localBook, normal)))
    }
}
