package io.legado.app.help.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「默认备份内容」的三条不可退化契约。
 *
 * 背景：备份此前**每次**弹框询问并硬编码全选（`BooleanArray(items.size){ true }`），
 * 用户想去掉主题/阅读排版（背景图、字体、主题包才是体积大头）只能每次手动取消。
 * 现在改为一次设定、之后按已存选择直接打包。
 *
 * ⚠️ 本测试是**源码级静态断言**：`BackupTargetConfig` 初始化会触碰 `appCtx`
 * （见 `BackupConfig` 的 `const`/`appCtx` 用法），JVM 单测无法实例化；
 * 它不能证明运行期正确，但能挡住三条会真实伤到用户的退化方向。
 */
class BackupTargetConfigTest {

    private val dir = "src/main/java/io/legado/app/help/storage"
    private val configSource: String by lazy { readSource("$dir/BackupConfig.kt") }
    private val backupSource: String by lazy { readSource("$dir/Backup.kt") }
    private val fragmentSource: String by lazy {
        readSource("src/main/java/io/legado/app/ui/config/BackupConfigFragment.kt")
    }

    private fun readSource(path: String): String {
        val f = File(path)
        assertTrue("找不到 $path（Gradle 单测 CWD 应为模块目录 app/）：${f.absolutePath}", f.exists())
        return f.readText()
    }

    /**
     * ⚠️ 未保存过选择时必须**默认勾选**（`?: true`）。
     *
     * 存量设备升级后没有 `backupTarget.json`，默认方向反了（默认不选）会让
     * 升级后的第一次备份变成「什么都没备份」——而用户以为备份成功了。
     */
    @Test
    fun unsetSelectionDefaultsToSelected() {
        val body = configSource.substringAfter("object BackupTargetConfig")
            .substringBefore("fun isAllSelected")
        assertTrue("未定位到 BackupTargetConfig 主体", body.isNotBlank())
        assertTrue(
            "未保存过的项必须默认勾选（?: true），否则升级后首次备份为空",
            body.contains("selections[key] ?: true")
        )
        assertFalse(
            "默认不得为 false",
            body.contains("selections[key] ?: false")
        )
    }

    /**
     * ⚠️ 一个项目都没勾时，`selectedTargets()` 必须返回**空集**，调用方据此拒绝备份。
     *
     * 空集不能被塌缩成「全部打包」或「打个空包」：
     * 前者无视用户设定，后者产出一个「恢复后什么都没有」却提示成功的包。
     */
    @Test
    fun emptySelectionIsRefusedNotSilentlyWidened() {
        val block = fragmentSource.substringAfter("private suspend fun selectBackupTargets")
            .substringBefore("private fun buildBackupItems")
        assertTrue("未定位到 selectBackupTargets 方法体", block.isNotBlank())
        assertTrue(
            "空选择必须拒绝并提示",
            block.contains("targets.isEmpty()") && block.contains("backup_select_none")
        )
        assertFalse(
            "空选择不得回退成「全部打包」",
            block.contains("if (targets.isEmpty()) return null\n        }\n        return targets") &&
                block.contains("hashSetOf()")
        )
    }

    /**
     * ⚠️ 全选时备份必须走 `targets = null`（旧「全部打包」语义），而不是下发一份显式清单。
     *
     * `Backup.backup` 内部只按清单过滤 `backupFileNames`/`backgroundAssetDirNames`，
     * 清单漏掉某个目标（例如日后新增）就会被静默漏备份。全选时不下发清单，
     * 新增目标自动包含，这是唯一不会随清单漂移而丢数据的分支。
     */
    @Test
    fun allSelectedKeepsNullTargetsSemantics() {
        val block = fragmentSource.substringAfter("private suspend fun selectBackupTargets")
            .substringBefore("private fun buildBackupItems")
        val gate = block.substringBefore("return targets")
        assertTrue(
            "全选必须提前 return null，保留旧「全部打包」语义",
            gate.contains("isAllSelected()") && gate.contains("return null")
        )
    }

