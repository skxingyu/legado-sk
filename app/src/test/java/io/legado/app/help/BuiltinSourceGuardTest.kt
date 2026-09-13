package io.legado.app.help

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 出厂内置书源的作者授权守卫（直接校验资产文件本身，不依赖 Android 运行时）。
 *
 * 契约：书源自身声明「仅限阅读SK使用」，校验由 JS 侧 `java.matchApp(包名, 应用名)` 完成。
 * 这里锁定的事实一旦被破坏，守卫会**静默失效**（书源照常工作、非授权客户端也能用）：
 * 1. 规则仍以 `@js:` 开头——legado 只识别首位的 `@js:`，被挤到第二行整段会退化为字面量；
 * 2. **只有正文入口**参与授权裁决，搜索与发现必须完全放行；
 * 3. 非授权时正文返回引导文案而**不是抛异常**，且不得 `toast`；
 * 4. `jsLib` 声明的包名/应用名与 SK 版实际值一致，且提示文案里的换行已正确转义。
 *
 * 第 2、3 条是 10041 交付后的回归修复：搜索/发现插桩会让每次全书源搜书弹一次 toast；
 * 正文 `throw` 会被阅读器当成下载失败，既弹「获取正文失败」又按重试次数反复重试。
 */
class BuiltinSourceGuardTest {

    private val root: JsonObject by lazy {
        // Gradle 单测 CWD 默认为模块目录 app/，兼容从仓库根运行的情形。
        val file = listOf(
            File("src/main/assets/defaultData/bookSources.json"),
            File("app/src/main/assets/defaultData/bookSources.json"),
        ).firstOrNull { it.isFile } ?: error("未找到内置书源资产")
        val array = JsonParser.parseString(file.readText(Charsets.UTF_8)).asJsonArray
        assertEquals("内置书源数量", 1, array.size())
        array[0].asJsonObject
    }

    /** 取规则原文，并断言它仍以 `@js:` 开头（legado 只识别首位标记）。 */
    private fun rule(path: String): String {
        val segments = path.split('.')
        // 除最后一段（字符串规则本身）外，逐级下钻到对象。
        val owner = segments.dropLast(1).fold(root) { acc, key -> acc.getAsJsonObject(key) }
        val v = owner.get(segments.last()).asString
        assertTrue("规则缺失: $path", v.isNotEmpty())
        assertTrue("规则必须以 @js: 开头，实际为: ${v.take(24)}", v.startsWith("@js:"))
        return v
    }

    /** 抽取 `if (!fqAuthOk.call(this)) { ... }` 的守卫分支正文，用于只裁决非授权路径。 */
    private fun guardBranch(rule: String): String {
        val start = rule.indexOf("if (!fqAuthOk.call(this)) {")
        assertTrue("未找到守卫分支: ${rule.take(80)}", start >= 0)
        val open = rule.indexOf('{', start)
        var depth = 0
        for (i in open until rule.length) {
            when (rule[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return rule.substring(open + 1, i)
                }
            }
        }
        error("守卫分支括号未闭合")
    }

    @Test
    fun everyAtJsRuleKeepsPrefix() {
        listOf(
            "searchUrl",
            "ruleSearch.bookList",
            "ruleExplore.bookList",
            "ruleContent.content",
        ).forEach { rule(it) }
    }

    @Test
    fun onlyContentEntryCarriesGuard() {
        // 授权裁决只落在正文入口。搜索/发现承载结果但必须对非授权客户端完全放行：
        // 给它们插桩会让每次全书源搜书都弹一次 toast，属于已知回归。
        assertTrue("ruleContent.content 未调用 fqAuthOk", rule("ruleContent.content").contains("fqAuthOk"))
        listOf("ruleSearch.bookList", "ruleExplore.bookList").forEach { path ->
            val v = rule(path)
            assertFalse("$path 不应参与授权裁决（会导致搜书弹窗）", v.contains("fqAuthOk"))
            assertFalse("$path 不应出现授权提示文案", v.contains("FQ_AUTH_DENIED"))
        }
    }

    @Test
    fun deniedContentReturnsBodyInsteadOfThrowing() {
        // 非授权正文必须「返回引导文案」而非抛异常：抛异常会被 CacheBook 当成下载失败，
        // 阅读页显示「获取正文失败」，并按 downloadChapterRetryCount 反复重试 + 反复 toast。
        // 只裁决守卫分支本身——授权分支内的 throw（章节编号缺失等）是正常取正文失败。
        val deniedBranch = guardBranch(rule("ruleContent.content"))
        assertFalse("非授权分支不得抛异常", deniedBranch.contains("throw"))
        assertFalse("非授权分支不得 toast（正文由阅读界面呈现）", deniedBranch.contains("java.toast"))
        assertTrue("非授权分支应返回 FQ_AUTH_DENIED 文案", deniedBranch.contains("FQ_AUTH_DENIED"))
    }

    @Test
    fun noRuleToastsOnDenied() {
        // 任何规则都不得在非授权路径上弹 toast：提示信息统一由正文承载。
        listOf(
            "ruleSearch.bookList",
            "ruleExplore.bookList",
            "ruleContent.content",
        ).forEach { path ->
            assertFalse("$path 不应调用 java.toast", rule(path).contains("java.toast"))
        }
    }

    @Test
    fun jsLibDeclaresSkIdentity() {
        val lib = root.get("jsLib").asString
        assertTrue("jsLib 缺少 fqAuthOk 定义", lib.contains("function fqAuthOk"))
        assertTrue("jsLib 未声明 SK 包名", lib.contains("io.legado.app.c"))
        assertTrue("jsLib 未声明 SK 应用名", lib.contains("阅读SK"))
        assertTrue("jsLib 缺少作者仓库地址", lib.contains("github.com/skxingyu/legado-sk"))
        // 提示文案里的换行必须是转义序列，字面换行会截断 JS 字符串导致语法错误。
        assertTrue("FQ_AUTH_DENIED 文案缺少转义换行", lib.contains("\\n"))
    }

    @Test
    fun commentStatesUsageScope() {
        val comment = root.get("bookSourceComment").asString
        assertTrue("备注未声明使用范围: $comment", comment.contains("阅读SK"))
    }
}
