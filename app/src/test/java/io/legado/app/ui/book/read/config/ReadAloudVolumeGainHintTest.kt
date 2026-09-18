package io.legado.app.ui.book.read.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 音量增强提示的赋值方式契约（源码级静态断言）。
 *
 * 背景：10050 首版用 `tvVolumeGainHint.setText(hintRes)`，并让"无提示"档返回 `0` 表示清空——
 * 但 `TextView.setText(Int)` 的参数是**字符串资源 id**，`0` 不是有效资源，实际运行抛
 * `Resources$` + `NotFoundException`（String resource ID #0x0）。**只要打开朗读面板且增益为默认值 0
 * 就必崩**（已在平板 TB-9707F 实测复现）。
 *
 * 这条无法用 JVM 单测覆盖（`TextView` 是 Android 类），故退化为对源码文本的静态断言：
 * 它不能证明运行正确，但能挡住"又把资源 id 0 当空文本"这个具体回归。
 */
class ReadAloudVolumeGainHintTest {

    private val sourceFile = File(
        "src/main/java/io/legado/app/ui/book/read/config/ReadAloudDialog.kt"
    )

    private fun source(): String {
        assertTrue(
            "找不到 ReadAloudDialog.kt（Gradle 单测 CWD 应为模块目录 app/）：${sourceFile.absolutePath}",
            sourceFile.exists()
        )
        return sourceFile.readText()
    }

    @Test
    fun volumeGainHintNeverAssignsAResourceId() {
        val body = source().substringAfter("private fun upVolumeGainText")
            .substringBefore("private fun saveVolumeGain")

        assertTrue("未能定位 upVolumeGainText 方法体", body.isNotBlank())
        // 曾崩溃的写法：把可能为 0 的资源 id 直接交给 setText(Int)
        assertFalse(
            "upVolumeGainHint 不得用 setText(资源id)：0 不是有效资源，会抛 Resources NotFoundException",
            Regex("""tvVolumeGainHint\s*\.\s*setText\s*\(""").containsMatchIn(body)
        )
        // 空档必须赋 CharSequence，而不是数字
        assertTrue(
            "无提示档应以空字符串赋值",
            body.contains("\"\"")
        )
    }
}
