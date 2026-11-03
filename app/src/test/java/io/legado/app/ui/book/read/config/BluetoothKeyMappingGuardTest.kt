package io.legado.app.ui.book.read.config

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 蓝牙按键映射的回归锁。
 *
 * 行为断言：音量键在朗读态的默认动作必须是翻页（SK 保留了自己的音量键翻页闸门，
 * 若这里退回上游的 ACTION_NONE，打开「朗读中也可翻页」的用户按音量键会毫无反应）。
 * 源码断言：三处入口必须保持唯一路径——媒体键过 handleBluetoothMediaKey、
 * 音量键过 handleKeyPage、未朗读时的播放暂停走 MediaButtonReceiver.readAloud。
 */
class BluetoothKeyMappingGuardTest {

    private fun source(path: String): String {
        val f = File(path)
        assertTrue("找不到 ${f.absolutePath}（Gradle 单测 CWD 应为模块目录）", f.exists())
        return f.readText().lineSequence()
            .map { it.trim() }
            .filterNot { it.startsWith("*") || it.startsWith("//") || it.startsWith("/*") }
            .joinToString("\n")
    }

    @Test
    fun `volume keys default to page turning in both slots`() {
        val up = BluetoothKeyMapping.keyForKeyCode(KeyEvent.KEYCODE_VOLUME_UP)!!
        val down = BluetoothKeyMapping.keyForKeyCode(KeyEvent.KEYCODE_VOLUME_DOWN)!!
        assertEquals(BluetoothKeyMapping.ACTION_PREVIOUS_PAGE, up.defaultAction)
        assertEquals(BluetoothKeyMapping.ACTION_PREVIOUS_PAGE, up.defaultPlayAction)
        assertEquals(BluetoothKeyMapping.ACTION_NEXT_PAGE, down.defaultAction)
        assertEquals(BluetoothKeyMapping.ACTION_NEXT_PAGE, down.defaultPlayAction)
        // 音量键不能吞掉：没映射时要放行给系统调音量
        assertFalse(up.consumeWhenNone || down.consumeWhenNone)
    }

    @Test
    fun `toggle falls back to the shared read aloud entry`() {
        val mapping = source("src/main/java/io/legado/app/ui/book/read/config/BluetoothKeyMapping.kt")
        val toggle = mapping.substringAfter("ACTION_READ_ALOUD_TOGGLE ->")
            .substringBefore("else -> error")
        assertTrue(toggle, toggle.contains("MediaButtonReceiver.readAloud(context)"))
        assertFalse("读未朗读分支不得直调界面回调，会绕过 mediaButton 三项策略", toggle.contains("toggleReadAloud"))
    }

    @Test
    fun `reader routes media and volume keys through one entry each`() {
        val activity = source("src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt")
        assertTrue(activity.contains("if (handleBluetoothMediaKey(keyCode))"))
        assertTrue(activity.contains("KeyEvent.KEYCODE_VOLUME_UP -> if (handleVolumeKey(KeyEvent.KEYCODE_VOLUME_UP, longPress))"))
        assertTrue(activity.contains("if (isVolumeKeyConsumed(keyCode))"))
        val volumeKey = activity.substringAfter("private fun handleVolumeKey(")
            .substringBefore("private fun isVolumeKeyConsumed(")
        // 翻页动作必须走 handleKeyPage，否则丢掉长按抑制与 600ms 防抖
        assertTrue(volumeKey.contains("ACTION_PREVIOUS_PAGE -> handleKeyPage(PageDirection.PREV, longPress)"))
        assertTrue(volumeKey.contains("ACTION_NEXT_PAGE -> handleKeyPage(PageDirection.NEXT, longPress)"))
        // 熄屏时蓝牙键唤不回前台，实例引用只能在真 onDestroy 清
        assertTrue(activity.contains("fun readerInstance(): ReadBookActivity? = readerInstanceRef?.get()"))
        val onStop = activity.substringAfter("override fun onStop()").substringBefore("override fun onDestroy()")
        assertFalse("onStop 清实例会让熄屏翻页失效", onStop.contains("readerInstanceRef"))

        val receiver = source("src/main/java/io/legado/app/receiver/MediaButtonReceiver.kt")
        assertTrue(receiver.contains("if (handleReaderMediaKey(context, keycode))"))
        assertFalse("mediaButtonPerNext 已删除，不得复活", receiver.contains("mediaButtonPerNext"))
    }

    @Test
    fun `skip interval preference is clickable`() {
        val config = source("src/main/java/io/legado/app/ui/book/read/config/ReadAloudConfigDialog.kt")
        // pref 行是普通 Preference，不挂点击处理就是一个点了没反应的死行（上游同款写法）
        val clickBranch = config.substringAfter("PreferKey.bluetoothReadAloudSkipInterval ->")
            .substringBefore("PreferKey.readAloudPlaybackPanelDuration")
        assertTrue(clickBranch, clickBranch.contains("showBluetoothReadAloudSkipIntervalDialog()"))
        assertTrue(config.contains("private fun showBluetoothReadAloudSkipIntervalDialog()"))
        assertTrue(config.contains("upBluetoothReadAloudSkipIntervalSummary()"))
    }
}
