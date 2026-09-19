package io.legado.app.lib.prefs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * isBottomBackground 偏好行的文字配色回归锁。
 *
 * 根因（10057 阅读页「更多设置」亮色白底白字）：行文字亮度判定用的是原始
 * ThemeStore.bottomBackground 存值，而该行实际绘制的背景是
 * PreferenceItemStyle 的 UiCorner.surfaceColor(UiCorner.themeSurfaceCardColor)。
 * 主题把 bottomBackground 配成亮度<0.5 的中灰（实测 #B6B6B6，亮度 0.468）时，
 * 判定误为深色背景 → 白字配浅卡。
 *
 * JVM 单测无法实例化 androidx.preference（Android 依赖），按仓库先例做源码级静态断言：
 * 文字判定必须与卡片绘制同源（PreferenceItemStyle.itemSurfaceColor）。
 */
class PreferenceRowTextColorSourceTest {

    private fun source(path: String): String {
        val file = File(path)
        assertTrue("源码文件不存在：$path", file.isFile)
        return file.readText()
    }

    @Test
    fun itemSurfaceColor_isSingleSourceOfPaintedCardColor() {
        val body = source("src/main/java/io/legado/app/lib/prefs/PreferenceItemStyle.kt")
        assertTrue(
            "PreferenceItemStyle 必须提供 itemSurfaceColor 作为卡片表面色唯一入口",
            body.contains("fun itemSurfaceColor(context: Context): Int")
        )
        val funStart = body.indexOf("fun itemSurfaceColor(context: Context): Int")
        val funBody = body.substring(funStart, body.indexOf("}", funStart) + 1)
        assertTrue(
            "itemSurfaceColor 必须走 UiCorner.surfaceColor(themeSurfaceCardColor) 统一入口",
            funBody.contains("UiCorner.surfaceColor(UiCorner.themeSurfaceCardColor(context))")
        )
        assertTrue(
            "PreferenceItemStyle.apply 绘制背景必须复用 itemSurfaceColor，与文字判定同源",
            body.contains("val itemColor = itemSurfaceColor(preference.context)")
        )
    }

    @Test
    fun preferenceBindView_judgesAgainstPaintedCardColor() {
        val body = source("src/main/java/io/legado/app/lib/prefs/Preference.kt")
        assertFalse(
            "bindView 不得再按原始 bottomBackground 判定文字亮度（白字配浅卡根因）",
            body.contains("isColorLight(context.bottomBackground)")
        )
        val branchStart = body.indexOf("if (isBottomBackground && !viewHolder.itemView.isInEditMode)")
        assertTrue("bindView 的 isBottomBackground 分支缺失", branchStart >= 0)
        val branchEnd = body.indexOf("val iconView", branchStart)
        val branch = body.substring(branchStart, branchEnd)
        assertTrue(
            "isBottomBackground 分支必须按 PreferenceItemStyle.itemSurfaceColor 判定",
            branch.contains("ColorUtils.isColorLight(PreferenceItemStyle.itemSurfaceColor(context))")
        )
    }

    @Test
    fun nameListPreference_judgesAgainstPaintedCardColor() {
        val body = source("src/main/java/io/legado/app/lib/prefs/NameListPreference.kt")
        assertFalse(
            "NameListPreference 不得再按原始 bottomBackground 判定 chip 文字亮度",
            body.contains("isColorLight(bgColor)")
        )
        val branchStart = body.indexOf("if (isBottomBackground)")
        assertTrue("NameListPreference 的 isBottomBackground 分支缺失", branchStart >= 0)
        val branchEnd = body.indexOf("super.onBindViewHolder", branchStart)
        val branch = body.substring(branchStart, branchEnd)
        assertTrue(
            "chip 文字必须按 PreferenceItemStyle.itemSurfaceColor 判定",
            branch.contains("ColorUtils.isColorLight(PreferenceItemStyle.itemSurfaceColor(context))")
        )
    }
}
