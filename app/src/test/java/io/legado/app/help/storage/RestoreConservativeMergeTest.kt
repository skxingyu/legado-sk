package io.legado.app.help.storage

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 恢复路径「保守合并」的存在性契约（源码级静态断言）。
 *
 * 背景：`Book` 表主键是 `bookUrl`，同一本书在不同书源下 `bookUrl` 不同，按 `bookUrl` 浅合并
 * 会让书架出现两条记录（两设备选了不同书源时必然发生）。恢复必须按身份收敛，但又**不能**
 * 复用 `BookUpsert` 的换源语义 —— 那是「以新源为准」，会：
 *   ① 调 `clearIllustrations(keep)` 清掉本机配图（备份不含章节表，配图无法重建）；
 *   ② 改写 `origin`/`tocUrl`，而恢复没有备份目录可重建章节 → 目录 URL 与章节内容脱节。
 *
 * 本测试锁定这两条**不得回退**的约束。`Restore` 依赖 `appDb`（Room 顶层对象）无法在 JVM 单测中
 * 实例化，故退化为源码静态断言：它不能证明运行正确，但能挡住「把恢复改回 upsertByIdentity /
 * 清配图」这个具体回归。
 */
class RestoreConservativeMergeTest {

    private fun restoreSource(): String {
        val f = File("src/main/java/io/legado/app/help/storage/Restore.kt")
        assertTrue(
            "找不到 Restore.kt（Gradle 单测 CWD 应为模块目录 app/）：${f.absolutePath}",
            f.exists()
        )
        return f.readText()
    }

    @Test
    fun restoreShelfBooksMustMergeByIdentity() {
        val body = restoreSource()
            .substringAfter("private fun restoreShelfBooks")
            .substringBefore("private fun restoreIllustrations")

        assertTrue("未能定位 restoreShelfBooks / findLocalBookByIdentity 方法体", body.isNotBlank())
        assertTrue(
            "恢复必须按身份收敛（否则书源不同的同书会并列两条）",
            body.contains("BookMergeRules.identityKeyOf")
        )
        assertTrue(
            "命中同书必须走恢复专用保守合并 mergeFromBackup",
            body.contains("BookMergeRules.mergeFromBackup")
        )
    }

    /**
     * 恢复路径**不得**出现清空本机配图的调用。
     *
     * `clearIllustrations`（BookUpsert）与 `deleteByBook`（BookIllustrationDao）都是整体删除，
     * 一旦出现在恢复路径，本机已下载的配图会被永久清掉 —— 因为备份里没有 chapters.json，配图无法重建。
     */
    @Test
    fun restoreMustNeverClearLocalIllustrations() {
        val source = restoreSource()
        // ⚠️ 判据必须覆盖**整条恢复路径**，不能只查单个函数的字面 token：
        // 初版只查 restoreShelfBooks 里的 "clearIllustrations" 与 restoreIllustrations 里的
        // "deleteByBook"，实测把 `deleteByBook(...)` 注入 restoreShelfBooks **不会被抓到**（假绿）。
        val restoreBody = source
            .substringAfter("private fun restoreShelfBooks")
            .substringBefore("private inline fun <reified T> fileToListT")

        assertTrue("未能定位恢复段方法体", restoreBody.isNotBlank())
        listOf("clearIllustrations", "deleteByBook").forEach { forbidden ->
            assertTrue(
                "恢复路径不得出现 $forbidden：它会整体删除本机配图，而备份不含章节表、" +
                        "配图无法重建（这是 v1 方案的致命缺陷）",
                !restoreBody.contains(forbidden)
            )
        }
    }

    /**
     * 配图插入前必须过滤出「父书最终存在于 books 表」的行：
     * 备份里被 `ignoreLocalBook` 跳过的本地书、以及身份合并后被丢弃的备份 URL，
     * 其配图若照原 `bookUrl` 插入会触发外键失败（`PRAGMA foreign_keys = ON`），使整个 DB 段回滚。
     */
    @Test
    fun illustrationRestoreMustFilterByPersistedBookUrls() {
        val body = restoreSource()
            .substringAfter("private fun restoreIllustrations")
            .substringBefore("private inline fun <reified T> fileToListT")

        assertTrue(
            "配图段必须按「最终落库的书 URL」映射后再插入",
            body.contains("restoredBookUrls[illustration.bookUrl]")
        )
        assertTrue(
            "父书不存在时必须跳过（记为跳过数并输出日志，不得静默丢弃）",
            body.contains("skipped")
        )
    }
}
