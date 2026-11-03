package io.legado.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 书源音频两个既有红线的源码守卫（无法在 JVM 驱动 Android Service，退化为函数体锚定断言）。
 *
 * 1. 章节定时：`chapterPlaybackStart` / `maxPlayedPosition` 的记录行**不得**被删，
 *    否则 10115 治过的「章节定时永不消耗」会复活。
 * 2. 段内位置预测：注入点是 `onPlaybackStateChanged/STATE_READY`（此时 duration 才就绪），
 *    且发布门槛必须走 `shouldPublishTextPosition`，不得退回 `paragraphUnchanged && !syncView` 早退。
 */
class SourceAudioChapterTimerGuardTest {

    private fun serviceSource(): String {
        val f = File("src/main/java/io/legado/app/service/SourceAudioReadAloudService.kt")
        assertTrue("找不到 ${f.absolutePath}（Gradle 单测 CWD 应为模块目录）", f.exists())
        return f.readText().lineSequence()
            .map { it.trim() }
            .filterNot { it.startsWith("*") || it.startsWith("//") || it.startsWith("/*") }
            .joinToString("\n")
    }

    private fun slice(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        assertTrue("未找到起始标记：$startMarker", start >= 0)
        val end = source.indexOf(endMarker, start)
        assertTrue("未找到结束标记：$endMarker", end >= 0)
        return source.substring(start, end)
    }

    @Test
    fun `chapter timer records start and max played position`() {
        val source = serviceSource()
        val play = slice(source, "player.seekTo(startPosition.toLong())", "}.onFailure(::failPlayback)")
        assertTrue(play, play.contains("chapterPlaybackStart = startPosition"))
        assertTrue(play, play.contains("maxPlayedPosition = startPosition"))

        val progress = slice(source, "private fun startProgressUpdates()", "private fun publishTimeProgress(")
        assertTrue(progress, progress.contains("if (position > maxPlayedPosition) maxPlayedPosition = position.toInt()"))
        assertTrue(source, source.contains("override fun onChapterConsumed()"))
    }

    @Test
    fun `estimated timing is bound only from state ready`() {
        val source = serviceSource()
        assertFalse("不得使用 onMediaItemTransition（此时 duration 未就绪）", source.contains("onMediaItemTransition"))
        val ready = slice(source, "Player.STATE_READY ->", "Player.STATE_ENDED ->")
        assertTrue(ready, ready.contains("withEstimatedTiming("))
        assertTrue(ready, ready.contains("bindMappingToLayout(estimated)"))
        // 绑定失败必须保留 mapping 原值，留给下一次 READY 重试
        assertTrue(ready, ready.contains("?.let { binding ->"))
    }

    @Test
    fun `sync text position publishes through the shared gate`() {
        val source = serviceSource()
        val sync = slice(source, "private fun syncTextPosition(", "private fun persistProgress(")
        assertTrue(sync, sync.contains("shouldPublishTextPosition("))
        assertFalse(
            "过早 return 会让暂停在其上的预测不再发布",
            sync.contains("if (paragraphUnchanged && !syncView) return")
        )
        assertTrue(sync, sync.contains("mapping.paragraphFractionAt("))
    }
}
