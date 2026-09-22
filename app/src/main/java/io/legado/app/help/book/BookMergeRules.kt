package io.legado.app.help.book

import io.legado.app.constant.BookMediaType
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter

/**
 * 书籍身份匹配与「同一本书」合并的纯逻辑层。
 *
 * 背景：`Book` 表主键是 `bookUrl`（详情页地址），而同一本书在不同书源下 `bookUrl` 不同，
 * 于是换源会在书架上留下两条记录。本对象把「同一本书」的判据收敛为
 * **书名 + 作者 + 媒体类型** 三元组（`BookMediaType` 只取稳定媒体位，不含可变状态位）。
 *
 * ⚠️ 本文件**不碰数据库、不依赖 Android 资源**，以便在 JVM 单测里直接覆盖分组与字段裁决。
 * 一切 Room 写入都在 [BookUpsert] 的事务里完成。
 */
object BookMergeRules {

    /**
     * 同一本书的判据键。
     *
     * @param name 书名（原样，不做 trim —— 判据必须与 `BookDao.getBook(name,author,mediaType)` 一致）
     * @param author 作者；空作者按空串参与判据（产品决定：不做额外守卫）
     * @param mediaType 稳定媒体类型，取 [_stableMediaType]
     */
    data class IdentityKey(val name: String, val author: String, val mediaType: Int)

    /**
     * 取书籍的**稳定媒体身份**。
     *
     * ⚠️ 不能直接用 [BookMediaType.fromBookType]：它只按固定优先级塌缩媒体位，
     * 会把「本地 txt」与「网络 txt」都算成 [BookMediaType.text]（`BookType.local` 不参与映射）。
     * 本地书参与合并会导致 [Book.delete] 删掉本地文件的持久化资源，故此处直接把本地书判为「不参与」。
     *
     * @return 媒体类型；返回 null 表示这本书不参与合并
     */
    fun stableMediaType(book: Book): Int? {
        // 本地书（含 webDav）不参与合并：删它会连带清掉本地书文件资源。
        if (book.isLocal || book.isArchive) return null
        // 未正式入架的临时阅读书不参与：避免把试读书记进正式书架。
        if (book.isType(BookType.notShelf)) return null
        // 书名为空无法作为身份判据。
        if (book.name.isBlank()) return null
        return BookMediaType.fromBookType(book.type)
    }

    /** 取身份键；不参与合并时返回 null。 */
    fun identityKeyOf(book: Book): IdentityKey? {
        val mediaType = stableMediaType(book) ?: return null
        return IdentityKey(book.name, book.author, mediaType)
    }

    /**
     * 把一批书按身份键分组，只保留真正重复（组内 size > 1）的组。
     * 组内顺序保持输入顺序，便于上层做稳定展示。
     */
    fun duplicateGroups(books: List<Book>): List<List<Book>> {
        val grouped = LinkedHashMap<IdentityKey, MutableList<Book>>()
        books.forEach { book ->
            val key = identityKeyOf(book) ?: return@forEach
            grouped.getOrPut(key) { mutableListOf() }.add(book)
        }
        return grouped.values.filter { it.size > 1 }
    }

    /**
     * 从一组同书里选出保留项。
     *
     * 判据按优先级：
     * 1. [lastReadOf] 有记录的，取最大者 —— 这是「最近打开阅读」的唯一可靠时钟。
     *    ⚠️ 刻意不用 `Book.durChapterTime`：它会被**与阅读无关**的动作推高
     *    （详情页「置顶」`BookInfoViewModel.topBook`、字段默认值 = 加入时间、云同步回写），
     *    用它会导致「刚置顶过但从未打开」的书顶掉真正在读的那本。
     * 2. 都没有阅读记录时，回退 `durChapterTime` 最大者。
     * 3. 仍相同则保持输入顺序的第一个（稳定，不随遍历顺序抖动）。
     */
    fun pickKeeper(group: List<Book>, lastReadOf: (String) -> Long?): Book {
        require(group.isNotEmpty()) { "empty group cannot pick keeper" }
        return group.maxByOrNull { lastReadOf(it.bookUrl) ?: Long.MIN_VALUE }
            ?.takeIf { lastReadOf(it.bookUrl) != null }
            ?: group.maxByOrNull { it.durChapterTime }
            ?: group.first()
    }

