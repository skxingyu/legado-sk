package io.legado.app.help.book

import io.legado.app.constant.BookMediaType
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [BookMergeRules] 的身份判据、保留项选择、字段裁决与进度重算。
 *
 * 这些用例都是可回归的缺陷锁：删掉对应守卫会让用例失败。
 */
class BookMergeRulesTest {

    private fun book(
        bookUrl: String,
        name: String = "同名书",
        author: String = "同作者",
        type: Int = BookType.text,
        group: Long = 0L,
        durChapterTime: Long = 0L
    ) = Book(
        bookUrl = bookUrl,
        name = name,
        author = author,
        type = type,
        group = group,
        durChapterTime = durChapterTime
    )

    private fun chapter(index: Int, title: String = "第${index}章", isVolume: Boolean = false) =
        BookChapter(url = "u$index", title = title, bookUrl = "b", index = index, isVolume = isVolume)

    // ---- 身份判据 -------------------------------------------------------------

    @Test
    fun `same name author and media type share one identity`() {
        val a = book("url-a")
        val b = book("url-b")
        assertEquals(BookMergeRules.identityKeyOf(a), BookMergeRules.identityKeyOf(b))
    }

    @Test
    fun `different media type is not the same book`() {
        val textBook = book("url-a", type = BookType.text)
        val audioBook = book("url-b", type = BookType.audio)
        assertNotEquals(
            BookMergeRules.identityKeyOf(textBook),
            BookMergeRules.identityKeyOf(audioBook)
        )
    }

    /**
     * 本地 txt 与网络 txt 的 mediaType 都会被算成 text，若不做守卫会被判为同一本。
     * 本地书被并入会连带删除本地文件的持久化资源，是数据破坏，因此必须排除。
     */
    @Test
    fun `local book never participates even when name and author match`() {
        val localBook = book("content://doc/local.txt", type = BookType.text or BookType.local)
        val onlineBook = book("https://site/book/1", type = BookType.text)
        assertNull(BookMergeRules.identityKeyOf(localBook))
        assertNotNull(BookMergeRules.identityKeyOf(onlineBook))
    }

    @Test
    fun `archive book never participates`() {
        val archiveBook = book("url-a", type = BookType.text or BookType.archive)
        assertNull(BookMergeRules.identityKeyOf(archiveBook))
    }

    /** 未正式入架的临时阅读书不应被卷进书架去重。 */
    @Test
    fun `not shelf book never participates`() {
        val tempBook = book("url-a", type = BookType.text or BookType.notShelf)
        assertNull(BookMergeRules.identityKeyOf(tempBook))
    }

    @Test
    fun `blank name never participates`() {
        assertNull(BookMergeRules.identityKeyOf(book("url-a", name = "")))
        assertNull(BookMergeRules.identityKeyOf(book("url-a", name = "   ")))
    }

    // ---- 分组 -----------------------------------------------------------------

    @Test
    fun `duplicate groups only contain groups larger than one`() {
        val groups = BookMergeRules.duplicateGroups(
            listOf(
                book("a1", name = "甲"),
                book("a2", name = "甲"),
                book("b1", name = "乙"),
                book("c1", name = "丙", type = BookType.audio)
            )
        )
        assertEquals(1, groups.size)
        assertEquals(listOf("a1", "a2"), groups.single().map { it.bookUrl })
    }

    @Test
    fun `local and online books of the same name never group together`() {
        val groups = BookMergeRules.duplicateGroups(
            listOf(
                book("content://doc/x.txt", name = "甲", type = BookType.text or BookType.local),
                book("https://s/1", name = "甲", type = BookType.text)
            )
        )
        assertEquals(emptyList<List<Book>>(), groups)
    }

    // ---- 保留项 ---------------------------------------------------------------

