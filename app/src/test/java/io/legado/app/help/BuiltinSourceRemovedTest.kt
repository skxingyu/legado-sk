package io.legado.app.help

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 内置书源与其作者授权守卫**已整体移除**（2026-09-21，作者指示）。
 *
 * 背景：10040 起随包内置「番茄小说」书源，10041 起加作者授权守卫（仅限包名 `io.legado.app.c`
 * + 应用名 `阅读SK` 才放行正文）。用户规模变大后作者决定不再内置、也不再做授权判定。
 *
 * 本测试锁定"移除"这一事实，防止资产与播种链路被无意恢复：
 * 1. `defaultData/bookSources.json` 资产不得再出现；
 * 2. `DefaultData` 不得再有播种入口与读取点；
 * 3. 内核不得再暴露只服务于该守卫的 JS 接口（`matchApp` 等）。
 *
 * 用读源码/资产文本的方式校验：触碰 `DefaultData` object 会初始化 `appCtx`（JVM 单测不可用）。
 * Gradle 单测 CWD 为模块目录 `app/`，兼容从仓库根运行。
 */
class BuiltinSourceRemovedTest {

    /** 依次尝试模块目录与仓库根两种 CWD。 */
    private fun file(path: String): File =
        listOf(File(path), File("app/$path")).firstOrNull { it.isFile }
            ?: error("找不到文件：$path（单测工作目录应为模块目录 app/）")

    @Test
    fun builtinBookSourceAssetIsGone() {
        val candidates = listOf(
            File("src/main/assets/defaultData/bookSources.json"),
            File("app/src/main/assets/defaultData/bookSources.json"),
        )
        assertTrue(
            "内置书源资产不应再存在，但找到：${candidates.filter { it.exists() }}",
            candidates.none { it.exists() },
        )
    }

    @Test
    fun seedingChainIsGone() {
        val text = file("src/main/java/io/legado/app/help/DefaultData.kt").readText()
        listOf("builtinBookSources", "builtinBookSourcesToSeed", "seedBuiltinBookSourcesOnce")
            .forEach { symbol ->
                assertFalse("DefaultData 不应再含内置书源播种链路：$symbol", text.contains(symbol))
            }

        val localConfig = file("src/main/java/io/legado/app/help/config/LocalConfig.kt").readText()
        assertFalse(
            "LocalConfig 不应再含内置书源播种标记",
            localConfig.contains("builtinBookSourceSeeded"),
        )
    }

    @Test
    fun authGuardJsApiIsGone() {
        val jsExtensions = file("src/main/java/io/legado/app/help/JsExtensions.kt").readText()
        listOf("matchApp", "getAppPackageName", "getAppName").forEach { symbol ->
            assertFalse("内核不应再暴露只服务于内置书源守卫的 JS 接口：$symbol", jsExtensions.contains(symbol))
        }

        // 版本名/版本号查询是通用接口，必须保留（不要误删）。
        assertTrue("getAppVersionName 是通用接口，应保留", jsExtensions.contains("getAppVersionName"))
        assertTrue("getAppVersionCode 是通用接口，应保留", jsExtensions.contains("getAppVersionCode"))

        val appConst = file("src/main/java/io/legado/app/constant/AppConst.kt").readText()
        // 只断言 AppInfo 数据类里不再声明这两个字段；`appCtx.packageName` 是平台 API，
        // 与本次移除无关，不能一并否定。
        val appInfoBlock = Regex("data class AppInfo\\(([^)]*)\\)")
            .find(appConst)?.groupValues?.get(1)
            ?: error("未能解析 AppConst.AppInfo 声明")
        assertFalse("AppInfo 不应再声明 appName", appInfoBlock.contains("appName"))
        assertFalse("AppInfo 不应再声明 packageName", appInfoBlock.contains("packageName"))
    }
}
