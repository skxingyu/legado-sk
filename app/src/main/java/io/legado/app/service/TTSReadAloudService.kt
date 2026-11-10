package io.legado.app.service

import android.app.PendingIntent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.LogModule
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.MediaHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.exoplayer.VolumeGainRenderersFactory
import io.legado.app.help.tts.TtsCacheJanitor
import io.legado.app.help.tts.TtsCacheLog
import io.legado.app.help.tts.TtsCacheStore
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 朗读中「引擎无回调」的基础静默阈值：初始化/合成请求在引擎内丢失时不会有 onError。
 */
private const val TTS_STALL_TIMEOUT_MS = 15_000L

/**
 * 预计朗读时长最多给多少额外余量（防止语速采样异常时阈值无限放大）。
 */
private const val TTS_STALL_MAX_EXTRA_MS = 120_000L

/**
 * 无回调看门狗的判定阈值（纯函数，JVM 单测可直接断言行为）。
 *
 * 系统引擎（MultiTTS 等）整段只回调 onStart/onDone，**不报 onRangeStart**，
 * 因此「长时间没有回调」在长段正常朗读时同样成立。阈值必须覆盖本段预计朗读时长，
 * 否则 169 字这类长段读到一半就被判成引擎无响应 → 重试本段（听成「回段首重读」）
 * → 第二次仍超时 → 直接跳段（2026-11-10 模拟器实测）。
 */
internal fun ttsStallDeadlineMs(textLength: Int, charRatePerMs: Double): Long {
    val estimated = if (charRatePerMs > 0.0) (textLength / charRatePerMs).toLong() else 0L
    return TTS_STALL_TIMEOUT_MS + estimated.coerceIn(0L, TTS_STALL_MAX_EXTRA_MS)
}

/**
 * 快速重试模式下「播放队列干涸」的等待秒数：当前段还在合成、播放器已无音频，
 * 超过该时长仍未出声即判定引擎把这次请求吞了（见 [TTSReadAloudService.armRealtimeDryWatchdog]）。
 */
private const val REALTIME_DRY_STALL_GRACE_SECONDS = 15

/**
 * 看门狗到点时的干涸判据（纯函数，JVM 单测可直接断言行为）：
 * 仍是当前单元、服务在跑、没暂停、且播放器确实没在出声。
 */
internal fun shouldTreatAsDry(
    index: Int,
    nowSpeak: Int,
    isRun: Boolean,
    paused: Boolean,
    playerPlaying: Boolean
): Boolean = index == nowSpeak && isRun && !paused && !playerPlaying

class TTSReadAloudService : BaseReadAloudService() {

    private companion object {
        /**
         * 引擎初始化回调最长等待：引擎进程崩溃/重启时 binder 回调可能永远不来。
         * 冷启动（引擎进程刚被拉起）通常 1~5 秒，10 秒是留了余量的下限，再短会在冷启动时误判重建。
         */
        const val TTS_INIT_TIMEOUT_MS = 10_000L

        /**
         * 朗读中无回调的静默轮询间隔；阈值本身按本段预计朗读时长动态放宽，
         * 见 [ttsStallDeadlineMs]。
         */
        const val TTS_STALL_POLL_MS = 2_000L
    }

    private var textToSpeech: TextToSpeech? = null
    private var ttsInitFinish = false
    private val ttsUtteranceListener = TTSUtteranceListener()
    private var speakGeneration = 0
    private var ttsInitGeneration = 0
    private var retryParagraphKey: String? = null
    private var retryingTtsInit = false
    private var ttsVoiceName: String? = null
    private var queuedUntilIndex = -1
    private var ttsInitWatchdogJob: Job? = null
    private var ttsStallWatchdogJob: Job? = null

    @Volatile
    private var lastTtsCallbackAt = 0L

    @Volatile
    private var activeUtteranceId: String? = null

    // ---- 预测换页（页间分段 OFF 时的被动机制）----
    // TTS 拿不到整段音频时长，按“文字量 + 实测语速”估算读过页界的时刻；
    // onRangeStart 可用时用真实进度校准速率。契约：预测只影响位置事件的
    // 发布时机（upTtsProgress），显示是否翻页仍由 UI 侧跟随规则判定。
    private val predictHandler = Handler(Looper.getMainLooper())
    private var predictRunnable: Runnable? = null
    private var utteranceStartRealtime = 0L

    @Volatile
    private var lastRangeOffset = 0

    // 实测朗读速率（字/毫秒）：由上一句 onDone 的真实总时长滚动校准，
    // 初值按常见中文 TTS 语速约 480 字/分钟，只影响第一句的预估
    @Volatile
    private var measuredCharRate = 480.0 / 60_000.0

    // ---- TTS-Wav 模式：synthesizeToFile 合成本地 wav 后由应用自行播放 ----
    // 音频时长精确可知，句内进度按真实音频位置轮询发布（与 HTTP 引擎同款），
    // 翻页仍由 UI 侧跟随规则判定；播放当前句时后台预合成下一句保证无缝衔接。
    private val wavPlayer: ExoPlayer by lazy {
        ExoPlayer.Builder(this)
            .setRenderersFactory(VolumeGainRenderersFactory(this))
            .build()
            .apply { addListener(wavPlayerListener) }
    }
    private val wavDir: File by lazy {
        File(cacheDir, "ttsWav").apply { mkdirs() }
    }
    private var wavPosJob: Job? = null
    private var wavPendingSynthesisIndex = -1
    private var wavPendingSynthesisFile: File? = null
    private var wavReadyIndex = -1
    private var wavReadyFile: File? = null
    private var lastWavFile: File? = null

    /** 暂停恢复标志：区分“从暂停继续播放当前 wav”和“从头开始新句子”。 */
    @Volatile
    private var wavPausedPlayback = false

    // ---- 实时缓存：任务列表 + 在途合成 ----
    // 每当新的朗读单元成为当前单元：先解决当前段三态（命中→直接播；在途→等
    // 合成完成回调；缺失→立即派发，排在引擎队列最前），随后重建任务列表
    // （当前段之后 ttsCachePrefetchCount 个可读单元：命中跳过、在途跳过、
    // 缺失派发）。引擎串行合成，引擎忙碌时当前段要等手头的合成做完——这是
    // 开启实时缓存的固定代价。预取失败的段无需显式重试：变为当前段时自然
    // 落入“缺失”状态重新派发。

