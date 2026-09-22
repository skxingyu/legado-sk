package io.legado.app.help.storage

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「按备份清理本机书籍」入口的存在性契约（源码级静态断言）。
 *
 * 背景：备份恢复是**增量合并**，A 设备删除书籍后备份，B 恢复时其本地未手动删除的书仍留存，
 * 且 B 再备份会把它们带回 A。该入口让用户用一份备份清理本机多出来的书。
 *
 * 本测试锁定三条**不可回退**的产品约束。UI/VM 依赖 Android 与 Room，无法在 JVM 单测中实例化，
 * 故退化为源码静态断言：它不能证明运行正确，但能挡住把这几条约束改掉的具体回归。
 */
class ShelfCleanupEntryTest {

    private fun moduleSource(relativePath: String): String {
        val f = File(relativePath)
        assertTrue(
            "找不到 $relativePath（Gradle 单测 CWD 应为模块目录 app/）：${f.absolutePath}",
            f.exists()
        )
        return f.readText()
    }

    private fun executableLines(block: String): String = block.lineSequence()
        .map { it.trim() }
        .filterNot { it.startsWith("*") || it.startsWith("//") || it.startsWith("/*") }
        .joinToString("\n")

    /** 只检查可执行行，避免把说明性注释误判为违规。 */
    private fun fragmentCleanupBlock(): String {
        val source = moduleSource(
            "src/main/java/io/legado/app/ui/main/bookshelf/BaseBookshelfFragment.kt"
        )
        return executableLines(
            source.substringAfter("private fun showCleanupDialog")
                .substringBefore("private fun createBookPickView")
        )
    }

    /**
     * ⚠️ 默认全不勾选。勾选=删除，默认勾选会把不可逆操作变成「一次确认即生效」。
     */
    @Test
    fun cleanupCandidatesDefaultToUnchecked() {
        val body = fragmentCleanupBlock()

        assertTrue("未能定位 showCleanupDialog 方法体", body.isNotBlank())
        assertTrue(
            "候选必须默认全不勾选（BooleanArray 初始值为 false）",
            body.contains("BooleanArray(candidates.size) { false }")
        )
    }

    /**
     * ⚠️ 必须二次确认，且**逐条列出**将删书籍；只给数量用户无法复核。
     */
    @Test
    fun cleanupRequiresExplicitSecondConfirmation() {
        val body = fragmentCleanupBlock()

        assertTrue(
            "必须有独立的最终确认步骤",
            body.contains("confirmCleanup(")
        )
        val source = moduleSource(
            "src/main/java/io/legado/app/ui/main/bookshelf/BaseBookshelfFragment.kt"
        )
        val confirm = executableLines(
            source.substringAfter("private fun confirmCleanup")
                .substringBefore("private fun createBookPickView")
        )
        assertTrue("未能定位 confirmCleanup 方法体", confirm.isNotBlank())
        assertTrue(
            "最终确认必须逐条列出将删书名（不能只给数字）",
            confirm.contains("joinToString") && confirm.contains("R.string.cleanup_by_backup_final_message")
        )
    }

    /**
     * ⚠️ 只删书籍，**不得触碰书源**。
     *
     * 用户明确要求避开书源删除：删书源会连带清掉该源的登录变量（`SourceHelp.deleteBookSource`
     * → `deleteSourceVariables` 清 `v_`/`userInfo_`/`loginHeader_`/`sourceVariable_`/`infoMap_` 五族），
     * 而这些数据**不在备份里**，删掉无法找回。
     */
    @Test
    fun cleanupNeverDeletesBookSources() {
        val vm = executableLines(
            moduleSource("src/main/java/io/legado/app/ui/main/bookshelf/BookshelfViewModel.kt")
                .substringAfter("fun deleteBooksByCleanup")
                .substringBefore("private fun writeCleanupManifest")
        )

        assertTrue("未能定位 deleteBooksByCleanup 方法体", vm.isNotBlank())
        listOf(
            "bookSourceDao",
            "SourceHelp",
            "deleteBookSource",
            "deleteSourceVariables",
            "cacheDao",
            "cookieDao"
        ).forEach { forbidden ->
            assertTrue(
                "按备份清理不得触碰书源/登录态（$forbidden）：删书源会不可逆丢失登录信息",
                !vm.contains(forbidden)
            )
        }
        assertTrue(
            "删除必须走 Book.delete()（已有，负责清引擎状态与级联子表）",
            vm.contains(".delete()")
        )
    }

    /**
     * ⚠️ 删除前必须落「将被删项」清单。
     *
     * 书籍删除不可撤销，而 `RestoreJournal` 的快照**从不登记 `legado.db`**（既有继承缺陷），
     * 落清单是把「完全不可逆」降级为「可手工找回」的最低成本手段。
     */
    @Test
    fun cleanupWritesManifestBeforeDeleting() {
        val body = executableLines(
            moduleSource("src/main/java/io/legado/app/ui/main/bookshelf/BookshelfViewModel.kt")
                .substringAfter("fun deleteBooksByCleanup")
                .substringBefore("private fun writeCleanupManifest")
        )

        val manifestIdx = body.indexOf("writeCleanupManifest(books)")
        val deleteIdx = body.indexOf(".delete()")
        assertTrue("未能定位清单写入或删除调用", manifestIdx >= 0 && deleteIdx >= 0)
        assertTrue(
            "清单必须在执行删除**之前**写（否则删除失败时清单也丢了）",
            manifestIdx < deleteIdx
        )
    }
}