    /**
     * 判据必须是「最近打开阅读」，不能用 durChapterTime：
     * 后者会被详情页「置顶」等与阅读无关的动作刷成当前时间。
     */
    @Test
    fun `keeper is the most recently read one not the newest durChapterTime`() {
        val actuallyRead = book("read-book", durChapterTime = 1_000L)
        val onlyTopped = book("topped-book", durChapterTime = 9_999_999L)
        val lastRead = mapOf("read-book" to 5_000L, "topped-book" to 10L)
        val keeper = BookMergeRules.pickKeeper(listOf(onlyTopped, actuallyRead)) {
            lastRead[it]
        }
        assertEquals("read-book", keeper.bookUrl)
    }

    @Test
    fun `keeper falls back to durChapterTime when nothing was ever read`() {
        val older = book("older", durChapterTime = 100L)
        val newer = book("newer", durChapterTime = 200L)
        assertEquals("newer", BookMergeRules.pickKeeper(listOf(older, newer)) { null }.bookUrl)
    }

    @Test
    fun `keeper is stable when neither signal distinguishes the group`() {
        val first = book("first")
        val second = book("second")
        assertEquals("first", BookMergeRules.pickKeeper(listOf(first, second)) { null }.bookUrl)
    }

    // ---- 字段裁决 -------------------------------------------------------------

    @Test
    fun `merge takes source identity but keeps user state`() {
        val keep = book("keep-url", group = 0b10L).apply {
            customCoverUrl = "cover-keep"
            readConfig = Book.ReadConfig(ttsEngine = "engine-keep")
            canUpdate = false
            syncTime = 123L
            order = 7
        }
        val src = book("src-url", group = 0b100L).apply {
            origin = "https://newsource"
            originName = "新源"
            tocUrl = "https://newsource/toc"
            coverUrl = "cover-src"
            intro = "新简介"
        }

        val merged = BookMergeRules.mergeInto(keep, src, listOf(chapter(0)))

        // 书源身份来自 src
        assertEquals("https://newsource", merged.origin)
        assertEquals("新源", merged.originName)
        assertEquals("https://newsource/toc", merged.tocUrl)
        assertEquals("封面" to "cover-src", "封面" to merged.coverUrl)
        assertEquals("新简介", merged.intro)
        // 用户状态来自 keep
        assertEquals("cover-keep", merged.customCoverUrl)
        assertEquals("engine-keep", merged.readConfig?.ttsEngine)
        assertEquals(false, merged.canUpdate)
        assertEquals(123L, merged.syncTime)
        assertEquals(7, merged.order)
    }

    /** group 是位掩码，取 keep 会静默丢掉被并书所在的分组。 */
    @Test
    fun `group is unioned so merged book keeps both memberships`() {
        val keep = book("keep-url", group = 0b10L)
        val src = book("src-url", group = 0b100L)
        val merged = BookMergeRules.mergeInto(keep, src, listOf(chapter(0)))
        assertEquals(0b110L, merged.group)
    }

    @Test
    fun `merge never mutates its inputs`() {
        val keep = book("keep-url", group = 0b10L)
        val src = book("src-url", group = 0b100L).apply { origin = "https://new" }
        BookMergeRules.mergeInto(keep, src, listOf(chapter(0)))
        assertEquals(0b10L, keep.group)
        assertEquals(0b100L, src.group)
    }

    @Test
    fun `media type stays consistent with type after merge`() {
        val keep = book("keep-url", type = BookType.text)
        val src = book("src-url", type = BookType.audio)
        val merged = BookMergeRules.mergeInto(keep, src, listOf(chapter(0)))
        assertEquals(BookMediaType.audio, merged.mediaType)
        assertEquals(BookMediaType.fromBookType(merged.type), merged.mediaType)
    }

    /** keep 的非媒体语义位（本地/未入架）不能被 src 的媒体位冲掉。 */
    @Test
    fun `merge preserves keep semantic bits outside media type`() {
        val keep = book("keep-url", type = BookType.text or BookType.local)
        val src = book("src-url", type = BookType.text)
        val merged = BookMergeRules.mergeInto(keep, src, listOf(chapter(0)))
        assertEquals(true, merged.isLocal)
    }

