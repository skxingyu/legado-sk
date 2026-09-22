package io.legado.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookIllustration
import io.legado.app.help.book.BookMergeRules
import io.legado.app.help.storage.Restore
import io.legado.app.utils.GSON
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 恢复身份收敛的真机（模拟器）端到端验证：走**真实** `Restore.restoreLocked`，
 * 用真实 Room 数据库与外键约束，验证「两设备同一本书选了不同书源，恢复后书架不并列两条」。
 *
 * 这是对 `BookMergeRules.mergeFromBackup` 单测的补充：单测证明纯逻辑，本测试证明
 * 接入真实 DB 后（含 `PRAGMA foreign_keys = ON` 的级联与约束）行为正确。
 */
@RunWith(AndroidJUnit4::class)
class RestoreIdentityInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val localUrl = "https://sourceY.com/book/1"
    private val backupUrl = "https://sourceX.com/book/9"
    private val backupDir = File(context.filesDir, "test_restore_backup")

    @Before
    fun setUp() {
        cleanup()
        // 本机已有源 Y 的同书 + 一条配图
        appDb.bookDao.insert(
            Book(
                bookUrl = localUrl,
                tocUrl = "https://sourceY.com/toc/1",
                origin = "https://sourceY.com",
                originName = "源Y",
                name = "测试书A",
                author = "测试作者",
                durChapterIndex = 0,
                durChapterTime = 1000
            )
        )
        appDb.bookIllustrationDao.insert(
            BookIllustration(bookUrl = localUrl, chapterIndex = 0, note = "本机配图")
        )
    }

    @After
    fun tearDown() = cleanup()

    private fun cleanup() {
        appDb.bookDao.getBook(localUrl)?.let { appDb.bookDao.delete(it) }
        appDb.bookDao.getBook(backupUrl)?.let { appDb.bookDao.delete(it) }
        backupDir.deleteRecursively()
    }

    /** 写入一份模拟设备 A 的备份：同书但来自源 X。 */
    private fun writeBackup(): String {
        backupDir.mkdirs()
        val backupBook = Book(
            bookUrl = backupUrl,
            tocUrl = "https://sourceX.com/toc/9",
            origin = "https://sourceX.com",
            originName = "源X",
            name = "测试书A",
            author = "测试作者",
            durChapterIndex = 0,
            durChapterTime = 5000
        )
        File(backupDir, "bookshelf.json").writeText(GSON.toJson(listOf(backupBook)))
        return backupDir.absolutePath
    }

    /**
     * 核心：恢复后书架上只有一条记录，且保留本机书源身份。
     */
    @Test
    fun restoreMergesSameBookFromDifferentSourcesIntoOne() {
        val path = writeBackup()
        val before = appDb.bookDao.all.size

        Restore.restoreLocked(path)

        // 本机记录仍在，且身份未被改写
        val local = appDb.bookDao.getBook(localUrl)
        assertNotNull("本机记录不得被删除", local)
        assertEquals("https://sourceY.com", local!!.origin)
        assertEquals("源Y", local.originName)
        assertEquals("https://sourceY.com/toc/1", local.tocUrl)

        // 备份的书不得作为第二条记录落库
        val all = appDb.bookDao.all
        assertEquals("恢复后不得出现第二条同书记录", before, all.size)
        assertEquals(
            "书架上同书只应有一条",
            1,
            all.count { BookMergeRules.identityKeyOf(it) != null && it.name == "测试书A" }
        )
    }

    /**
     * 核心：本机配图必须保留。
     *
     * 备份不含章节表，配图无法重建 —— 一旦恢复路径清了配图就是永久丢失
     * （这是 v1 方案把恢复接到 `BookUpsert` 会踩的坑）。
     */
    @Test
    fun restoreKeepsLocalIllustrations() {
        val path = writeBackup()

        Restore.restoreLocked(path)

        val illustrations = appDb.bookIllustrationDao.getByBook(localUrl)
        assertEquals("本机配图不得被清除", 1, illustrations.size)
        assertEquals("本机配图", illustrations.first().note)
    }

    /**
     * 外键安全：恢复不得因配图行的 `bookUrl` 无父行而整体失败。
     *
     * `PRAGMA foreign_keys = ON` 下，插入父行不存在的配图会抛约束失败，
     * 而恢复整个 DB 段在同一事务里 → 一旦触发会整段回滚。
     */
    @Test
    fun restoreSurvivesIllustrationWithMissingParentBook() {
        backupDir.mkdirs()
        val backupBook = Book(
            bookUrl = backupUrl,
            origin = "https://sourceX.com",
            originName = "源X",
            name = "测试书A",
            author = "测试作者"
        )
        File(backupDir, "bookshelf.json").writeText(GSON.toJson(listOf(backupBook)))
        // 配图指向一本备份中不存在、也不会落库的书 → 必须被跳过而不是让恢复失败
        File(backupDir, "bookIllustration.json").writeText(
            GSON.toJson(
                listOf(
                    BookIllustration(bookUrl = "https://ghost.com/none", chapterIndex = 1)
                )
            )
        )

        Restore.restoreLocked(backupDir.absolutePath)

        assertNotNull("恢复必须整体成功", appDb.bookDao.getBook(localUrl))
        assertTrue(
            "无父行的配图不得落库",
            appDb.bookIllustrationDao.getByBook("https://ghost.com/none").isEmpty()
        )
    }

    /** 恢复后本机进度不得倒退（本机读得更远时保持本机）。 */
    @Test
    fun restoreDoesNotRewindLocalProgress() {
        appDb.bookDao.getBook(localUrl)?.let { book ->
            appDb.bookDao.update(
                book.copy(durChapterIndex = 5, durChapterPos = 500, durChapterTime = 9000)
            )
        }
        val path = writeBackup()

        Restore.restoreLocked(path)

        val local = appDb.bookDao.getBook(localUrl)!!
        assertEquals("本机读得更远时不得倒退", 5, local.durChapterIndex)
        assertEquals(500, local.durChapterPos)
    }
}
