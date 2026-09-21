package io.legado.app.help.config

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 出厂内置主题/排版预设（直接校验资产文件本身，不依赖 Android 运行时）。
 *
 * 预设是静态 assets，写不了随设备变化的 filesDir 路径：
 * - 主题背景图以 `@asset:` 前缀声明引用，由 ThemeConfig.resolvePresetBackgrounds 解压后回填；
 * - 排版背景图以 `bgType=1` + `bgStr` 文件名引用 assets/bg。
 *
 * 这里锁定的事实一旦被破坏，失败是**静默**的：资产缺失时主题只是没有背景图、
 * 排版只是变成纯色，界面上不会报任何错。故必须由测试兜住路径的完整性。
 */
class BuiltinPresetAssetTest {

    private fun assetDir(): File = listOf(
        // Gradle 单测 CWD 默认为模块目录 app/，兼容从仓库根运行的情形。
        File("src/main/assets"),
        File("app/src/main/assets"),
    ).firstOrNull { it.isDirectory } ?: error("未找到 assets 目录")

    private fun presetArray(name: String): JsonArray {
        val file = File(assetDir(), "defaultData/$name")
        assertTrue("预设资产不存在: $name", file.isFile)
        return JsonParser.parseString(file.readText(Charsets.UTF_8)).asJsonArray
    }

    /** 主题预设引用的资产必须真实存在，否则该主题应用后没有背景图。 */
    @Test
    fun themePresetAssetRefsExist() {
        val prefix = "@asset:"
        val refs = presetArray("themeConfig.json").map { it.asJsonObject }
            .mapNotNull { it.get("backgroundImgPath")?.asString }
            .filter { it.startsWith(prefix) }
        assertTrue("新增主题预设应至少带一张资产背景图", refs.isNotEmpty())
        refs.forEach { ref ->
            val path = ref.removePrefix(prefix)
            assertTrue("@asset 引用必须相对 assets 根: $ref", !path.startsWith("/"))
            assertTrue("主题背景图资产缺失: $path", File(assetDir(), path).isFile)
        }
    }

    /**
     * 主题预设按 themeName + isNightTheme 双键匹配：键重复会让后写入的一条
     * 整体替换掉另一条，导致日间或夜间配色丢失（ThemeConfig.addConfig 同源约束）。
     */
    @Test
    fun themePresetKeysAreUnique() {
        val keys = presetArray("themeConfig.json").map { it.asJsonObject }
            .map { "${it.get("themeName").asString}|${it.get("isNightTheme").asBoolean}" }
        assertEquals("主题预设双键不得重复", keys.size, keys.toSet().size)
    }

    /**
     * 排版预设按**数组下标**作为内置样式序号，下标直接写进用户的 readStyleSelect。
     * 因此只允许在末尾追加，插入或重排会让已用户的当前排版指到别的样式上。
     */
    @Test
    fun readPresetIsAppendedAtEnd() {
        val names = presetArray("readConfig.json").map { it.asJsonObject.get("name").asString }
        assertEquals("内置排版前 5 项是既有预设，不得改动", READ_PRESET_HEAD, names.take(5))
        assertEquals("娑娜排版必须追加在末尾", "娑娜", names.last())
    }

    /** 排版预设的背景图引用必须指向 assets/bg 下的真实文件（bgType=1 即 assets 图片）。 */
    @Test
    fun readPresetBackgroundAssetsExist() {
        presetArray("readConfig.json").map { it.asJsonObject }.forEach { config ->
            val name = config.get("name").asString
            for ((typeKey, strKey) in listOf("bgType" to "bgStr", "bgTypeNight" to "bgStrNight")) {
                // 仅 bgType=1（assets 图片）才有资产可校验；0=颜色、2=外部文件
                if (config.get(typeKey)?.asInt != 1) continue
                val fileName = config.get(strKey)?.asString.orEmpty()
                assertTrue("$name 的 $strKey 不能为空", fileName.isNotBlank())
                assertTrue(
                    "$name 的背景图资产缺失: bg/$fileName",
                    File(File(assetDir(), "bg"), fileName).isFile
                )
            }
        }
    }

    /** resolvePresetBackgrounds 只处理带前缀的值，其余路径必须原样透传。 */
    @Test
    fun presetPrefixOnlyStripsDeclaredRefs() {
        val prefix = "@asset:"
        val passthrough = listOf(
            "/data/user/0/io.legado.app.c/files/defaultData/x.jpg",
            "http://example.com/bg.jpg",
            "background.jpg",
        )
        passthrough.forEach {
            assertTrue("非引用值不得被当作资产: $it", !it.startsWith(prefix))
        }
        val ref = "@asset:defaultData/pre_theme_moxu_day.jpg"
        assertEquals("defaultData/pre_theme_moxu_day.jpg", ref.removePrefix(prefix))
    }