    @Test
    fun `merge clears updateError flag`() {
        val keep = book("keep-url", type = BookType.text or BookType.updateError)
        val src = book("src-url", type = BookType.text)
        val merged = BookMergeRules.mergeInto(keep, src, listOf(chapter(0)))
        assertEquals(false, merged.isType(BookType.updateError))
    }

    /**
     * `getFolderName()` 的记忆化缓存由「书名 + bookUrl」决定。合并会改 `name`，
     * 若不清缓存，后续会返回按旧书名算出的目录名，与落盘目录脱节。
     */
    @Test
    fun `merge clears folder name cache so it is recomputed`() {
        val keep = book("keep-url", name = "旧名")
        assertEquals(keep.getFolderNameNoCache(), keep.getFolderName()) // 预热记忆化缓存
        val src = book("src-url", name = "新书名")
        val merged = BookMergeRules.mergeInto(keep, src, listOf(chapter(0)))
        // 缓存已失效 → 重新按合并后的 name 计算
        assertEquals("新书名" + io.legado.app.utils.MD5Utils.md5Encode16("keep-url"),
            merged.getFolderName())
        assertNotEquals(keep.getFolderName(), merged.getFolderName())
    }

    // ---- 进度重算 -------------------------------------------------------------

    @Test
    fun `merge recomputes volume position together with chapter index`() {
        val toc = listOf(
            chapter(0, "第一卷", isVolume = true),
            chapter(1, "第1章"),
            chapter(2, "第2章"),
            chapter(3, "第二卷", isVolume = true),
            chapter(4, "第3章")
        )
        val keep = book("keep-url").apply {
            durChapterIndex = 4
            durChapterPos = 42
        }
        val merged = BookMergeRules.mergeInto(keep, book("src-url"), toc)
        assertEquals(4, merged.durChapterIndex)
        assertEquals(2, merged.durVolumeIndex)
        assertEquals(1, merged.chapterInVolumeIndex)
        assertEquals(42, merged.durChapterPos)
    }

    @Test
    fun `volume position counts volumes and chapters within them`() {
        val toc = listOf(
            chapter(0, "第1章"),
            chapter(1, "第一卷", isVolume = true),
            chapter(2, "第2章"),
            chapter(3, "第3章")
        )
        // 卷名章节之前的正文属于「第 0 卷」，卷内计数照常累加
        assertEquals(0 to 1, BookMergeRules.volumePositionOf(toc, 0))
        // 卷名章节本身开启新卷，卷内计数归零
        assertEquals(1 to 0, BookMergeRules.volumePositionOf(toc, 1))
        assertEquals(1 to 2, BookMergeRules.volumePositionOf(toc, 3))
    }

    @Test
    fun `merge keeps the later read time so recent reading never goes backwards`() {
        val keep = book("keep-url", durChapterTime = 5_000L)
        val src = book("src-url", durChapterTime = 1_000L)
        assertEquals(5_000L, BookMergeRules.mergeInto(keep, src, emptyList()).durChapterTime)
        assertEquals(
            5_000L,
            BookMergeRules.mergeInto(src, keep, emptyList()).durChapterTime
        )
    }

    @Test
    fun `merge without toc leaves progress untouched`() {
        val keep = book("keep-url").apply {
            durChapterIndex = 3
            durChapterPos = 9
        }
        val merged = BookMergeRules.mergeInto(keep, book("src-url"), emptyList())
        assertEquals(3, merged.durChapterIndex)
        assertEquals(9, merged.durChapterPos)
    }

    // ---- 手动「去重」时选「书源提供方」与「目录」的口径 -------------------------