    /**
     * 字段裁决：把 [src]（新书源的那本）合并进 [keep]（保留身份的那本），返回应写入数据库的对象。
     *
     * ⚠️ **不修改入参**：`keep`/`src` 都是调用方持有的可变对象（`Book` 是 data class 但字段是 var），
     * 就地改写会让调用方的 UI 状态与库内容脱节。此处一律在副本上做。
     *
     * 裁决口径（与既有 [Book.updateTo] / [Book.migrateTo] 保持同一语义，避免出现第三套搬运规则）：
     * - **书源身份**（`tocUrl`/`origin`/`originName`/`kind`/`coverUrl`/`intro`/媒体位/元数据）← src，这就是「换源」
     * - **用户状态**（`group`/`order`/自定义封面简介标签/`readConfig`/`canUpdate`/`syncTime`）← keep
     * - **分组取并集**：`group` 是位掩码，取 keep 会静默丢掉被并书所在的分组
     * - **阅读进度**：按新目录重算（章节号在换源后会漂移），时间戳取较大者
     */
    /**
     * **恢复专用**的保守合并：把备份里的书 [backup] 并入本机记录 [local]，返回应写库的对象。
     *
     * 与 [mergeInto] 的区别（**两者不可互相替代**，恢复场景不适用换源语义）：
     * - **身份一律取本机**：`bookUrl`/`origin`/`originName`/`tocUrl`/`variable` 全部保持 [local]。
     *   ⚠️ 特别是 `variable` —— [mergeInto] 取 src（换源后新源的脚本状态），但恢复不动 `origin`，
     *   本机源脚本的变量必须随本机，否则书源脚本状态与 `origin` 错配。
     * - **不使用备份的目录索引**：备份的 `durChapterIndex` 锚定在**备份源的目录**上，
     *   与本机目录**不可比**，直接搬用会跳到错误章节。此处按 [toc]（本机目录）重定位。
     * - **绝不删除任何本机数据**（调用方据此可安全地不清配图、不删记录）。
     *
     * @param toc 本机该书当前的目录；为空表示无法定位，此时进度**一律保持本机**
     */
    fun mergeFromBackup(local: Book, backup: Book, toc: List<BookChapter>): Book {
        val merged = local.copy()

        // ---- 用户可编辑内容：本机为空时用备份补（不覆盖用户在本机的填写） ----
        merged.customTag = local.customTag ?: backup.customTag
        merged.customIntro = local.customIntro ?: backup.customIntro
        merged.customCoverUrl = local.customCoverUrl ?: backup.customCoverUrl
        if (local.readConfig == null) merged.readConfig = backup.readConfig
        if (!local.canUpdate) merged.canUpdate = backup.canUpdate

        // ---- 分组：位掩码取并集，否则被并书所在的分组会被静默丢弃 ----
        merged.group = local.group or backup.group

        // ---- 同步时钟：取较晚者。syncTime 参与 WebDAV 进度同步判定，
        //      取小值会让本机新状态被误判为「无更新」而跳过同步 ----
        merged.syncTime = maxOf(local.syncTime, backup.syncTime)

        // ---- 阅读进度：只有能锚定到本机目录时才采纳备份进度 ----
        if (toc.isNotEmpty()) {
            val index = ChapterLocator.findChapterIndex(
                backup.durChapterIndex,
                backup.durChapterTitle,
                toc,
                backup.totalChapterNum
            ).coerceIn(0, toc.size - 1)
            // 仅当备份确实读得更靠后时才采纳，避免把本机进度倒退回去
            val localIndex = local.durChapterIndex.coerceIn(0, toc.size - 1)
            if (index > localIndex) {
                merged.durChapterIndex = index
                merged.durChapterTitle = toc[index].title
                val (volumeIndex, inVolumeIndex) = volumePositionOf(toc, index)
                merged.durVolumeIndex = volumeIndex
                merged.chapterInVolumeIndex = inVolumeIndex
                merged.durChapterPos = backup.durChapterPos
            } else if (index == localIndex) {
                // 同一章：位置取较靠后者
                merged.durChapterPos = maxOf(local.durChapterPos, backup.durChapterPos)
            }
        }
        merged.durChapterTime = maxOf(local.durChapterTime, backup.durChapterTime)

        // 其余字段（书源身份、元数据、order、totalChapterNum、进度索引等）一律保持 local，
        // 故 merged 由 local.copy() 直接带出，此处不逐字段覆盖即为正确。
        return merged
    }