    /**
     * 播种（ThemePackageManager.seedBuiltinPresetsOnce）按 themeName + isNightTheme
     * 落到 themePackages/{day,night}/<normalizeFileName(themeName)>，与既有落包链路同址。
     * 这里锁定播种能覆盖到每一条预设、且落点不冲突 —— 若两条预设归一到同一目录名，
     * 后一条会覆盖前一条，表现为「主题管理页少了一项」，且**不会报任何错**。
     */
    @Test
    fun everyPresetSeedsToDistinctDir() {
        val keys = presetArray("themeConfig.json").map { it.asJsonObject }.map { config ->
            val name = config.get("themeName").asString.trim()
            assertTrue("预设主题名不能为空（播种会跳过空名）", name.isNotBlank())
            val tab = if (config.get("isNightTheme").asBoolean) "night" else "day"
            // normalizeFileName 只替换 [\\/:*?"<>|]，不折叠 CJK；此处用同一字符类模拟。
            val dirName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            "$tab/$dirName"
        }
        assertEquals(
            "预设落点不得重复，否则播种会互相覆盖",
            keys.size,
            keys.toSet().size
        )
    }

    /**
     * 播种要求 backgroundImgPath 是「裸 @asset: 引用」或「已是绝对路径」二选一：
     * 前者由 resolvePresetBackgrounds 解压回填，后者直接拷贝。
     * 若出现第三种形态（相对路径、content:// 等），copyAssetsIntoPackage 会静默
     * 写坏 theme.json，主题包背景丢失。故在此锁死取值范围。
     */
    @Test
    fun presetBackgroundPathsAreResolvable() {
        presetArray("themeConfig.json").map { it.asJsonObject }.forEach { config ->
            val name = config.get("themeName").asString
            val path = config.get("backgroundImgPath")?.asString ?: return@forEach
            val ok = path.startsWith("@asset:") || File(path).isAbsolute
            assertTrue("$name 的 backgroundImgPath 形态无法被播种解析: $path", ok)
        }
    }

    /**
     * 全新安装时 `durThemeName` / `durThemeNameNight` 未写入，`getDayTheme`/`getNightTheme`
     * 用 [ThemeConfig.DEFAULT_DAY_THEME_NAME] / [ThemeConfig.DEFAULT_NIGHT_THEME_NAME] 兜底，并以此名
     * 在 `configList` 中 `firstOrNull { it.themeName == name }` 命中预设，从而取得该预设的
     * **背景图**等资产（见 mergeStoredThemeAssets）。
     *
     * 一旦兜底名与预设里的 `themeName` 不一致，命中失败 → 新装用户拿到的是**没有背景图的
     * 裸 pref 默认配色**，且**不报任何错**。故把这两处的耦合锁死。
     */
    @Test
    fun defaultThemeNamesMatchFallback() {
        val presets = presetArray("themeConfig.json").map { it.asJsonObject }
        val dayNames = presets.filter { !it.get("isNightTheme").asBoolean }
            .map { it.get("themeName").asString }
        val nightNames = presets.filter { it.get("isNightTheme").asBoolean }
            .map { it.get("themeName").asString }
        // 日间/夜间首条即兜底主题（themeConfig.json 约定：index 0 = 日间默认，index 1 = 夜间默认）
        assertEquals(
            "日间默认主题名必须与 ThemeConfig.DEFAULT_DAY_THEME_NAME 一致",
            ThemeConfig.DEFAULT_DAY_THEME_NAME,
            dayNames.first()
        )
        assertEquals(
            "夜间默认主题名必须与 ThemeConfig.DEFAULT_NIGHT_THEME_NAME 一致",
            ThemeConfig.DEFAULT_NIGHT_THEME_NAME,
            nightNames.first()
        )
    }