    /**
     * 去重时被并的重复项可能不止一条，必须挑一本作为「新书源身份」的来源。
     * 口径与保留项**互不相关**：保留项看阅读记录，书源来源看 durChapterTime
     * （最新刷新的那份目录），否则可能把保留项的书源换成一本过期记录。
     */
    @Test
    fun `merge donor should be the newest duplicate not the keeper`() {
        val keeper = book("keeper", durChapterTime = 1_000L)
        val staleDup = book("dup-old", durChapterTime = 2_000L)
        val freshDup = book("dup-new", durChapterTime = 9_000L)
        // 书源来源：最近刷新的那份
        assertEquals("dup-new", listOf(staleDup, freshDup).maxByOrNull { it.durChapterTime }?.bookUrl)
        // 保留项：有阅读记录时只认阅读记录，与 durChapterTime 无关
        val lastRead = mapOf("keeper" to 5_000L, "dup-old" to 10L, "dup-new" to 20L)
        assertEquals(
            "keeper",
            BookMergeRules.pickKeeper(listOf(keeper, staleDup, freshDup)) { lastRead[it] }.bookUrl
        )
    }

    /**
     * 重复记录的目录常有一边是空的。取「最全的那份」，
     * 否则会把保留项已有的完整目录覆盖成残缺目录。
     */
    @Test
    fun `best toc is the longest one among duplicates`() {
        val empty: List<BookChapter> = emptyList()
        val partial = listOf(chapter(0), chapter(1))
        val full = listOf(chapter(0), chapter(1), chapter(2), chapter(3))
        val best = listOf(empty, partial, full).maxByOrNull { it.size }
        assertEquals(4, best?.size)
    }

    // ---- 恢复专用保守合并（mergeFromBackup） ---------------------------------

    /**
     * 决策：恢复命中同书时**保留本机书源身份**。备份的 origin/tocUrl/variable 一律不得写进本机。
     */
    @Test
    fun `restore merge keeps local source identity`() {
        val local = book("local-url").apply {
            origin = "https://local-source"
            originName = "本机源"
            tocUrl = "https://local-source/toc"
            variable = "local-variable"
            order = 7
            totalChapterNum = 100
        }
        val backup = book("backup-url").apply {
            origin = "https://backup-source"
            originName = "备份源"
            tocUrl = "https://backup-source/toc"
            variable = "backup-variable"
            order = 99
            totalChapterNum = 200
        }

        val merged = BookMergeRules.mergeFromBackup(local, backup, emptyList())

        assertEquals("local-url", merged.bookUrl)
        assertEquals("https://local-source", merged.origin)
        assertEquals("本机源", merged.originName)
        assertEquals("https://local-source/toc", merged.tocUrl)
        assertEquals("local-variable", merged.variable)
        assertEquals(7, merged.order)
        assertEquals(100, merged.totalChapterNum)
    }

    /**
     * ⚠️ 与本文件 `mergeInto` 的裁决**相反**：换源时 variable ← src，恢复时必须 ← 本机。
     * 两者不可互相替代，此用例锁定这个差异。
     */
    @Test
    fun `restore merge takes local variable while mergeInto takes source variable`() {
        val local = book("local-url").apply { variable = "local-variable" }
        val backup = book("backup-url").apply { variable = "backup-variable" }

        assertEquals(
            "恢复必须保留本机变量（origin 不动，变量须随本机源）",
            "local-variable",
            BookMergeRules.mergeFromBackup(local, backup, emptyList()).variable
        )
        assertEquals(
            "换源语义应取新源变量（对照项）",
            "backup-variable",
            BookMergeRules.mergeInto(local, backup, emptyList()).variable
        )
    }

    @Test
    fun `restore merge unions group and takes later sync time`() {
        val local = book("local-url", group = 0b10L).apply { syncTime = 100L }
        val backup = book("backup-url", group = 0b100L).apply { syncTime = 500L }

        val merged = BookMergeRules.mergeFromBackup(local, backup, emptyList())

        assertEquals("分组是位掩码，必须取并集", 0b110L, merged.group)
        assertEquals("syncTime 取较晚者", 500L, merged.syncTime)
    }

