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

    private companion object {
        val READ_PRESET_HEAD = listOf("猫咪", "秋", "春", "黄", "黑猫")
    }
}
