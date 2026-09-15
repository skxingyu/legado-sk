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

    /**
     * 回归背景（10044 引入）：`covers` 目录由 `prepareCustomCoverBackup()` 在打包清单
     * 构建期间才创建。若把 `"covers"` 放回 `backgroundAssetDirNames` 由存在性判定处理，
     * 或改用"目录存在即入包"，则从未设过自定义封面的装机也会把**空目录**写进 zip。
     *
     * 因此入包依据必须是 `covers` **目录内确有内容**，而不是"目录存在"、
     * 也不是"本次拷了几个文件"（见下方 coversInsideCoversDirStillCountAsContent）。
     */
    @Test
    fun coversIsZippedWhenDirectoryHasFiles() {
        val covers = tempFolder.newFolder("covers_with_files")
        covers.resolve("cover_a.jpg").writeText("x")

        assert(coverDirShouldBeZipped(covers)) { "目录中有封面，必须入包" }
    }

    @Test
    fun emptyCoversDirectoryMustNotBeZipped() {
        val emptyCovers = tempFolder.newFolder("covers_empty")
        assert(emptyCovers.isDirectory) { "前提：目录存在" }
        assertEquals(0, emptyCovers.listFiles()?.size ?: 0)

        // 目录存在 ≠ 应入包：prepareCustomCoverBackup 会无条件建目录
        assert(!coverDirShouldBeZipped(emptyCovers)) { "空目录存在也不得入包" }
    }

    /**
     * 本用例直接对应一次真实回归：封面的 `customCoverUrl` 已在 covers 目录内时，
     * `prepareCustomCoverBackup()` 会跳过拷贝（无需复制到自身），
     * 目录非空 → 必须入包。若判据用"本次拷贝了几个文件"，此处会漏掉封面。
     */
    @Test
    fun coversInsideCoversDirStillCountAsContent() {
        val covers = tempFolder.newFolder("covers_inside")
        // 模拟「选择本地图片」：文件直接落在 covers 目录里，不需要再拷贝
        val inside = covers.resolve("4b3c7a86c14262f47611df96421f3c2b.png")
        inside.writeText("cover-bytes")

        assert(coverDirShouldBeZipped(covers)) {
            "封面已在 covers 内（无需拷贝）时目录非空，必须入包"
        }
    }

    /**
     * `"covers"` 必须留在 `Backup.backgroundAssetDirNames` 之外，否则会被
     * 打包清单构建期间的存在性判定提前跳过（此时目录尚未创建）→ 封面漏备份。
     *
     * 用读源码文本的方式校验：触碰 `Backup` object 会初始化 `appCtx`（JVM 单测不可用），
     * 与 `DefaultData.builtinBookSources` 同类问题。Gradle 单测 CWD 为模块目录 `app/`。
     */
    @Test
    fun coversIsNotInBackgroundAssetDirNames() {
        val source = java.io.File("src/main/java/io/legado/app/help/storage/Backup.kt")
        assert(source.isFile) { "找不到 Backup.kt，单测工作目录应为模块目录 app/，实际=${source.absolutePath}" }

        val text = source.readText()
        val block = Regex("backgroundAssetDirNames\\s*=\\s*arrayOf\\(([^)]*)\\)")
            .find(text)?.groupValues?.get(1)
        assert(block != null) { "未能解析 backgroundAssetDirNames 清单" }

        val entries = block!!.split(',')
            .map { it.trim().trim('"') }
            .filter { it.isNotBlank() }
        assert(!entries.contains("covers")) {
            "covers 必须由 prepareCustomCoverBackup() 单独处理，不能留在目录清单里" +
                "（当前清单：$entries）"
        }
    }
}
