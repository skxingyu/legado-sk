package io.legado.app.help.book

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 书籍写入路径的存在性分流契约（源码级静态断言）。
 *
 * 背景：`BookDao.insert` 是 `OnConflictStrategy.REPLACE`。SQLite 的 INSERT OR REPLACE
 * 命中主键冲突时 = DELETE 旧行 + INSERT 新行，且本应用 FK 开启（Room 生成
 * `PRAGMA foreign_keys = ON`），隐式 DELETE 会触发 chapters / book_shortcuts /
 * book_collection_items / book_illustrations 四张子表的 `onDelete = CASCADE`——
 * 对**已存在 bookUrl** 的书裸 insert 等于级联清空章节、快捷入口、合集成员与配图。
 *
 * 修复方式 = 与 `Book.save()` 同构的存在性分流（has → update / else insert）。
 * BookUpsert 依赖 appDb（Room 顶层对象）无法在 JVM 单测中实例化，故退化为源码静态断言：
 * 它不能证明运行正确，但能挡住"把分流改回裸 insert"这个具体回归。
 */
class BookUpsertWritePathTest {

    private fun moduleSource(relativePath: String): File {
        val f = File(relativePath)
        assertTrue(
            "找不到 $relativePath（Gradle 单测 CWD 应为模块目录 app/）：${f.absolutePath}",
            f.exists()
        )
        return f
    }

    @Test
    fun savePlainMustBranchOnExistenceBeforeWriting() {
        val body = moduleSource("src/main/java/io/legado/app/help/book/BookUpsert.kt")
            .readText()
            .substringAfter("private fun savePlain")
            .substringBefore("private fun moveShortcuts")

        assertTrue("未能定位 savePlain 方法体", body.isNotBlank())
        assertTrue(
            "savePlain 必须先 bookDao.has(bookUrl) 判存在再写：裸 INSERT(REPLACE) 会触发子表 CASCADE",
            body.contains("appDb.bookDao.has(target.bookUrl)")
        )
        assertTrue(
            "已存在路径必须走 bookDao.update(target)（@Update 不删行、不触发 CASCADE）",
            body.contains("appDb.bookDao.update(target)")
        )
        val insertIdx = body.indexOf("appDb.bookDao.insert(target)")
        val hasIdx = body.indexOf("appDb.bookDao.has(target.bookUrl)")
        // 从 has(...) 之后找 else：savePlain 前段的 val target = if...else 不算
        val elseIdx = body.indexOf("else {", hasIdx)
        val updateIdx = body.indexOf("appDb.bookDao.update(target)")
        assertTrue(
            "insert(target) 必须位于 has(...) 之后的 else 分支内",
            hasIdx in 0 until updateIdx && updateIdx < elseIdx && elseIdx < insertIdx
        )
    }

    @Test
    fun loadChapterMustUseUpdateWhenBookUrlUnchanged() {
        val body = moduleSource(
            "src/main/java/io/legado/app/ui/book/info/BookInfoViewModel.kt"
        ).readText()
            .substringAfter("val oldBook = book.copy()")
            .substringBefore("}.onError {")

        assertTrue("未能定位 loadChapter 拉目录成功分支", body.isNotBlank())
        val updateIdx = body.indexOf("appDb.bookDao.update(book)")
        val replaceIdx = body.indexOf("appDb.bookDao.replace(oldBook, book)")
        val branchIdx = body.indexOf("oldBook.bookUrl == book.bookUrl")
        assertTrue(
            "同 bookUrl 刷新目录必须走 update：无条件 replace 会级联清空快捷入口/合集/配图",
            branchIdx in 0 until updateIdx && updateIdx < replaceIdx
        )
    }
}
