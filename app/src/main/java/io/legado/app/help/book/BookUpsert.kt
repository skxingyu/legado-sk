package io.legado.app.help.book

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.ReadRecentBook
import io.legado.app.help.ai.AiChapterPurifyService
import io.legado.app.model.ReadBook

/**
 * 书籍身份收敛的**写入收口**：所有「按身份入库」的路径都必须走这里。
 *
 * 存在的理由：`Book` 表主键是 `bookUrl`，同一本书换源后 `bookUrl` 变了，朴素地
 * `delete(旧) + insert(新)` 或 `insert(新)` 都会在书架上留下第二条记录。
 * 本对象把「查到同书就合并、查不到才插入」收敛成唯一入口，
 * 并以**返回值**告知调用方最终落库的是哪一本（合并时是 keep，不是传入的 src）。
 *
 * ⚠️ 调用方**必须使用返回值**去驱动 UI 与阅读引擎，继续持有传入对象会导致
 * 「页面按 src 的 bookUrl 打开、但库里只有 keep」的错配。
 *
 * 判据与字段裁决见 [BookMergeRules]（纯逻辑、有单测）；本文件只负责 Room 写入与顺序。
 */
object BookUpsert {

    /**
     * 按身份入库。
     *
     * @param incoming 新到的书（换源时是**新源**的那本）
     * @param toc incoming 对应的目录；为空表示只入库书籍本身（不替换章节）
     * @param migrateFrom 当前正在使用的旧记录（换源时传，用于继承阅读进度与用户状态）；
     *                    为 null 时表示这是一次普通入库
     * @return **最终落库的那一本**。合并发生时是 keep（`bookUrl` 为旧记录的身份），
     *         否则是 incoming（或按 migrateFrom 调整后的版本）
     */
    fun upsertByIdentity(
        incoming: Book,
        toc: List<BookChapter> = emptyList(),
        migrateFrom: Book? = null
    ): Book {
        val incomingKey = BookMergeRules.identityKeyOf(incoming)
            ?: run {
                // 不参与身份收敛的书（本地书 / 未入架 / 无书名）走朴素路径。
                return savePlain(incoming, toc, migrateFrom)
            }

        // 找库中的同书。可能有历史残留的多条重复，取「最近阅读」的那条作为 keep，
        // 与手动去重的保留规则保持同一口径。
        val candidates = appDb.bookDao
            .getBooks(incomingKey.name, incomingKey.author)
            .filter {
                BookMergeRules.stableMediaType(it) == incomingKey.mediaType
            }
        val keep = candidates
            .takeIf { it.isNotEmpty() }
            ?.let { BookMergeRules.pickKeeper(it, ::lastReadOf) }

        if (keep == null) return savePlain(incoming, toc, migrateFrom)

        // 同一本书，且身份已是 incoming —— 只更新内容，不做身份替换。
        if (keep.bookUrl == incoming.bookUrl) {
            val merged = migrateFrom
                ?.takeIf { it.bookUrl == keep.bookUrl }
                ?.let { BookMergeRules.mergeInto(keep, incoming, toc) }
                ?: incoming
            return savePlain(merged, toc, migrateFrom = null)
        }

        return merge(keep, incoming, toc, migrateFrom)
    }

    /**
     * 把 [src] 合并进 [keep]，保留 `keep.bookUrl`。
     *
     * 执行顺序是**不可调换**的（每一步都有实证理由，见行内注释）。
     */
    private fun merge(
        keep: Book,
        src: Book,
        toc: List<BookChapter>,
        migrateFrom: Book?
    ): Book {
        // 换源场景下「用户状态与进度」的来源：优先正在读的那本，其次 keep 自身。
        val carrier = migrateFrom?.takeIf { it.bookUrl == keep.bookUrl } ?: keep
        val merged = BookMergeRules.mergeInto(carrier, src, toc).let {
            // mergeInto 返回的是 carrier 的副本，身份必须回到 keep。
            it.bookUrl = keep.bookUrl
            it
        }

        appDb.runInTransaction {
            // ① 搬运 src 侧会被 CASCADE 连带删除的关联数据（必须在删 src 之前）。
            moveShortcuts(src, keep)
            moveCollectionItems(src, keep)
            clearIllustrations(keep)

            // ② 写 keep 本体（身份不变，内容换成新源）。
            appDb.bookDao.update(merged)

            // ③ 章节：「先删后插」不可省。
            //    chapters 有 (bookUrl, index) 唯一索引，且新目录的 index 与旧目录逐条重合，
            //    不先删必然触发 UNIQUE 冲突（插入用 REPLACE 时会静默半覆盖，索引与 URL 交叉错乱）。
            if (toc.isNotEmpty()) {
                appDb.bookChapterDao.delByBook(keep.bookUrl)
                appDb.bookChapterDao.insert(
                    *toc.map { it.copy(bookUrl = keep.bookUrl) }.toTypedArray()
                )
            }

            // ④ 搬运「最近阅读」记录：保留两条中更晚的时间，再删掉 src 那一行。
            //    readRecentBooks 是普通表（无外键），删 src 时不会被级联，
            //    残留的孤儿行会永久抬高 latestReadTime()，让阅读记录页每次 onResume 都全量重载。
            moveRecentRead(keep, src)

            // ⑤ 删 src —— 必须最后，否则 ① 已无从搬运。
            appDb.bookDao.delete(src)

            // ⑥ 清理无外键的孤儿记录（源已失效，留着只是垃圾）。
            AiChapterPurifyService.dropBookRecords(src)
            appDb.readRecentBookDao.delete(src.bookUrl)
        }

        // 阅读引擎状态重定向：src 被删了，但当前在读的可能正是 src。
        // ⚠️ 用 resetData 而不是裸赋值：它还会取消在途任务、清朗读位置、清已下载章节集合。
        ReadBook.book?.let {
            if (it.bookUrl == src.bookUrl || it.bookUrl == keep.bookUrl) {
                ReadBook.resetData(merged)
            }
        }
        return merged
    }

