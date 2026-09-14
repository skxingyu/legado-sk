package io.legado.app.help.storage

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 备份打包清单必须只包含实际落盘的文件。
 *
 * 回归背景（10043 实机故障）：`ZipUtils.zipFile` 由「静默跳过缺失源文件」改为
 * `require(srcFile.exists())`，而 `Backup.kt` 仍按 `backupFileNames` 全量拼路径，
 * 于是任何一张空表（`writeListToJson` 对空列表刻意不写文件）都会让整次备份
 * 以 `IllegalArgumentException: ZIP 源文件不存在` 中止——新装机必然触发。
 */
class BackupZipSourcesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun givenFiles(vararg names: String): String {
        val dir = tempFolder.newFolder("backup")
        names.forEach { dir.resolve(it).writeText("{}") }
        return dir.absolutePath
    }

    @Test
    fun returnsOnlyFilesThatExistOnDisk() {
        val dir = givenFiles("bookshelf.json", "bookSource.json")

        val sources = existingZipSources(
            dir,
            listOf("bookshelf.json", "rssStar.json", "bookSource.json")
        )

        assertEquals(
            listOf("bookshelf.json", "bookSource.json"),
            sources.map { it.substringAfterLast(java.io.File.separator) }
        )
    }

    @Test
    fun emptyListWhenNothingWasWritten() {
        val dir = givenFiles()

        val sources = existingZipSources(dir, listOf("rssStar.json", "sourceSub.json"))

        assertEquals(emptyList<String>(), sources)
    }

    @Test
    fun everyReturnedPathIsANonEmptyExistingFile() {
        val dir = givenFiles("readRecord.json")

        val sources = existingZipSources(
            dir,
            listOf("readRecord.json", "bookIllustration.json")
        )

        // 打包要求：返回的每个路径都必须真实存在，否则 require 会中断备份
        sources.forEach { assert(java.io.File(it).exists()) { "不存在的源文件被交给 zip：$it" } }
    }
}