    /**
     * 预设改名后，`ThemePackageManager.presetLegacyDirNames` 必须覆盖到「改名前的旧目录名」。
     *
     * 起因（10061 实测缺陷）：日/夜默认预设由「黑猫慢生活」/「黯夜」更名为「白」/「黑」，
     * 存量设备上旧名目录已被 `ensureLocalAppliedTheme` 落过盘，而主题管理页是**纯目录扫描**
     * 不做去重 → 播种不认旧名就会新建新名目录，同一主题并列两条（实测日间 5 条）。
     *
     * 这里锁定：默认预设一旦改名，旧名映射必须存在且不得与当前预设名相同
     * （映射到自身等于没映射，重复条目会重现）。
     */
    @Test
    fun renamedDefaultsMustHaveLegacyDirMapping() {
        val presets = presetArray("themeConfig.json").map { it.asJsonObject }
        val dayName = presets.first { !it.get("isNightTheme").asBoolean }
            .get("themeName").asString
        val nightName = presets.first { it.get("isNightTheme").asBoolean }
            .get("themeName").asString

        listOf(dayName, nightName).forEach { current ->
            val legacy = ThemePackageManager.legacyDirNameOf(current)
            assertNotNull("改名后的默认预设「$current」必须登记旧目录名映射，否则存量设备会重复", legacy)
            assertTrue(
                "「$current」的旧名映射不得指向自身（等于没映射）: $legacy",
                legacy != current
            )
        }
    }

    /**
     * ⚠️ **旧名映射必须被每一个落包入口查询，只挡住播种入口是不够的。**
     *
     * 10062 实测缺陷：guard 只加在 `seedBuiltinPresetsOnce`，而真正造出重复目录的是
     * `ensureLocalAppliedTheme`（它拿 `getThemeConfig` 的默认兜底名「白」去查目录，
     * 存量设备上只有旧名「黑猫慢生活」→ 落出 `day/白` 空壳：`backgroundImgPath=None`、
     * `fontScale=11`，配色取自 pref 默认值而非预设资产）。结果是同一主题并列两条，
     * 且其中一条背景丢失。单测只断言"映射存在"**拦不住这个**，故改为锁定结构。
     *
     * 判据：两个落包入口都必须在自身源码里引用同一个共享判据方法，
     * 不允许各自内联一套"只查新名"的目录检查。
     */
    @Test
    fun everyMaterializationEntryPointSharesLegacyAwarePredicate() {
        val source = sourceOf("ThemePackageManager.kt")
        listOf("ensureLocalAppliedTheme", "seedBuiltinPresetsOnce").forEach { entryPoint ->
            val body = functionBody(source, entryPoint)
            assertNotNull("未找到落包入口 $entryPoint（改名后请同步本测试）", body)
            assertTrue(
                "$entryPoint 必须在落包前查询共享判据 $MATERIALIZED_PREDICATE（含旧名），" +
                    "否则存量升级会重复出主题条目",
                body!!.contains(MATERIALIZED_PREDICATE)
            )
        }
    }

    /**
     * 共享判据自身必须真的查旧名 —— 防止把它改成"只读映射却不用它查目录"这种假修复。
     */
    @Test
    fun materializedPredicateActuallyChecksLegacyDir() {
        val body = functionBody(sourceOf("ThemePackageManager.kt"), MATERIALIZED_PREDICATE)
        assertNotNull("未找到共享判据 $MATERIALIZED_PREDICATE", body)
        assertTrue(
            "$MATERIALIZED_PREDICATE 必须把旧名纳入待查目录名集合",
            body!!.contains("presetLegacyDirNames")
        )
        assertTrue(
            "$MATERIALIZED_PREDICATE 必须按候选目录名逐个读取主题包",
            body.contains("readPackage")
        )
    }

    /**
     * 截取 `fun <name>(...)` 起、到下一个同缩进 `fun ` / 类结束为止的函数体文本。
     * 仅用于本测试的结构断言，不解析 Kotlin。
     */
    private fun functionBody(source: String, functionName: String): String? {
        val header = Regex("""(?m)^\s*(?:private |internal |public )?(?:suspend )?fun $functionName\b""")
            .find(source) ?: return null
        val rest = source.substring(header.range.last)
        val end = Regex("""(?m)^    (?:private |internal |public )?(?:suspend )?fun """)
            .find(rest, 1)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    /** 读取主源集下的 Kotlin 源文件；CWD 兼容模块目录与仓库根。 */
    private fun sourceOf(relativePath: String): String {
        val candidates = listOf(
            File("src/main/java/io/legado/app/help/config/$relativePath"),
            File("app/src/main/java/io/legado/app/help/config/$relativePath"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("未找到源文件 $relativePath（候选：$candidates）")
        return file.readText(Charsets.UTF_8)
    }

    private companion object {
        val READ_PRESET_HEAD = listOf("猫咪", "秋", "春", "黄", "黑猫")

        /** 见 ThemePackageManager：唯一的内置预设物化判据。 */
        const val MATERIALIZED_PREDICATE = "findMaterializedPreset"
    }
}