    private class RealtimeSynthesis(
        val index: Int,
        val tempFile: File,
        val key: TtsCacheStore.UnitKey,
        val watchdog: Runnable,
        /** 派发时生效的超时秒数：日志必须与真实截止时间一致（开关可能被中途改）。 */
        val timeoutSeconds: Int,
    )

    private val realtimePending = linkedMapOf<Int, RealtimeSynthesis>()

    /** 队列干涸看门狗的待命回调；null 表示未武装。 */
    private var realtimeDryWatchdog: Runnable? = null

    private fun clearRealtimePending() {
        synchronized(realtimePending) {
            realtimePending.values.forEach { record ->
                predictHandler.removeCallbacks(record.watchdog)
                runCatching { record.tempFile.delete() }
            }
            realtimePending.clear()
        }
    }

    private val TAG = "TTSReadAloudService"

    override fun onCreate() {
        super.onCreate()
        kotlin.runCatching {
            initTts()
        }.onFailure {
            AppLog.put("${getString(R.string.tts_init_failed)}\n$it", it, true)
        }
    }

    override fun onDestroy() {
        // 退出清扫：只留当前章，其余 tts_cache 章节目录全删（批量预缓存章豁免）。
        // 章节身份用 currentChapterIndex（引擎私有光标）而不是 textChapter：后者在
        // 退出流程中可能已被清空，用它会导致清扫静默不登记（已实机复现）。
        runCatching {
            val book = ReadBook.book
            val chapterIndex = currentChapterIndex
            if (book != null && chapterIndex >= 0) {
                val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex)
                val keepStem = if (chapter != null) {
                    TtsCacheStore.chapterStem(chapter)
                } else {
                    // 章节查不到（被换源/删除）：不保留任何章，只保留批量预缓存章
                    null
                }
                TtsCacheJanitor.onAloudStopped(book, listOfNotNull(keepStem).toSet())
            }
        }.onFailure { AppLog.put("TTS 缓存清扫登记失败: ${it.localizedMessage}", it) }
        super.onDestroy()
        cancelRealtimeDryWatchdog()
        clearTTS(forgetVoice = true)
        runCatching { wavPlayer.release() }
    }

    @Synchronized
    private fun initTts() {
        ttsInitFinish = false
        val initGeneration = ++ttsInitGeneration
        // 系统 TTS 只由经典引擎选择中的 SelectItem 包名决定。
        val engine = GSON.fromJsonObject<SelectItem<String>>(ReadAloud.ttsEngine).getOrNull()?.value
        LogUtils.d(TAG, "initTts engine:$engine")
        textToSpeech = if (engine.isNullOrBlank()) {
            TextToSpeech(this) { status -> onTtsInit(initGeneration, status) }
        } else {
            TextToSpeech(this, { status -> onTtsInit(initGeneration, status) }, engine)
        }
        upSpeechRate()
        armTtsInitWatchdog(initGeneration)
    }

    private fun armTtsInitWatchdog(initGeneration: Int) {
        ttsInitWatchdogJob?.cancel()
        ttsInitWatchdogJob = lifecycleScope.launch {
            delay(TTS_INIT_TIMEOUT_MS)
            if (initGeneration != ttsInitGeneration || ttsInitFinish) return@launch
            AppLog.put(
                "[朗读] TTS 初始化 ${TTS_INIT_TIMEOUT_MS}ms 未回调，按引擎无响应处理",
                module = LogModule.READ_ALOUD,
            )
            if (retryingTtsInit) {
                // 已经重建过一次仍无回调：曝光并停下，不能无限静默
                retryingTtsInit = false
                activeUtteranceId = null
                reportInitFailure()
                toastOnUi(R.string.tts_init_failed)
                pauseReadAloud(false)
            } else {
                retryingTtsInit = true
                clearTTS()
                initTts()
            }
        }
    }

    /**
     * 引擎无响应看门狗：MultiTTS 等引擎进程被杀、或合成请求在引擎内丢失时，
     * UtteranceProgressListener 不会有任何回调（onStart/onDone/onError 全无），
     * 表现为服务与通知仍在但永久无声。超时一律走 speak error 路径重试一次。
     */
    private fun armTtsStallWatchdog() {
        if (AppConfig.ttsWavMode) return
        ttsStallWatchdogJob?.cancel()
        if (!isPlay()) return
        lastTtsCallbackAt = SystemClock.elapsedRealtime()
        // 本段预计朗读时长 + 基础静默阈值：长段只回调 onStart/onDone，不能按固定 15 秒判死
        val utteranceLength = currentUtteranceTextLength()
        val deadlineMs = ttsStallDeadlineMs(utteranceLength, measuredCharRate)
        ttsStallWatchdogJob = lifecycleScope.launch {
            while (isActive) {
                delay(TTS_STALL_POLL_MS)
                if (!isPlay() || !ttsInitFinish) return@launch
                if (SystemClock.elapsedRealtime() - lastTtsCallbackAt < deadlineMs) continue
                AppLog.put(
                    "[朗读] ${deadlineMs}ms 无引擎回调（本段 ${utteranceLength} 字），按引擎无响应重试本段",
                    module = LogModule.READ_ALOUD,
                )
                handleSpeakError("tts no callback", retryWithReinit = true)
                return@launch
            }
        }
    }

    private fun cancelTtsWatchdogs() {
        ttsInitWatchdogJob?.cancel()
        ttsInitWatchdogJob = null
        ttsStallWatchdogJob?.cancel()
        ttsStallWatchdogJob = null
    }

    /**
     * 当前朗读单元实际传给 TTS 的文本长度（段内续读时扣除起始偏移）。
     */
    private fun currentUtteranceTextLength(): Int {
        val content = contentList.getOrNull(nowSpeak) ?: return 0
        return (content.length - paragraphStartPos).coerceAtLeast(0)
    }

    private fun cancelPageBreakPrediction() {
        predictRunnable?.let { predictHandler.removeCallbacks(it) }
        predictRunnable = null
    }

    /**
     * 预测换页调度：当前朗读单元跨越页边界时，按“页界前字符量 / 实测语速”
     * 估算读过页界的时刻，到点发布一次前进位置事件。
     * - 页间分段 ON 时单元已在页界裂开，本单元不跨页，天然不调度。
     * - onRangeStart 已发布过界位置的，预测回调自动跳过（不重复发布）。
     * - speakGeneration 防乱序：暂停/停止/出错重试后调度自动失效。
     */
    private fun schedulePageBreakPrediction(utteranceTextLength: Int) {
        cancelPageBreakPrediction()
        if (pageSplit) return
        val chapter = textChapter ?: return
        if (utteranceTextLength <= 0) return
        if (pageIndex + 1 >= chapter.pageSize) return
        val nextPageStart = chapter.getReadLength(pageIndex + 1)
        val utteranceStart = readAloudNumber
        val utteranceEnd = utteranceStart + utteranceTextLength
        if (nextPageStart <= utteranceStart || nextPageStart >= utteranceEnd) return
        val breakOffset = nextPageStart - utteranceStart
        val delayMs = (breakOffset / measuredCharRate).toLong().coerceAtLeast(0L)
        val generation = speakGeneration
        AppLog.putDebug(
            "[朗读] 预测换页调度 单元:$nowSpeak 长:$utteranceTextLength " +
                "页界偏移:$breakOffset 延时:${delayMs}ms 速率:${(measuredCharRate * 60_000).toInt()}/min"
        )
        val runnable = Runnable {
            predictRunnable = null
            if (generation != speakGeneration || pause) return@Runnable
            if (lastRangeOffset + utteranceStart >= nextPageStart) return@Runnable
            AppLog.putDebug("[朗读] 预测换页触发 pos:$nextPageStart")
            upTtsProgress(nextPageStart)
        }
        predictRunnable = runnable
        predictHandler.postDelayed(runnable, delayMs)
    }

    // ---- TTS-Wav 模式（synthesizeToFile 合成本地 wav + 应用自播）----

    private val wavPlayerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                lifecycleScope.launch(Main) {
                    if (AppConfig.ttsWavMode && isRun && !pause) {
                        advanceWavParagraph()
                    }
                }
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            AppLog.put("TTS-Wav 播放错误：${error.localizedMessage}", error)
            lifecycleScope.launch(Main) {
                if (AppConfig.ttsWavMode && isRun && !pause) {
                    lastWavFile?.delete()
                    lastWavFile = null
                    // 播放失败跳过本句，继续下一句（日志已暴露）
                    advanceWavParagraph()
                }
            }
        }
    }

    /** 停止/废弃播放现场：清播放器、轮询、在途与就绪的合成产物。 */
    private fun resetWavPlayback() {
        cancelRealtimeDryWatchdog()
        wavPosJob?.cancel()
        wavPosJob = null
        if (wavPlayer.isCommandAvailable(androidx.media3.common.Player.COMMAND_STOP)) {
            runCatching {
                wavPlayer.stop()
                wavPlayer.clearMediaItems()
            }
        }
        wavPendingSynthesisIndex = -1
        wavPendingSynthesisFile?.delete()
        wavPendingSynthesisFile = null
        wavReadyIndex = -1
        wavReadyFile?.delete()
        wavReadyFile = null
        // 实时缓存模式下 lastWavFile 是缓存文件，只能由缓存生命周期管理
        if (!AppConfig.ttsRealtimeCache) {
            lastWavFile?.delete()
        }
        lastWavFile = null
        wavPausedPlayback = false
        clearRealtimePending()
    }

    /** Wav 模式取当前朗读单元：跳过不可读单元（与 speak 模式的 while 循环同语义）。 */
    private fun speakWavCurrentParagraph() {
        if (AppConfig.ttsRealtimeCache) {
            speakRealtimeCurrentParagraph()
            return
        }
        while (nowSpeak < contentList.size) {
            val content = contentList[nowSpeak]
            val text = if (paragraphStartPos > 0) {
                content.substring(paragraphStartPos.coerceAtMost(content.length))
            } else {
                content
            }
            if (text.isNotEmpty() && !text.matches(AppPattern.notReadAloudRegex)) {
                synthesizeAndPlayCurrent(text)
                return
            }
            moveToNextParagraph()
        }
        nextChapter()
    }

    // ---- 实时缓存调度 ----

    /** 实时缓存取当前朗读单元：跳过不可读单元（与 speak 模式的 while 循环同语义）。 */
    private fun speakRealtimeCurrentParagraph() {
        while (nowSpeak < contentList.size) {
            val content = contentList[nowSpeak]
            val text = if (paragraphStartPos > 0) {
                content.substring(paragraphStartPos.coerceAtMost(content.length))
            } else {
                content
            }
            if (text.isNotEmpty() && !text.matches(AppPattern.notReadAloudRegex)) {
                speakRealtimeUnit(nowSpeak, text)
                return
            }
            moveToNextParagraph()
        }
        nextChapter()
    }

    /**
     * 当前单元三态解决：
     * 1. 命中缓存 → 直接播（任务列表照建，全命中则不派发任何合成）；
     * 2. 引擎正在合成 → 任务列表照建，等合成完成回调驱动播放；
     * 3. 未合成也不在缓存 → 立即派发（排在引擎队列最前），等回调。
     * 当前段先解决、任务列表随后建立：保证当前段在引擎队列中优先产出，
     * 已知代价是引擎忙碌时当前段要等手头的合成做完（引擎串行，无法插队）。
     */
    private fun speakRealtimeUnit(index: Int, text: String) {
        armRealtimeDryWatchdog(index)
        val book = ReadBook.book ?: return
        val chapter = textChapter?.chapter ?: return
        val key = TtsCacheStore.buildUnitKey(book, chapter, text, ttsVoiceName)
        if (TtsCacheStore.has(book, key)) {
            // 状态1：命中缓存直接播
            TtsCacheLog.debug("实时缓存命中 单元:$index")
            playWavFile(index, TtsCacheStore.unitFile(book, key))
        } else {
            val pending = synchronized(realtimePending) { realtimePending.containsKey(index) }
            if (!pending) {
                // 状态3：缺失，立即派发（排在引擎队列最前）
                TtsCacheLog.debug("实时缓存未命中，立即派发 单元:$index")
                synchronized(realtimePending) {
                    if (!realtimePending.containsKey(index)) {
                        dispatchRealtimeSynthesis(book, chapter, index, text, key)
                    }
                }
            } else {
                // 状态2：引擎正在合成该段，等完成回调驱动播放
                TtsCacheLog.debug("实时缓存在途等待 单元:$index")
            }
        }
        // 任务列表（当前段之后 N 个可读单元）：命中/在途跳过，缺失派发
        buildRealtimeTaskList(book, chapter, index)
    }

    /**
     * 任务列表：从 fromIndex+1 起向后取 ttsCachePrefetchCount 个可读单元，
     * 命中缓存或在途的跳过，缺失的派发给引擎。预取失败的段无需显式重试：
     * 变为当前段时自然落入“缺失”状态重新派发。
     */
    private fun buildRealtimeTaskList(book: Book, chapter: BookChapter, fromIndex: Int) {
        val maxCount = AppConfig.ttsCachePrefetchCount
        var scheduled = 0
        var index = fromIndex + 1
        while (index < contentList.size && scheduled < maxCount) {
            val text = contentList[index]
            if (text.isEmpty() || text.matches(AppPattern.notReadAloudRegex)) {
                index++
                continue
            }
            scheduled++
            val pending = synchronized(realtimePending) { realtimePending.containsKey(index) }
            if (!pending) {
                val key = TtsCacheStore.buildUnitKey(book, chapter, text, ttsVoiceName)
                if (!TtsCacheStore.has(book, key)) {
                    synchronized(realtimePending) {
                        if (!realtimePending.containsKey(index)) {
                            dispatchRealtimeSynthesis(book, chapter, index, text, key)
                        }
                    }
                }
            }
            index++
        }
        TtsCacheLog.debug(
            "实时缓存任务列表 当前:$fromIndex 窗口:$maxCount " +
                "在途:${synchronized(realtimePending) { realtimePending.size }}"
        )
    }

    /**
     * 派发一个单元给引擎合成（临时文件），完成回调里提交进 TTS 缓存。
     * 每次派发挂一个超时看门狗：系统 TTS 引擎挂死时 onDone/onError 都不会
     * 到来，没有看门狗则该段永久占用在途表——预取段堵死引擎队列，成为
     * 当前段时静默等待 forever。超时按失败清理；当前段走重试链
     * （clearTTS+initTts 正好是解卡引擎的手段）。
     */
    private fun dispatchRealtimeSynthesis(
        book: Book,
        chapter: BookChapter,
        index: Int,
        text: String,
        key: TtsCacheStore.UnitKey,
    ) {
        val tts = textToSpeech ?: return
        val tempFile = File(wavDir, "rt_${speakGeneration}_$index.wav")
        val watchdog = Runnable { onRealtimeSynthesisTimeout(index) }
        // 取一次，保证「实际截止时间」与「日志里的秒数」一定一致。
        val timeoutSeconds = AppConfig.ttsRealtimeSegmentTimeoutSeconds
        realtimePending[index] = RealtimeSynthesis(index, tempFile, key, watchdog, timeoutSeconds)
        val result = tts.runCatching {
            synthesizeToFile(text, Bundle(), tempFile, utteranceId(index))
        }.getOrElse {
            TtsCacheLog.put("实时缓存合成请求出错 单元:$index\n${it.localizedMessage}", it)
            TextToSpeech.ERROR
        }
        if (result == TextToSpeech.ERROR) {
            predictHandler.removeCallbacks(watchdog)
            realtimePending.remove(index)
            tempFile.delete()
            if (index == nowSpeak && isRun && !pause) {
                handleSpeakError("tts realtime synthesize error", retryWithReinit = true)
            }
        } else {
            predictHandler.postDelayed(
                watchdog,
                timeoutSeconds * 1000L,
            )
            TtsCacheLog.debug(
                "实时缓存合成派发 单元:$index 超时:${timeoutSeconds}s 在途:${realtimePending.size}"
            )
        }
    }

    /** 看门狗触发：引擎在超时时限内没有回报任何回调，按合成失败清理。 */
    private fun onRealtimeSynthesisTimeout(index: Int) {
        val record = synchronized(realtimePending) { realtimePending.remove(index) } ?: return
        record.tempFile.delete()
        TtsCacheLog.put(
            "实时缓存合成超时(${record.timeoutSeconds}s) 单元:$index"
        )
        if (index == nowSpeak && isRun && !pause) {
            handleSpeakError("tts realtime synthesize timeout", retryWithReinit = true)
        }
        // 预取段超时通常意味着引擎已挂死：当前段的看门狗随后也会触发并重init
    }

    /**
     * 武装「播放队列干涸」看门狗：实时缓存下当前段要等引擎合成，若播放器已空转、
     * 该段迟迟不出声，说明这次请求要么被引擎吞掉、要么引擎还在忙别的段——
     * 单段超时从派发起算（默认 90 秒）会白等很久，这里从「该出声音了」起算。
     * 仅快速重试开启时生效，慢速引擎保持原有保守行为（只靠单段超时兜底）。
     */
    private fun armRealtimeDryWatchdog(index: Int) {
        if (!AppConfig.ttsCacheFastRetry) return
        // 一律重新计时：同一单元可能因重试/重入再次进场，残留的旧截止时刻会提前误判干涸。
        // 重复重试次数由 [handleSpeakError] 的 retryParagraphKey 限制，不需要第二套去重。
        cancelRealtimeDryWatchdog()
        val watchdog = Runnable {
            realtimeDryWatchdog = null
            if (!shouldTreatAsDry(index, nowSpeak, isRun, pause, wavPlayer.isPlaying)) {
                return@Runnable
            }
            TtsCacheLog.put(
                "实时缓存队列干涸(${REALTIME_DRY_STALL_GRACE_SECONDS}s)未出声，按引擎挂死重试 单元:$index"
            )
            handleSpeakError("tts realtime dry timeout", retryWithReinit = true)
        }
        realtimeDryWatchdog = watchdog
        predictHandler.postDelayed(watchdog, REALTIME_DRY_STALL_GRACE_SECONDS * 1000L)
    }

    private fun cancelRealtimeDryWatchdog() {
        realtimeDryWatchdog?.let { predictHandler.removeCallbacks(it) }
        realtimeDryWatchdog = null
    }

    /** 合成完成：提交进缓存；当前单元直接播，预取单元落库等翻句时命中。 */
    private fun onRealtimeSynthesisDone(index: Int) {
        val record = synchronized(realtimePending) { realtimePending.remove(index) } ?: return
        predictHandler.removeCallbacks(record.watchdog)
        lifecycleScope.launch {
            val book = ReadBook.book
            val cacheFile = if (book == null) {
                record.tempFile.delete()
                null
            } else {
                withContext(Dispatchers.IO) {
                    runCatching {
                        TtsCacheStore.commit(record.tempFile, TtsCacheStore.unitFile(book, record.key))
                    }.onFailure {
                        record.tempFile.delete()
                        TtsCacheLog.put("实时缓存提交失败 单元:$index\n${it.localizedMessage}", it)
                    }.isSuccess
                }.takeIf { it }?.let { TtsCacheStore.unitFile(book, record.key) }
            }
            if (index == nowSpeak && isRun && !pause) {
                TtsCacheLog.debug("实时缓存合成完成，当前段播放 单元:$index")
                playWavFile(index, cacheFile)
            } else {
                TtsCacheLog.debug(
                    "实时缓存合成完成，预取落库 单元:$index 缓存:${cacheFile != null}"
                )
            }
        }
    }

    /** 合成失败：当前单元走既有错误链（重试一次/跳句），预取单元静默丢弃、后续重派发。 */
    private fun onRealtimeSynthesisError(index: Int?) {
        if (index == null) return
        val record = synchronized(realtimePending) { realtimePending.remove(index) } ?: return
        predictHandler.removeCallbacks(record.watchdog)
        record.tempFile.delete()
        TtsCacheLog.debug("实时缓存合成失败 单元:$index")
        if (index == nowSpeak && isRun && !pause) {
            handleSpeakError("tts realtime synthesize error", retryWithReinit = true)
        }
    }

    /** 合成当前朗读单元为本地 wav，完成后回调驱动播放。 */
    private fun synthesizeAndPlayCurrent(text: String) {
        val tts = textToSpeech ?: throw NoStackTraceException("tts is null")
        val index = nowSpeak
        if (wavPendingSynthesisIndex == index) {
            // 该句预合成已在途，直接等其完成回调驱动播放
            return
        }
        val file = File(wavDir, "utt_${speakGeneration}_$index.wav")
        wavPendingSynthesisIndex = index
        wavPendingSynthesisFile = file
        AppLog.putDebug("[朗读] WAV合成请求 单元:$index 长:${text.length}")
        val result = tts.runCatching {
            synthesizeToFile(text, Bundle(), file, utteranceId(index))
        }.getOrElse {
            AppLog.put("tts wav 合成请求出错\n${it.localizedMessage}", it, true)
            TextToSpeech.ERROR
        }
        if (result == TextToSpeech.ERROR) {
            wavPendingSynthesisIndex = -1
            wavPendingSynthesisFile = null
            file.delete()
            handleSpeakError("tts wav synthesize error", retryWithReinit = true)
        }
    }

    /** 合成完成回调：当前句直接播放，下一句暂存为预合成产物。 */
    private fun onWavSynthesisDone(utteranceIdStr: String) {
        val index = utteranceIndex(utteranceIdStr) ?: return
        if (AppConfig.ttsRealtimeCache) {
            onRealtimeSynthesisDone(index)
            return
        }
        if (index != wavPendingSynthesisIndex) return
        val file = wavPendingSynthesisFile
        wavPendingSynthesisIndex = -1
        wavPendingSynthesisFile = null
        lifecycleScope.launch(Main) {
            when {
                index == nowSpeak && isRun && !pause -> playWavFile(index, file)
                index == nowSpeak + 1 && file != null -> {
                    wavReadyIndex = index
                    wavReadyFile = file
                    AppLog.putDebug("[朗读] WAV预合成完成 单元:$index")
                }
                else -> file?.delete()
            }
        }
    }

    /** 合成失败：清理在途产物并走既有错误链（重试一次，仍失败跳句）。 */
    private fun onWavSynthesisError(index: Int?) {
        if (AppConfig.ttsRealtimeCache) {
            onRealtimeSynthesisError(index)
            return
        }
        if (wavPendingSynthesisIndex < 0) return
        wavPendingSynthesisFile?.delete()
        wavPendingSynthesisIndex = -1
        wavPendingSynthesisFile = null
        lifecycleScope.launch(Main) {
            if (isRun && !pause) {
                handleSpeakError("tts wav synthesize error", retryWithReinit = true)
            }
        }
    }

    /** 播放当前句的 wav 文件，并预合成下一句保证衔接。 */
    private fun playWavFile(index: Int, file: File?) {
        // 实时缓存模式下传入的是已提交的缓存文件，任何路径都不得误删
        if (index != nowSpeak) {
            if (!AppConfig.ttsRealtimeCache) file?.delete()
            return
        }
        cancelRealtimeDryWatchdog()
        if (file == null || !file.exists() || file.length() <= 0L) {
            file?.delete()
            handleSpeakError("tts wav file invalid", retryWithReinit = false)
            return
        }
        if (!AppConfig.ttsRealtimeCache) {
            lastWavFile?.delete()
        }
        lastWavFile = file
        wavPausedPlayback = false
        wavPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        wavPlayer.prepare()
        wavPlayer.play()
        upWavPlayPos()
        scheduleWavPreSynthesis()
    }

    /** 当前句播完推进：优先使用预合成的下一句，未就绪则现场合成。 */
    private fun advanceWavParagraph() {
        wavPosJob?.cancel()
        wavPosJob = null
        if (!moveToNextParagraph()) {
            nextChapter()
            return
        }
        if (wavReadyIndex == nowSpeak && wavReadyFile != null) {
            val ready = wavReadyFile
            wavReadyIndex = -1
            wavReadyFile = null
            playWavFile(nowSpeak, ready)
        } else {
            speakWavCurrentParagraph()
        }
    }

    /**
     * 句内进度发布：wav 时长由播放器精确提供，按“位置/时长 × 句字符数”换算
     * 字符位置，扫过页界即发布一次前进位置事件——真实音频信号，不是估算。
     * 页间分段 ON 时单元已在页界裂开，句内无页界，无需轮询。
     */
    private fun upWavPlayPos() {
        wavPosJob?.cancel()
        val chapter = textChapter ?: return
        val utteranceLen = currentUtteranceTextLength()
        if (utteranceLen <= 0) return
        wavPosJob = lifecycleScope.launch {
            val duration = wavPlayer.duration
            // 锚点必须取真实播放位置，不能用本句句首：
            // 本函数在句内多次进入（暂停恢复/换句），锚在句首会把朗读位置
            // 拉回本句开头，表现为「拖语速跳进度」。
            val anchor = if (duration > 0) {
                (utteranceLen.toLong() * wavPlayer.currentPosition / duration).toInt()
                    .coerceIn(0, utteranceLen)
            } else {
                0
            }
            upTtsProgress(readAloudNumber + anchor + 1)
            if (pageSplit) return@launch
            while (isActive && isRun && !pause) {
                val duration0 = wavPlayer.duration
                if (duration0 > 0) {
                    val pos = (utteranceLen.toLong() * wavPlayer.currentPosition / duration0).toInt()
                    if (pageIndex + 1 < chapter.pageSize
                        && readAloudNumber + pos > chapter.getReadLength(pageIndex + 1)
                    ) {
                        // 扫过页界：只推进引擎私有页光标并发布位置，
                        // 显示翻页由 UI 侧跟随规则处理
                        pageIndex++
                        AppLog.putDebug("[朗读] WAV过界发布 pos:${readAloudNumber + pos}")
                        upTtsProgress(readAloudNumber + pos)
                    }
                    if (wavPlayer.currentPosition >= duration0) break
                }
                delay(80)
            }
        }
    }

    /** 预合成下一句（一个槽位），播放中完成，翻句时零等待衔接。 */
    private fun scheduleWavPreSynthesis() {
        // 实时缓存模式：预取由任务列表统一调度，单槽位不介入
        if (AppConfig.ttsRealtimeCache) return
        if (wavReadyIndex == nowSpeak + 1 && wavReadyFile != null) return
        val next = nowSpeak + 1
        val text = contentList.getOrNull(next)?.takeIf {
            it.isNotEmpty() && !it.matches(AppPattern.notReadAloudRegex)
        } ?: return
        val tts = textToSpeech ?: return
        val file = File(wavDir, "utt_${speakGeneration}_$next.wav")
        wavPendingSynthesisIndex = next
        wavPendingSynthesisFile = file
        val result = tts.runCatching {
            synthesizeToFile(text, Bundle(), file, utteranceId(next))
        }.getOrElse {
            wavPendingSynthesisIndex = -1
            wavPendingSynthesisFile = null
            file.delete()
            AppLog.put("tts wav 预合成请求出错\n${it.localizedMessage}", it)
            TextToSpeech.ERROR
        }
        if (result == TextToSpeech.ERROR) {
            // 预合成失败不影响当前句播放，翻句时现场合成兜底
            wavPendingSynthesisIndex = -1
            wavPendingSynthesisFile = null
        }
    }

    @Synchronized
    private fun clearTTS(forgetVoice: Boolean = false) {
        // 重建引擎的单一收口：干涸看门狗由 resetWavPlayback() 撤销，本函数不必再显式撤。
        activeUtteranceId = null
        queuedUntilIndex = -1
        speakGeneration++
        cancelPageBreakPrediction()
        resetWavPlayback()
        ttsInitGeneration++
        cancelTtsWatchdogs()
        if (forgetVoice) {
            ttsVoiceName = null
        }
        textToSpeech?.runCatching {
            stop()
            shutdown()
        }
        textToSpeech = null
        ttsInitFinish = false
    }

    private fun onTtsInit(initGeneration: Int, status: Int) {
        if (initGeneration != ttsInitGeneration) {
            return
        }
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.let {
                restoreOrRememberVoice(it)
                it.setOnUtteranceProgressListener(ttsUtteranceListener)
                ttsInitFinish = true
                ttsInitWatchdogJob?.cancel()
                play()
            }
        } else {
            ttsInitWatchdogJob?.cancel()
            retryParagraphKey = null
            retryingTtsInit = false
            activeUtteranceId = null
            reportInitFailure()
            toastOnUi(R.string.tts_init_failed)
            pauseReadAloud(false)
        }
    }

    /**
     * 初始化失败原因暴露：默认引擎包名 + 本机已装 TTS 引擎清单。
     * 典型死因：系统默认引擎指向已卸载的应用（如上游宿主 MultiTTS 残留
     * tts_default_synth 设置），或整机未安装任何 TTS 引擎——
     * 只报"TTS 初始化失败"无法定位。
     */
    private fun reportInitFailure() {
        val tts = textToSpeech
        val detail = if (tts == null) {
            "TTS 实例未创建"
        } else {
            runCatching {
                val engines = tts.engines.map { it.name }
                val defaultEngine = tts.defaultEngine?.takeIf { it.isNotBlank() } ?: "未设置"
                "默认引擎：$defaultEngine；" +
                    if (engines.isEmpty()) "系统未安装任何 TTS 引擎" else "已安装：${engines.joinToString("、")}"
            }.getOrElse { "诊断信息获取失败：${it.localizedMessage}" }
        }
        AppLog.put(
            "${getString(R.string.tts_init_failed)}\n$detail",
            module = LogModule.READ_ALOUD,
        )
    }

    private fun restoreOrRememberVoice(tts: TextToSpeech) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return
        }
        val voiceName = ttsVoiceName
        if (!voiceName.isNullOrBlank()) {
            val voice = tts.voices?.firstOrNull { it.name == voiceName }
            if (voice != null && tts.voice?.name != voiceName) {
                if (tts.setVoice(voice) != TextToSpeech.SUCCESS) {
                    AppLog.putDebug("restore tts voice failed:$voiceName")
                }
            }
            return
        }
        tts.voice?.name?.takeIf { it.isNotBlank() }?.let {
            ttsVoiceName = it
        }
    }

    @Synchronized
    override fun play() {
        if (!ttsInitFinish) return
        if (!requestFocus()) return
        // 重新开始朗读：取消退出时已排队的缓存清扫（否则几秒后把刚合成的章节删掉）
        ReadBook.book?.let(TtsCacheJanitor::cancelPending)
        if (contentList.isEmpty()) {
            // ⚠️ 必须保留「空列表 → 推进下一章」：这是**章节边界续读**的恢复机制，
            // 读到章尾、或本章本身无可读内容（纯图片章等）时都靠它往下走。
            // 删掉/改宽会让朗读卡死在章末与章首之间。
            //
            // 但空列表另有一种含义：**本次起点还没准备落地**，此时连章节身份都还没有
            // （currentChapterIndex == -1）。此时推进下一章是错的——没有"当前章"可推，
            // switchReadAloudChapterKeepingViewByOffset 会报
            // "Detached read aloud chapter is missing" 并 stopSelf()，表现就是
            // 「完全退出后第一次点朗读立刻停止，第二次正常」（真机 5 轮复现 4 次）。
            // 成因是纯时序：TTS 引擎热态（MultiTTS 常驻）时 onTtsInit 只需几十毫秒，
            // 早于 newReadAloud 在 IO 上的章节准备。
            //
            // 判据只取「有没有章节身份」：正常章界续读时 currentChapterIndex 必然 >= 0，
            // 因此这条早退对章界续读零影响。准备落地后确认块会再调一次 play()。
            if (currentChapterIndex < 0) {
                AppLog.putDebug("Read aloud start not prepared yet, waiting")
                return
            }
            AppLog.putDebug("Read aloud content list is empty")
            nextChapter()
            return
        }
        super.play()
        MediaHelp.playSilentSound(this@TTSReadAloudService)
        speakGeneration++
        if (retryingTtsInit) {
            retryingTtsInit = false
        } else {
            retryParagraphKey = null
        }
        LogUtils.d(TAG, "contentList size:${contentList.size}")
        LogUtils.d(TAG, "pageSize:${textChapter?.pageSize}")
        speakCurrentParagraph()
    }

    override fun playStop() {
        cancelTtsWatchdogs()
        activeUtteranceId = null
        queuedUntilIndex = -1
        speakGeneration++
        cancelPageBreakPrediction()
        resetWavPlayback()
        retryParagraphKey = null
        retryingTtsInit = false
        textToSpeech?.runCatching {
            stop()
        }
    }

    @Synchronized
    private fun speakCurrentParagraph() {
        if (pause) return
        // 发起新朗读单元前作废旧页界预测，新单元 onStart 时按最新光标重新调度
        cancelPageBreakPrediction()
        if (AppConfig.ttsWavMode) {
            // 暂停恢复：播放器仍持有当前句现场，从暂停位置继续
            if (wavPausedPlayback
                && wavPlayer.currentMediaItem != null
                && wavPlayer.playbackState == Player.STATE_READY
                && !wavPlayer.isPlaying
            ) {
                wavPausedPlayback = false
                wavPlayer.play()
                upWavPlayPos()
                return
            }
            wavPausedPlayback = false
            speakWavCurrentParagraph()
            return
        }
        val tts = textToSpeech ?: throw NoStackTraceException("tts is null")
        while (nowSpeak < contentList.size) {
            var text = contentList[nowSpeak]
            if (paragraphStartPos > 0) {
                text = text.substring(paragraphStartPos.coerceAtMost(text.length))
            }
            if (!text.matches(AppPattern.notReadAloudRegex)) {
                val utteranceId = utteranceId(nowSpeak)
                activeUtteranceId = utteranceId
                queuedUntilIndex = nowSpeak
                // QUEUE_FLUSH 会让引擎在空闲队列上执行内部 stop 路径，MultiTTS 等引擎
                // 冷启动首次合成前 synthesizer 为 null，NPE 经 binder 同步透传回客户端。
                // 该异常意味着引擎侧合成会话已被打死：同会话 QUEUE_ADD 重试客户端侧
                // "成功"但请求在引擎内消失，utterance 回调永不到来，最终永久无声。
                // 唯一正确恢复是按 speak error 销毁重建 TTS 客户端
                // （handleSpeakError retryWithReinit），重建后引擎已热，当前段重试成功。
                val result = tts.runCatching {
                    speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                }.getOrElse {
                    AppLog.put(
                        "[朗读] 引擎speak异常，重建TTS客户端重试\n${it.localizedMessage}",
                        it,
                        module = LogModule.READ_ALOUD,
                    )
                    TextToSpeech.ERROR
                }
                if (result == TextToSpeech.ERROR) {
                    queuedUntilIndex = -1
                    handleSpeakError("tts speak error", retryWithReinit = true)
                } else {
                    armTtsStallWatchdog()
                    queueUpcomingUtterances(tts)
                }
                return
            }
            moveToNextParagraph()
        }
        nextChapter()
    }

    @Synchronized
    private fun moveToNextParagraph(): Boolean {
        if (nowSpeak >= contentList.size) return false
        readAloudNumber += contentList[nowSpeak].length + 1 - paragraphStartPos
        paragraphStartPos = 0
        nowSpeak++
        return nowSpeak < contentList.size
    }

    private fun isActiveUtterance(utteranceId: String?): Boolean {
        val index = utteranceIndex(utteranceId) ?: return false
        return index in 0..queuedUntilIndex && index in contentList.indices
    }

    private fun utteranceId(index: Int): String {
        return "${AppConst.APP_TAG}${speakGeneration}_$index"
    }

    private fun utteranceIndex(utteranceId: String?): Int? {
        val prefix = "${AppConst.APP_TAG}${speakGeneration}_"
        return utteranceId
            ?.takeIf { it.startsWith(prefix) }
            ?.substring(prefix.length)
            ?.toIntOrNull()
    }

    @Synchronized
    private fun syncToUtteranceIndex(index: Int) {
        while (nowSpeak < index && nowSpeak in contentList.indices) {
            moveToNextParagraph()
        }
    }

    @Synchronized
    private fun queueUpcomingUtterances(tts: TextToSpeech) {
        if (pause || queuedUntilIndex < nowSpeak) return
        var index = queuedUntilIndex + 1
        var preloadLength = 0
        while (index < contentList.size && preloadLength < minReadAloudPreloadLength()) {
            val text = contentList[index]
            if (text.matches(AppPattern.notReadAloudRegex)) {
                return
            }
            val result = tts.runCatching {
                speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId(index))
            }.getOrElse {
                AppLog.put("tts preload error\n${it.localizedMessage}", it)
                TextToSpeech.ERROR
            }
            if (result == TextToSpeech.ERROR) {
                return
            }
            queuedUntilIndex = index
            preloadLength += text.length
            index++
        }
    }

    @Synchronized
    private fun handleSpeakError(message: String, retryWithReinit: Boolean) {
        // 重试/重建引擎期间不存在「引擎吞了请求」的情形，不能让它被当成第二次失败而跳段。
        cancelRealtimeDryWatchdog()
        ttsStallWatchdogJob?.cancel()
        val paragraphKey = "$nowSpeak:$readAloudNumber:$paragraphStartPos"
        if (retryParagraphKey != paragraphKey) {
            AppLog.putDebug("$message, retry current paragraph")
            retryParagraphKey = paragraphKey
            activeUtteranceId = null
            queuedUntilIndex = -1
            speakGeneration++
            if (retryWithReinit) {
                retryingTtsInit = true
                clearTTS()
                initTts()
            } else {
                speakCurrentParagraph()
            }
            return
        }
        retryParagraphKey = null
        activeUtteranceId = null
        queuedUntilIndex = -1
        if (!moveToNextParagraph()) {
            nextChapter()
            return
        }
        speakCurrentParagraph()
    }

    override fun upSpeechRate(reset: Boolean) {
        if (AppConfig.ttsFlowSys) {
            if (reset) {
                clearTTS(forgetVoice = true)
                initTts()
            }
        } else {
            // 语速只设给引擎，不重启朗读会话：实测拖语速打断引擎 live 会话后，
            // live 会话后，预加载队列被瞬间逐句"完成"（每句 5~10ms），朗读游标被
            // 拽过整个预加载窗口，下一次重启音频直接跳到游标位置——段首/句尾拖动
            // 必现跳进度（模拟器+真机 logcat 证实）。新语速从下一句自然生效。
            textToSpeech?.setSpeechRate((AppConfig.ttsSpeechRate + 5) / 10f)
        }
    }

    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        cancelRealtimeDryWatchdog()
        ttsStallWatchdogJob?.cancel()
        activeUtteranceId = null
        queuedUntilIndex = -1
        speakGeneration++
        cancelPageBreakPrediction()
        retryParagraphKey = null
        retryingTtsInit = false
        if (AppConfig.ttsWavMode) {
            // 暂停时保留播放器现场，恢复时从当前位置继续当前句
            wavPosJob?.cancel()
            runCatching { wavPlayer.pause() }
            wavPausedPlayback = true
        }
        // tts.stop() 会冲掉引擎队列里的在途合成，任务列表在恢复/推进时重建
        clearRealtimePending()
        textToSpeech?.runCatching {
            stop()
        }
    }

    override fun resumeReadAloud() {
        super.resumeReadAloud()
        play()
    }

    private inner class TTSUtteranceListener : UtteranceProgressListener() {

        private val TAG = "TTSUtteranceListener"

        override fun onStart(s: String) {
            runActiveUtteranceCallback(s) {
                lastTtsCallbackAt = SystemClock.elapsedRealtime()
                utteranceIndex(s)?.let { syncToUtteranceIndex(it) }
                LogUtils.d(TAG, "onStart nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$s")
                textChapter?.let {
                    if (nowSpeak !in contentList.indices) return@runActiveUtteranceCallback
                    if (pageIndex + 1 < it.pageSize
                        && readAloudNumber + 1 > it.getReadLength(pageIndex + 1)
                    ) {
                        // 只推进引擎私有页光标，显示翻页由 UI 侧跟随规则处理
                        pageIndex++
                    }
                    upTtsProgress(readAloudNumber + 1)
                    // 本朗读单元开始：记录计时基准并调度页界预测
                    utteranceStartRealtime = SystemClock.elapsedRealtime()
                    lastRangeOffset = 0
                    schedulePageBreakPrediction(currentUtteranceTextLength())
                }
            }
        }

        override fun onDone(s: String) {
            if (AppConfig.ttsWavMode) {
                // Wav 模式：onDone 表示合成完成（synthesizeToFile），驱动播放
                onWavSynthesisDone(s)
                return
            }
            runActiveUtteranceCallback(s) {
                LogUtils.d(TAG, "onDone utteranceId:$s")
                lastTtsCallbackAt = SystemClock.elapsedRealtime()
                nextParagraph(s)
            }
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            super.onRangeStart(utteranceId, start, end, frame)
            runActiveUtteranceCallback(utteranceId) {
                lastTtsCallbackAt = SystemClock.elapsedRealtime()
                utteranceIndex(utteranceId)?.let { syncToUtteranceIndex(it) }
                val msg =
                    "onRangeStart nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$utteranceId start:$start end:$end frame:$frame"
                LogUtils.d(TAG, msg)
                // 引擎实时汇报朗读进度：用真实读速校准预测速率（指数平滑）
                val now = SystemClock.elapsedRealtime()
                val elapsed = now - utteranceStartRealtime
                if (start > 0 && elapsed > 500) {
                    val sample = start.toDouble() / elapsed
                    measuredCharRate = measuredCharRate * 0.7 + sample * 0.3
                }
                lastRangeOffset = start
                textChapter?.let {
                    if (pageIndex + 1 < it.pageSize
                        && readAloudNumber + start > it.getReadLength(pageIndex + 1)
                    ) {
                        // 按引擎实时进度过页界：只推进引擎私有页光标并发布位置，
                        // 显示翻页由 UI 侧跟随规则处理
                        pageIndex++
                        upTtsProgress(readAloudNumber + start)
                    }
                }
            }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            if (AppConfig.ttsWavMode) {
                onWavSynthesisError(utteranceIndex(utteranceId))
                return
            }
            runActiveUtteranceCallback(utteranceId) {
                utteranceIndex(utteranceId)?.let { syncToUtteranceIndex(it) }
                LogUtils.d(
                    TAG,
                    "onError nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$utteranceId errorCode:$errorCode"
                )
                handleSpeakError("tts utterance error:$errorCode", retryWithReinit = true)
            }
        }

        private fun nextParagraph(utteranceId: String?) {
            val index = utteranceIndex(utteranceId) ?: return
            if (index < nowSpeak) return
            syncToUtteranceIndex(index)
            // 本句真实总时长已知：先按它校准预测速率，再作废旧单元的页界预测
            //（预加载队列中下一单元 onStart 时会按最新光标与速率重新调度）
            val len = currentUtteranceTextLength()
            val elapsed = SystemClock.elapsedRealtime() - utteranceStartRealtime
            if (len > 0 && elapsed > 500) {
                val sample = len.toDouble() / elapsed
                measuredCharRate = measuredCharRate * 0.7 + sample * 0.3
                AppLog.putDebug(
                    "[朗读] 预测速率校准 len:$len 耗时:${elapsed}ms → " +
                        "${(measuredCharRate * 60_000).toInt()}/min"
                )
            }
            cancelPageBreakPrediction()
            activeUtteranceId = null
            retryParagraphKey = null
            if (!moveToNextParagraph()) {
                nextChapter()
                return
            }
            textToSpeech?.let { tts ->
                if (queuedUntilIndex >= nowSpeak) {
                    queueUpcomingUtterances(tts)
                } else {
                    speakCurrentParagraph()
                }
            } ?: speakCurrentParagraph()
        }

        @Deprecated("Deprecated in Java")
        override fun onError(s: String) {
            runActiveUtteranceCallback(s) {
                utteranceIndex(s)?.let { syncToUtteranceIndex(it) }
                LogUtils.d(TAG, "onError nowSpeak:$nowSpeak pageIndex:$pageIndex s:$s")
                handleSpeakError("tts utterance error", retryWithReinit = true)
            }
        }

        private fun runActiveUtteranceCallback(utteranceId: String?, block: () -> Unit) {
            if (!isActiveUtterance(utteranceId)) return
            lifecycleScope.launch(Main) {
                if (isActiveUtterance(utteranceId)) {
                    block.invoke()
                }
            }
        }

    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<TTSReadAloudService>(actionStr)
    }

}