    fun mergeInto(keep: Book, src: Book, toc: List<BookChapter>): Book {
        val merged = keep.copy()

        // ---- 书源身份：以新源为准 -------------------------------------------------
        merged.tocUrl = src.tocUrl
        merged.origin = src.origin
        merged.originName = src.originName
        merged.name = src.name
        merged.author = src.author
        merged.kind = src.kind
        merged.coverUrl = src.coverUrl
        merged.intro = src.intro
        merged.latestChapterTitle = src.latestChapterTitle
        merged.latestChapterTime = src.latestChapterTime
        merged.totalChapterNum = src.totalChapterNum
        merged.wordCount = src.wordCount
        merged.originOrder = src.originOrder
        merged.lastCheckTime = src.lastCheckTime
        merged.lastCheckCount = 0
        merged.charset = src.charset
        merged.variable = src.variable
        // 媒体位取 src，但保住 keep 的语义位（local / notShelf / archive 等）。
        // ⚠️ 这里必须先把 keep 的非媒体位摘出来，否则 src 的 notShelf/updateError 会污染 keep。
        merged.type = src.type and BookType.allBookType or
                (keep.type and BookType.allBookType.inv())
        merged.type = merged.type and BookType.updateError.inv()
        merged.syncMediaType()

        // ---- 用户状态：以 keep 为准 ---------------------------------------------
        // group 是位掩码，必须取并集，否则被并书所在的分组会被静默丢弃。
        merged.group = keep.group or src.group
        merged.order = keep.order
        merged.customCoverUrl = keep.customCoverUrl ?: src.customCoverUrl
        merged.customIntro = keep.customIntro ?: src.customIntro
        merged.customTag = keep.customTag ?: src.customTag
        merged.readConfig = keep.readConfig
        merged.canUpdate = keep.canUpdate
        merged.syncTime = keep.syncTime
        // ⚠️ 必须清掉记忆化的 folderName：`getFolderName()` 会把「书名前9位 + md5(bookUrl)」
        // 缓存进 `@Ignore` 字段，而 keep.copy() 把这个缓存一并复制了过来。
        // 合并保留 keep.bookUrl，故这里其实与缓存值相同；但换源会让 **name** 变成 src 的，
        // 缓存值仍按旧 name 计算 —— 一旦不清，缓存目录名会与 `getFolderNameNoCache()` 脱节。
        merged.invalidateFolderName()

        // ---- 阅读进度：按新目录重算 ---------------------------------------------
        applyProgress(merged, keep, src, toc)
        return merged
    }

    /**
     * 重算阅读进度。
     *
     * ⚠️ `BookHelp.getDurChapter` 只返回章节序号，**不返回卷内序号**；
     * 现有 [Book.migrateTo] 与 [Book.updateTo] 都漏了 `durVolumeIndex` / `chapterInVolumeIndex`
     * （属既有缺陷）。这里一并重算，否则卷内定位会错位。
     */
    private fun applyProgress(merged: Book, keep: Book, src: Book, toc: List<BookChapter>) {
        if (toc.isNotEmpty()) {
            val index = ChapterLocator.findChapterIndex(
                keep.durChapterIndex,
                keep.durChapterTitle,
                toc,
                keep.totalChapterNum
            )
            val safeIndex = index.coerceIn(0, toc.size - 1)
            merged.durChapterIndex = safeIndex
            merged.durChapterTitle = toc[safeIndex].title
            val (volumeIndex, inVolumeIndex) = volumePositionOf(toc, safeIndex)
            merged.durVolumeIndex = volumeIndex
            merged.chapterInVolumeIndex = inVolumeIndex
        }
        // 进度位置取 keep（用户实际读到哪），时间取较大者（不因换源让"最近阅读"倒退）
        merged.durChapterPos = keep.durChapterPos
        merged.durChapterTime = maxOf(keep.durChapterTime, src.durChapterTime)
    }

    /**
     * 计算章节 [chapterIndex] 所在的卷序号与卷内序号。
     *
     * 目录里的卷名章节（`isVolume == true`）充当分隔符；`durVolumeIndex` 记「第几个卷」，
     * `chapterInVolumeIndex` 记「卷内第几章」。与新增书籍时的口径保持一致。
     */
    fun volumePositionOf(toc: List<BookChapter>, chapterIndex: Int): Pair<Int, Int> {
        var volumeIndex = 0
        var inVolumeIndex = 0
        for (i in 0..chapterIndex.coerceAtMost(toc.lastIndex)) {
            if (toc[i].isVolume) {
                volumeIndex += 1
                inVolumeIndex = 0
            } else {
                inVolumeIndex += 1
            }
        }
        return volumeIndex to inVolumeIndex
    }
}