    /** 朴素入库：不做身份收敛，按 bookUrl 覆盖写。 */
    private fun savePlain(book: Book, toc: List<BookChapter>, migrateFrom: Book?): Book {
        val target = if (migrateFrom != null && migrateFrom.bookUrl != book.bookUrl) {
            // 换源但未命中同书（例如旧记录不在架）：沿用旧记录的用户状态。
            BookMergeRules.mergeInto(migrateFrom, book, toc).let {
                it.bookUrl = book.bookUrl
                it
            }
        } else {
            book
        }
        appDb.runInTransaction {
            // 已存在同 bookUrl 时必须 update：bookDao.insert 是 OnConflictStrategy.REPLACE，
            // 冲突时的隐式 DELETE 旧行会触发子表 CASCADE，级联清空章节/快捷入口/合集/配图。
            if (appDb.bookDao.has(target.bookUrl)) {
                appDb.bookDao.update(target)
            } else {
                appDb.bookDao.insert(target)
            }
            if (toc.isNotEmpty()) {
                appDb.bookChapterDao.delByBook(target.bookUrl)
                appDb.bookChapterDao.insert(
                    *toc.map { it.copy(bookUrl = target.bookUrl) }.toTypedArray()
                )
            }
        }
        return target
    }

    private fun moveShortcuts(src: Book, keep: Book) {
        if (src.isLocal || keep.isLocal) return
        // book_shortcuts 持有书架上的 group/order，是用户手摆的位置；任由 CASCADE 删除
        // 会让用户的书架排布无提示清空。
        val srcShortcuts = appDb.bookShortcutDao.getByBookUrl(src.bookUrl)
        if (srcShortcuts.isEmpty()) return
        // keep 已有同书快捷方式时不重复挂载（保留 keep 原有的那条）。
        if (appDb.bookShortcutDao.hasByBookUrl(keep.bookUrl)) {
            appDb.bookShortcutDao.delete(srcShortcuts.map { it.shortcutId })
            return
        }
        // 只搬运第一条，其余作为重复条目丢弃 —— 书架同一本书只应有一个入口。
        val first = srcShortcuts.minByOrNull { it.order } ?: return
        appDb.bookShortcutDao.update(first.copy(bookUrl = keep.bookUrl))
        appDb.bookShortcutDao.delete(
            srcShortcuts.filter { it.shortcutId != first.shortcutId }.map { it.shortcutId }
        )
    }

    private fun moveCollectionItems(src: Book, keep: Book) {
        val items = appDb.bookCollectionDao.getItemsByBookUrl(src.bookUrl)
        items.forEach { item ->
            // 主键是 (collectionId, bookUrl)；同合集内重复时 IGNORE 会自动跳过，不必先查后插。
            appDb.bookCollectionDao.insertItems(
                listOf(item.copy(bookUrl = keep.bookUrl))
            )
        }
        appDb.bookCollectionDao.deleteItemsByBookUrls(listOf(src.bookUrl))
    }

    private fun clearIllustrations(keep: Book) {
        // book_illustrations 按 (bookUrl, chapterIndex) 锚定，换源后章节序号漂移，
        // 旧配图会挂到错误章节；先清后由新源按需重建。
        appDb.bookIllustrationDao.deleteByBook(keep.bookUrl)
    }

    private fun moveRecentRead(keep: Book, src: Book) {
        val srcLastRead = lastReadOf(src.bookUrl)
        val keepLastRead = lastReadOf(keep.bookUrl)
        if (srcLastRead != null) {
            appDb.readRecentBookDao.insert(
                ReadRecentBook(keep.bookUrl, maxOf(srcLastRead, keepLastRead ?: 0L))
            )
        }
    }

    /** 读「最近打开阅读」时钟；这是唯一可靠的判据（`durChapterTime` 会被与阅读无关的动作污染）。 */
    private fun lastReadOf(bookUrl: String): Long? =
        appDb.readRecentBookDao.getByBookUrl(bookUrl)?.lastRead
}