    @Test
    fun `restore merge fills empty user fields from backup but never overwrites`() {
        val local = book("local-url").apply {
            customTag = "本机标签"
            customIntro = null
            customCoverUrl = null
        }
        val backup = book("backup-url").apply {
            customTag = "备份标签"
            customIntro = "备份简介"
            customCoverUrl = "备份封面"
        }

        val merged = BookMergeRules.mergeFromBackup(local, backup, emptyList())

        assertEquals("本机已填则不覆盖", "本机标签", merged.customTag)
        assertEquals("本机为空则用备份补", "备份简介", merged.customIntro)
        assertEquals("备份封面", merged.customCoverUrl)
    }

    /**
     * 恢复场景**必须**用本机目录重定位备份进度：备份的 durChapterIndex 锚定在备份源目录上，
     * 与本机目录不可比，直接搬用会跳到错误章节。
     */
    @Test
    fun `restore merge relocates backup progress onto local toc by title`() {
        val local = book("local-url").apply { durChapterIndex = 0 }
        val backup = book("backup-url").apply {
            durChapterIndex = 2
            durChapterTitle = "第3章"
            totalChapterNum = 3
        }
        val localToc = (0..9).map { chapter(it, "第${it + 1}章") }

        val merged = BookMergeRules.mergeFromBackup(local, backup, localToc)

        assertEquals("应重定位到本机目录里的同名章节", 2, merged.durChapterIndex)
        assertEquals("第3章", merged.durChapterTitle)
    }

    /** 本机目录为空时无法定位，进度必须保持本机，不得搬用备份索引（可能越界）。 */
    @Test
    fun `restore merge keeps local progress when local toc is empty`() {
        val local = book("local-url").apply {
            durChapterIndex = 1
            durChapterPos = 50
        }
        val backup = book("backup-url").apply {
            durChapterIndex = 99
            durChapterPos = 999
        }

        val merged = BookMergeRules.mergeFromBackup(local, backup, emptyList())

        assertEquals("无本机目录时不得采纳备份索引", 1, merged.durChapterIndex)
        assertEquals(50, merged.durChapterPos)
    }

    /** 备份进度靠后时采纳；本机靠后时不得倒退。 */
    @Test
    fun `restore merge never rewinds local progress`() {
        val localToc = (0..9).map { chapter(it, "第${it + 1}章") }

        val localAhead = book("local-url").apply {
            durChapterIndex = 5
            durChapterTitle = "第6章"
            durChapterPos = 500
        }
        val backupBehind = book("backup-url").apply {
            durChapterIndex = 1
            durChapterTitle = "第2章"
            totalChapterNum = 10
        }
        val merged = BookMergeRules.mergeFromBackup(localAhead, backupBehind, localToc)
        assertEquals("本机读得更远时不得倒退", 5, merged.durChapterIndex)
        assertEquals(500, merged.durChapterPos)

        val localBehind = book("local-url").apply {
            durChapterIndex = 1
            durChapterTitle = "第2章"
            durChapterPos = 100
        }
        val backupAhead = book("backup-url").apply {
            durChapterIndex = 5
            durChapterTitle = "第6章"
            totalChapterNum = 10
        }
        val forward = BookMergeRules.mergeFromBackup(localBehind, backupAhead, localToc)
        assertEquals("备份读得更远时应采纳", 5, forward.durChapterIndex)
    }

    /** 不修改入参：调用方仍持有原对象驱动 UI。 */
    @Test
    fun `restore merge does not mutate its arguments`() {
        val local = book("local-url").apply { group = 0b10L }
        val backup = book("backup-url").apply { group = 0b100L }

        BookMergeRules.mergeFromBackup(local, backup, emptyList())

        assertEquals(0b10L, local.group)
        assertEquals(0b100L, backup.group)
    }
}
