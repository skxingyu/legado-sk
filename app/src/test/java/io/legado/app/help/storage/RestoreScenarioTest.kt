package io.legado.app.help.storage

import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookMergeRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 端到端场景复现：两设备同一本书选了不同书源，恢复后不得在书架并列两条。
 *
 * 用真实备份 JSON 形态构造（字段名与 Gson 序列化一致）。
 */
class RestoreScenarioTest {

    /** 设备 A 备份里的书（源 X）。 */
    private fun backupBook() = Book(
        bookUrl = "https://sourceX.com/book/9",
        tocUrl = "https://sourceX.com/toc/9",
        origin = "https://sourceX.com",
        originName = "源X",
        name = "测试书A",
        author = "测试作者",
        durChapterIndex = 5,
        durChapterTime = 5000,
        customTag = "备份标签",
        syncTime = 9999
    )

    /** 设备 B 本机已有的同书（源 Y）。 */
    private fun localBook() = Book(
        bookUrl = "https://sourceY.com/book/1",
        tocUrl = "https://sourceY.com/toc/1",
        origin = "https://sourceY.com",
        originName = "源Y",
        name = "测试书A",
        author = "测试作者",
        durChapterIndex = 0,
        durChapterTime = 1000
    )

    /** 身份键必须相同，否则恢复不会收敛 —— 这是整个修复的前提。 */
    @Test
    fun sameBookFromDifferentSourcesShareIdentity() {
        assertEquals(
            BookMergeRules.identityKeyOf(backupBook()),
            BookMergeRules.identityKeyOf(localBook())
        )
    }

    /** 合并结果必须保留本机 bookUrl（书架上只有这一条，不会出现第二条）。 */
    @Test
    fun mergedResultKeepsLocalBookUrl() {
        val merged = BookMergeRules.mergeFromBackup(localBook(), backupBook(), emptyList())

        assertEquals("https://sourceY.com/book/1", merged.bookUrl)
        assertEquals("https://sourceY.com", merged.origin)
        assertEquals("源Y", merged.originName)
        assertEquals("https://sourceY.com/toc/1", merged.tocUrl)
    }

    /** 备份的用户自定义内容应补进来；备份的同步时钟取较晚者。 */
    @Test
    fun mergedResultCarriesBackupUserData() {
        val merged = BookMergeRules.mergeFromBackup(localBook(), backupBook(), emptyList())

        assertEquals("备份标签", merged.customTag)
        assertEquals(9999L, merged.syncTime)
        assertEquals(5000L, merged.durChapterTime)
    }

    /**
     * 关键回归锁：本机目录为空（无 chapters）时**不得**采纳备份的 durChapterIndex。
     * 备份的索引锚定在源 X 目录上，本机目录为空说明无法定位，搬用会指向不存在的章节。
     */
    @Test
    fun backupIndexIsNotAdoptedWithoutLocalToc() {
        val merged = BookMergeRules.mergeFromBackup(localBook(), backupBook(), emptyList())

        assertEquals("本机目录缺失时索引必须保持本机值", 0, merged.durChapterIndex)
    }

    /** 有本机目录时按标题重定位（源 X 第 5 章 ≈ 本机第 6 章）。 */
    @Test
    fun backupProgressIsRelocatedOntoLocalToc() {
        val toc = (0..9).map {
            io.legado.app.data.entities.BookChapter(
                url = "u$it", title = "第${it + 1}章", bookUrl = "local", index = it
            )
        }
        val backup = backupBook().apply {
            durChapterIndex = 5
            durChapterTitle = "第6章"
            totalChapterNum = 20
        }

        val merged = BookMergeRules.mergeFromBackup(localBook(), backup, toc)

        assertEquals(5, merged.durChapterIndex)
        assertEquals("第6章", merged.durChapterTitle)
    }

    /** 本地书不参与身份收敛（恢复走原路径，不进合并）。 */
    @Test
    fun localBookDoesNotParticipate() {
        val local = localBook().apply {
            type = io.legado.app.constant.BookType.text or io.legado.app.constant.BookType.local
        }
        assertNull(BookMergeRules.identityKeyOf(local))
        assertNotNull(BookMergeRules.identityKeyOf(backupBook()))
    }
}