    /**
     * ⚠️ 备份与恢复必须**共用同一份**项目清单。
     *
     * 两侧各留一份就会漂移：备份能选到某个项目、恢复侧却不认识它，
     * 于是「按项目删除未选内容」删不掉 → 恢复把不该恢复的内容恢复了，且无任何报错。
     */
    @Test
    fun backupAndRestoreShareOneItemList() {
        assertTrue(
            "备份侧必须取自共享清单 BackupItems.all",
            fragmentSource.substringAfter("private fun buildBackupItems")
                .substringBefore("private fun restoreFromUri")
                .contains("BackupItems.all")
        )
        assertTrue(
            "恢复侧必须同样取自共享清单（读不到就无从删除未选项）",
            fragmentSource.substringAfter("private fun buildRestoreItems")
                .substringBefore("private fun restoreTargetSize")
                .contains("buildBackupItems()")
        )
    }

    /**
     * 清单必须覆盖当前打包路径认识的全部目标：漏一个就意味着该目标永远无法被排除，
     * 体积问题原样保留（背景图/字体/主题包正是体积大头）。
     *
     * ⚠️ 必须走**源码字面量**断言：`BackupItems.all` 求值会触碰
     * `ThemeConfig`/`BookCover` 等 Android 相关初始化，JVM 单测会
     * `ClassNotFoundException: android.app.ActivityThread`（实测）。
     */
    @Test
    fun itemListCoversEveryPackagedTarget() {
        val items = configSource.substringAfter("internal object BackupItems")
            .substringBefore("object BackupTargetConfig")
        assertTrue("未定位到 BackupItems 主体", items.isNotBlank())

        listOf(
            "themePackages",
            "\"bg\"",
            "\"font\"",
            "\"covers\"",
            "ReadBookConfig.configFileName",
            "ThemeConfig.configFileName",
            "NavigationBarIconConfig.rootDir.name"
        ).forEach { target ->
            assertTrue("打包目标 $target 必须在共享清单中可被勾选/排除", items.contains(target))
        }
        listOf("bookshelf.json", "bookSource.json", "rssSources.json").forEach { name ->
            assertTrue("目标 $name 必须出现在清单中", items.contains(name))
        }
    }

    /**
     * ⚠️ `navigationBarDirName` 的**字面量**必须与 `NavigationBarIconConfig.rootDir.name` 一致。
     *
     * 清单里刻意不直接引用 `rootDir`（那会读 `appCtx`，使整份清单无法在 JVM 单测中求值），
     * 于是两者就成了可能各自漂移的两处事实 —— 漂移即「导航栏图标」这一项静默失效
     * （勾了不打包 / 取消了照旧打包）。
     *
     * 这里只断言「比较本身存在且正确」；比较的**运行时结果**由
     * `Backup.navigationBarDirNameStaysInSync()` 在设备上成立，
     * 单测无法求值（会 `ClassNotFoundException: android.app.ActivityThread`）。
     */
    @Test
    fun navigationBarIconDirNameStaysInSync() {
        val sync = backupSource.substringAfter("fun navigationBarDirNameStaysInSync")
            .substringBefore("object Backup")
        assertTrue("未定位到 navigationBarDirNameStaysInSync 函数体", sync.isNotBlank())
        assertTrue(
            "必须比较 BackupItems.navigationBarDirName 与 NavigationBarIconConfig.rootDir.name",
            sync.contains("BackupItems.navigationBarDirName") &&
                sync.contains("NavigationBarIconConfig.rootDir.name")
        )
    }

    /** 打包侧仍按 `targets` 收窄，且不含任何「只认清单」的硬编码分支。 */
    @Test
    fun packagingStillNarrowsByTargets() {
        assertTrue(
            "打包清单必须继续按 targets 过滤",
            backupSource.contains("existingZipSources(backupPath, backupFileNames.filter")
        )
        assertTrue(
            "目录清单必须继续按 targets 过滤",
            backupSource.contains("backgroundAssetDirNames.forEach { dirName ->")
        )
    }
}
