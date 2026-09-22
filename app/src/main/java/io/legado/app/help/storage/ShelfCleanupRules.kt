package io.legado.app.help.storage

import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookMergeRules

/**
 * 「本机存在、备份中不存在」的书籍比对（纯逻辑，可 JVM 单测）。
 *
 * 用途：用户报告的第二个问题 —— 备份恢复是**增量合并**，A 设备删除书籍后备份，
 * B 设备恢复时其本地未被手动删除的书仍会留存，且 B 再备份会把它们带回 A。
 * 这里提供只读扫描，把候选交给用户勾选，**不自动删除**。
 *
 * ⚠️ 判据用**归一化身份键**而不是 `bookUrl`：
 * - 用 `bookUrl` 会把「换过源的同书」误判为本机多余（换源后 bookUrl 变了）；
 * - 而 `BookMergeRules.identityKeyOf` 刻意不做 trim（那份注释是给**入库收敛**用的，
 *   改动它会连带改变 `BookUpsert`/书架合并的既有语义，故此处不得改写它）。
 *   备份与本机的书名/作者可能只在首尾空白上不同，故本文件自带归一化。
 *
 * ⚠️ 本文件不碰数据库、不依赖 Android 资源，一切 Room 读取与删除都在调用方完成。
 */
object ShelfCleanupRules {

    /** 归一化后的书籍身份键。 */
    data class Key(val name: String, val author: String, val mediaType: Int)

    /**
     * 取归一化身份键。
     *
     * @return null 表示这本书**不参与比对**，调用方必须把它排除在候选之外
     */
    fun keyOf(book: Book): Key? {
        // 复用入库侧的稳定媒体判据，保证「同一本书」的口径与书架上一致；
        // 它同时承担了「本地书/归档书/未入架书/无书名不参与」的语义。
        val mediaType = BookMergeRules.stableMediaType(book) ?: return null
        return Key(
            name = book.name.trim(),
            author = book.author.trim(),
            mediaType = mediaType
        )
    }

    /**
     * 找出「本机有、备份没有」的书籍。
     *
     * @param localBooks 本机书架的**全部**书籍
     * @param backupBooks 备份包里的书籍
     * @return 候选列表。**空表示无需清理**，调用方此时不应弹任何确认框
     */
    fun findLocalOnlyBooks(
        localBooks: List<Book>,
        backupBooks: List<Book>
    ): List<Book> {
        val backupKeys = backupBooks.mapNotNull { keyOf(it) }.toHashSet()
        return localBooks.filter { book ->
            // ⚠️ keyOf 返回 null（本地书/未入架/无书名）时**一律排除**：
            // 写成 `key !in backupKeys` 会让 null 被当成「不在集合内」而入选，
            // 删除本地书会连带清掉本地文件资源（Book.delete -> LocalBook.deletePersistentBookResources）。
            val key = keyOf(book) ?: return@filter false
            key !in backupKeys
        }
    }

    /**
     * 备份中**存在**、但因不参与比对而被排除的本机书数量。
     *
     * 用于向用户如实报告「另有 N 条未列入候选」，避免用户以为功能漏了
     * （AGENTS.md：有问题直接暴露，不静默）。
     */
    fun countExcludedLocalBooks(localBooks: List<Book>): Int {
        return localBooks.count { keyOf(it) == null }
    }
}
