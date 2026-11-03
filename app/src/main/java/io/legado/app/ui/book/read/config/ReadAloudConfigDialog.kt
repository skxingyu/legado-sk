package io.legado.app.ui.book.read.config

import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.preference.ListPreference
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.base.BasePrefDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.help.IntentHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.tts.TtsCacheParams
import io.legado.app.lib.dialogs.showDecimalInputDialog
import io.legado.app.lib.dialogs.showIntegerInputDialog
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.lib.prefs.SwitchPreference
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isVideo
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.cache.TtsCacheChapterDialog
import io.legado.app.ui.book.cache.showBookDownloadRangeDialog
import io.legado.app.utils.StringUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.postEvent
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import java.math.BigDecimal
import kotlin.math.roundToInt

/**
 * 朗读设置的分组页签（对照 Rimchars 的 ReadAloudConfigGroup）。
 *
 * 按作者要求只保留 SK 实际拥有的三组：多角色设置由既有「AI 角色」对话框承担，
 * 不在本页重复一份。
 *
 * [preferenceKeys] 是该组要显示的偏好 key；未列在任何组里的 key 一律不显示，
 * 由 [ReadAloudConfigDialog.ReadAloudPreferenceFragment.selectGroup] 统一过滤。
 */
enum class ReadAloudConfigGroup(
    val titleRes: Int,
    val preferenceKeys: Set<String>
) {
    Basic(
        R.string.read_aloud_group_basic,
        setOf(
            PreferKey.ignoreAudioFocus,
            PreferKey.pauseReadAloudWhilePhoneCalls,
            PreferKey.readAloudWakeLock,
            PreferKey.bluetoothReadAloudSkipInterval,
            PreferKey.readAloudHideFloatingWindow,
            PreferKey.readAloudFloatOnDesktop,
            PreferKey.readAloudLongPressToAudioPlay
        )
    ),
    Reading(
        R.string.read_aloud_group_reading,
        setOf(
            PreferKey.forcePageFollow,
            PreferKey.pageSplit,
            PreferKey.ttsWavMode,
            PreferKey.ttsRealtimeCache,
            PreferKey.streamReadAloudAudio,
            PreferKey.ttsSpeechRate,
            PreferKey.readAloudCacheEntry,
            PreferKey.ttsCacheSettings,
            PreferKey.readAloudCoverRotation,
            PreferKey.readAloudCoverRotationDuration,
            PreferKey.readAloudScrollFollowTimeout,
            PreferKey.readAloudProgressPollInterval,
            PreferKey.readAloudHidePlaybackPanel,
            PreferKey.readAloudPlaybackPanelDuration,
            PreferKey.readAloudHidePagePanel,
            PreferKey.readAloudPanelOnPageFooter
        )
    ),
    Engine(
        R.string.read_aloud_group_engine,
        setOf(
            PreferKey.ttsEngine,
            PreferKey.sysTtsConfig
        )
    );

    companion object {
        val allPreferenceKeys: Set<String> = entries.flatMap { it.preferenceKeys }.toSet()
    }
}

class ReadAloudConfigDialog : BasePrefDialogFragment() {
    private val readAloudPreferTag = "readAloudPreferTag"
    private val groupTabs = arrayListOf<Pair<ReadAloudConfigGroup, android.widget.TextView>>()
    private var selectedGroup = ReadAloudConfigGroup.Basic

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            setBackgroundDrawableResource(R.color.transparent)
            val maxHeight = ((resources.displayMetrics.heightPixels * 0.72f).toInt())
                .coerceAtLeast(360.dpToPx())
            setLayout(0.9f, maxHeight)
        }
    }

    /**
     * 分页签骨架：顶部分组条 + 下方偏好列表。
     *
     * 对照 Rimchars 的 ReadAloudConfigGroup（基础/朗读/多角色/引擎），按作者要求
     * 只保留 SK 实际拥有的三组——多角色那一组的设置由既有「AI 角色」对话框承担，
     * 不在本页重复一份（否则同一开关两个入口、状态易漂移）。
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        groupTabs.clear()
        val context = requireContext()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipToOutline = true
            setPadding(10.dpToPx(), 10.dpToPx(), 10.dpToPx(), 8.dpToPx())
        }
        val tabBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(4.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
            setBackgroundResource(R.drawable.bg_bookshelf_tag_track)
        }
        ReadAloudConfigGroup.entries.forEach { group ->
            val tab = android.widget.TextView(context).apply {
                text = context.getString(group.titleRes)
                gravity = Gravity.CENTER
                textSize = 14f
                typeface = context.uiTypeface()
                isSingleLine = true
                background = UiCorner.actionSelector(
                    Color.TRANSPARENT,
                    UiCorner.themeSurfaceCardColor(context),
                    UiCorner.actionRadius(context)
                )
                setOnClickListener { selectGroup(group) }
            }
            groupTabs.add(group to tab)
            tabBar.addView(
                tab,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            )
        }
        root.addView(
            tabBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                42.dpToPx()
            ).apply { bottomMargin = 8.dpToPx() }
        )
        root.addView(
            android.widget.FrameLayout(context).apply { id = R.id.tag1 },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        container?.addView(root)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        var preferenceFragment =
            childFragmentManager.findFragmentByTag(readAloudPreferTag) as? ReadAloudPreferenceFragment
        if (preferenceFragment == null) {
            // 通过 arguments 传入当前分组：childFragmentManager 的 commit 是异步的，
            // 这里紧接着调 selectGroup 时片段还没 attach，靠它才能保证首屏就按正确分组过滤。
            preferenceFragment = ReadAloudPreferenceFragment().apply {
                arguments = Bundle().apply {
                    putString(ReadAloudPreferenceFragment.ARG_GROUP, selectedGroup.name)
                }
            }
        }
        childFragmentManager.beginTransaction()
            .replace(R.id.tag1, preferenceFragment, readAloudPreferTag)
            .commit()
        selectGroup(selectedGroup)
    }

    private fun selectGroup(group: ReadAloudConfigGroup) {
        selectedGroup = group
        groupTabs.forEach { (tabGroup, tab) ->
            val selected = tabGroup == group
            tab.isSelected = selected
            tab.setTextColor(
                if (selected) requireContext().primaryColor
                else requireContext().primaryTextColor
            )
        }
        (childFragmentManager.findFragmentByTag(readAloudPreferTag) as? ReadAloudPreferenceFragment)
            ?.selectGroup(group)
    }

    class ReadAloudPreferenceFragment : PreferenceFragment(),
        SpeakEngineDialog.CallBack,
        SharedPreferences.OnSharedPreferenceChangeListener {

        companion object {
            const val ARG_GROUP = "readAloudConfigGroup"
        }

        private var selectedGroup = ReadAloudConfigGroup.Basic

        /**
         * 是否按页签分组过滤。**只有**由 [ReadAloudConfigDialog] 承载（即带顶部页签）时为 true；
         * ConfigActivity 直接复用本 Fragment 时没有页签，过滤会把整页清空——那里必须全显示。
         */
        private var groupFilterEnabled = false

        /** 屏幕里实际存在的全部偏好 key（用于分组过滤，见 [applyGroupFilter]）。 */
        private fun androidx.preference.PreferenceScreen.allPreferenceKeys(): List<String> {
            val keys = arrayListOf<String>()
            fun collect(group: androidx.preference.PreferenceGroup) {
                for (index in 0 until group.preferenceCount) {
                    val child = group.getPreference(index)
                    if (child is androidx.preference.PreferenceGroup) collect(child)
                    else child.key?.let { keys.add(it) }
                }
            }
            collect(this)
            return keys
        }

        private val speakEngineSummary: String
            get() {
                val ttsEngine = ReadAloud.ttsEngine
                    ?: return getString(R.string.system_tts)
                if (ttsEngine == ReadAloud.SOURCE_AUDIO_ENGINE_ID) {
                    return getString(R.string.source_audio_engine)
                }
                if (StringUtils.isNumeric(ttsEngine)) {
                    return appDb.httpTTSDao.getName(ttsEngine.toLong())
                        ?: getString(R.string.http_tts_missing, ttsEngine)
                }
                return ReadAloud.selectedEngineLabel() ?: getString(R.string.system_tts)
            }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            // 由对话框承载时读取分组参数；ConfigActivity 承载时无参数 → 不过滤，全量显示。
            arguments?.getString(ARG_GROUP)?.let { name ->
                ReadAloudConfigGroup.entries.firstOrNull { it.name == name }?.let {
                    selectedGroup = it
                    groupFilterEnabled = true
                }
            }
            addPreferencesFromResource(R.xml.pref_config_aloud)
            upSpeakEngineSummary()
            initPhoneCallPausePreference()
            initFloatOnDesktopPreference()
            upFloatOnDesktopPreference()
            upCoverRotationDurationSummary()
            upScrollFollowTimeoutSummary()
            upProgressPollIntervalSummary()
            upPlaybackPanelDurationSummary()
            upSpeechRateSummary()
            upBluetoothReadAloudSkipIntervalSummary()
            if (groupFilterEnabled) applyGroupFilter()
        }

        /**
         * 分组过滤：按当前页签显示/隐藏偏好项。
         *
         * 只隐藏不删除——所有 key 始终留在 PreferenceScreen 里，避免页签切换时
         * 反复 add/remove 造成监听器与 summary 状态丢失。
         * 未启用分组（ConfigActivity 承载）时不做任何过滤，保持全量可见。
         */
        private fun applyGroupFilter() {
            preferenceScreen.allPreferenceKeys().forEach { key ->
                val preference = findPreference<Preference>(key) ?: return@forEach
                preference.isVisible = key in selectedGroup.preferenceKeys
            }
        }

        fun selectGroup(group: ReadAloudConfigGroup) {
            groupFilterEnabled = true
            selectedGroup = group
            if (isAdded) applyGroupFilter()
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            listView.background = null
            listView.clipToPadding = true
            listView.setEdgeEffectColor(primaryColor)
        }

        override fun onResume() {
            super.onResume()
            preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onPause() {
            preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
            super.onPause()
        }

        override fun onPreferenceTreeClick(preference: Preference): Boolean {
            when (preference.key) {
                PreferKey.ttsEngine -> showDialogFragment(SpeakEngineDialog())
                PreferKey.sysTtsConfig -> IntentHelp.openTTSSetting()
                PreferKey.ttsCacheSettings -> showDialogFragment(TtsCacheConfigDialog())
                // 听书页底部原先的缓存按钮：本地文字书缓存 TTS 音频，其余走正文下载范围。
                PreferKey.readAloudCacheEntry -> openCacheEntry()
                PreferKey.ttsSpeechRate -> showSpeechRateDialog()
                PreferKey.readAloudCoverRotationDuration -> showCoverRotationDurationDialog()
                PreferKey.readAloudScrollFollowTimeout -> showScrollFollowTimeoutDialog()
                PreferKey.readAloudProgressPollInterval -> showProgressPollIntervalDialog()
                PreferKey.bluetoothReadAloudSkipInterval ->
                    showBluetoothReadAloudSkipIntervalDialog()
                PreferKey.readAloudPlaybackPanelDuration -> showPlaybackPanelDurationDialog()
            }
            return super.onPreferenceTreeClick(preference)
        }

        /**
         * 缓存入口：与听书页原先的下载按钮同一语义。
         * 本地文字书 → TTS 章节缓存；不可用时明确提示原因（不再静默）。
         */
        private fun openCacheEntry() {
            val book = ReadBook.book ?: return
            val ttsCacheBook = book.takeIf { it.isLocal && !it.isAudio && !it.isVideo }
            if (ttsCacheBook != null) {
                TtsCacheParams.unavailableReasonRes(ttsCacheBook)?.let { reason ->
                    toastOnUi(reason)
                    return
                }
                TtsCacheChapterDialog.newInstance(ttsCacheBook)
                    .show(childFragmentManager, "ttsCacheChapterDialog")
            } else {
                showBookDownloadRangeDialog(book)
            }
        }

        /** 语速：滑条按 0.5x–5.0x 显示，落盘仍是整数刻度（与朗读面板同一存储）。 */
        private fun showSpeechRateDialog() {
            val current = AppConfig.ttsSpeechRate
            showIntegerInputDialog(
                title = R.string.read_aloud_speed,
                currentValue = current,
                validRange = 0..45,
                defaultValue = AppConfig.defaultSpeechRate
            ) {
                AppConfig.ttsSpeechRate = it
                upSpeechRateSummary()
            }
        }

        private fun upSpeechRateSummary() {
            findPreference<Preference>(PreferKey.ttsSpeechRate)?.summary =
                getString(R.string.read_aloud_speed_value, (AppConfig.ttsSpeechRate + 5) / 10f)
        }

        private fun showScrollFollowTimeoutDialog() {
            showIntegerInputDialog(
                title = R.string.read_aloud_scroll_follow_timeout_dialog_title,
                currentValue = AppConfig.readAloudScrollFollowTimeout,
                validRange = 0..10000,
                defaultValue = 3000
            ) {
                AppConfig.readAloudScrollFollowTimeout = it
                upScrollFollowTimeoutSummary()
            }
        }

        private fun upScrollFollowTimeoutSummary() {
            findPreference<Preference>(PreferKey.readAloudScrollFollowTimeout)?.summary =
                getString(
                    R.string.read_aloud_scroll_follow_timeout_value,
                    AppConfig.readAloudScrollFollowTimeout
                )
        }

        private fun showProgressPollIntervalDialog() {
            showIntegerInputDialog(
                title = R.string.read_aloud_progress_poll_interval_dialog_title,
                currentValue = AppConfig.readAloudProgressPollInterval,
                validRange = 100..5000,
                defaultValue = 500
            ) {
                AppConfig.readAloudProgressPollInterval = it
                upProgressPollIntervalSummary()
            }
        }

        private fun upProgressPollIntervalSummary() {
            findPreference<Preference>(PreferKey.readAloudProgressPollInterval)?.summary =
                getString(
                    R.string.read_aloud_progress_poll_interval_value,
                    AppConfig.readAloudProgressPollInterval
                )
        }

        private fun showBluetoothReadAloudSkipIntervalDialog() {
            showIntegerInputDialog(
                title = R.string.bluetooth_read_aloud_skip_interval_dialog_title,
                currentValue = AppConfig.bluetoothReadAloudSkipInterval,
                validRange = 0..Int.MAX_VALUE,
                defaultValue = AppConfig.DEFAULT_BLUETOOTH_READ_ALOUD_SKIP_INTERVAL
            ) {
                AppConfig.bluetoothReadAloudSkipInterval = it
                upBluetoothReadAloudSkipIntervalSummary()
            }
        }

        private fun upBluetoothReadAloudSkipIntervalSummary() {
            findPreference<Preference>(PreferKey.bluetoothReadAloudSkipInterval)?.summary =
                getString(
                    R.string.bluetooth_read_aloud_skip_interval_value,
                    AppConfig.bluetoothReadAloudSkipInterval
                )
        }

        private fun showPlaybackPanelDurationDialog() {
            showIntegerInputDialog(
                title = R.string.read_aloud_playback_panel_duration_dialog_title,
                currentValue = AppConfig.readAloudPlaybackPanelDuration,
                validRange =
                    AppConfig.MIN_READ_ALOUD_PLAYBACK_PANEL_DURATION..
                            AppConfig.MAX_READ_ALOUD_PLAYBACK_PANEL_DURATION,
                defaultValue = AppConfig.DEFAULT_READ_ALOUD_PLAYBACK_PANEL_DURATION
            ) {
                AppConfig.readAloudPlaybackPanelDuration = it
                upPlaybackPanelDurationSummary()
            }
        }

        private fun upPlaybackPanelDurationSummary() {
            val seconds = AppConfig.readAloudPlaybackPanelDuration
            val summary = if (seconds <= 0) {
                getString(R.string.read_aloud_playback_panel_duration_forever_summary)
            } else {
                getString(
                    R.string.read_aloud_playback_panel_duration_summary,
                    seconds
                )
            }
            findPreference<Preference>(PreferKey.readAloudPlaybackPanelDuration)?.summary = summary
        }

        private fun showCoverRotationDurationDialog() {
            val secondsRange =
                (AppConfig.MIN_READ_ALOUD_COVER_ROTATION_DURATION / 1000.0)..
                        (AppConfig.MAX_READ_ALOUD_COVER_ROTATION_DURATION / 1000.0)
            showDecimalInputDialog(
                title = R.string.read_aloud_cover_rotation_duration_dialog_title,
                currentValue = AppConfig.readAloudCoverRotationDuration / 1000.0,
                validRange = secondsRange,
                defaultValue = AppConfig.DEFAULT_READ_ALOUD_COVER_ROTATION_DURATION / 1000.0
            ) {
                AppConfig.readAloudCoverRotationDuration = (it * 1000).roundToInt()
                upCoverRotationDurationSummary()
            }
        }

        private fun upCoverRotationDurationSummary() {
            val seconds = BigDecimal.valueOf(
                AppConfig.readAloudCoverRotationDuration / 1000.0
            ).stripTrailingZeros().toPlainString()
            findPreference<Preference>(PreferKey.readAloudCoverRotationDuration)?.summary =
                getString(R.string.read_aloud_cover_rotation_duration_summary, seconds)
        }

        override fun onSharedPreferenceChanged(
            sharedPreferences: SharedPreferences?,
            key: String?
        ) {
            when (key) {
                PreferKey.pageSplit, PreferKey.streamReadAloudAudio, PreferKey.ttsWavMode,
                PreferKey.ttsRealtimeCache -> {
                    if (BaseReadAloudService.isRun) {
                        postEvent(EventBus.MEDIA_BUTTON, false)
                    }
                }

                PreferKey.readAloudFloatOnDesktop -> {
                    postEvent(PreferKey.readAloudFloatOnDesktop, "")
                }

                PreferKey.readAloudHideFloatingWindow -> {
                    upFloatOnDesktopPreference()
                    postEvent(PreferKey.readAloudHideFloatingWindow, "")
                }

                PreferKey.readAloudHidePlaybackPanel,
                PreferKey.readAloudHidePagePanel,
                PreferKey.readAloudPanelOnPageFooter -> {
                    postEvent(key, "")
                }

                PreferKey.readAloudScrollFollowTimeout -> upScrollFollowTimeoutSummary()
                PreferKey.bluetoothReadAloudSkipInterval ->
                    upBluetoothReadAloudSkipIntervalSummary()
                PreferKey.readAloudProgressPollInterval -> upProgressPollIntervalSummary()
                PreferKey.readAloudPlaybackPanelDuration -> {
                    upPlaybackPanelDurationSummary()
                    postEvent(key, "")
                }
                PreferKey.readAloudCoverRotationDuration -> upCoverRotationDurationSummary()

                PreferKey.ignoreAudioFocus -> {
                    Unit
                }
            }
        }

        private fun initFloatOnDesktopPreference() {
            findPreference<SwitchPreference>(PreferKey.readAloudFloatOnDesktop)
                ?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                    val enabled = newValue as? Boolean ?: return@OnPreferenceChangeListener true
                    if (enabled && !hasOverlayPermission()) {
                        requestOverlayPermission()
                    }
                    true
                }
        }

        private fun upFloatOnDesktopPreference() {
            findPreference<SwitchPreference>(PreferKey.readAloudFloatOnDesktop)?.isEnabled =
                !AppConfig.readAloudHideFloatingWindow
        }

        private fun initPhoneCallPausePreference() {
            findPreference<SwitchPreference>(PreferKey.pauseReadAloudWhilePhoneCalls)
                ?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { preference, newValue ->
                    val enabled = newValue as? Boolean ?: return@OnPreferenceChangeListener true
                    if (!enabled || hasReadPhoneStatePermission()) {
                        return@OnPreferenceChangeListener true
                    }
                    PermissionsCompat.Builder()
                        .addPermissions(Permissions.READ_PHONE_STATE)
                        .rationale(R.string.read_aloud_read_phone_state_permission_rationale)
                        .onGranted {
                            AppConfig.pauseReadAloudWhilePhoneCalls = true
                            (preference as? SwitchPreference)?.isChecked = true
                        }
                        .onDenied {
                            AppConfig.pauseReadAloudWhilePhoneCalls = false
                            (preference as? SwitchPreference)?.isChecked = false
                        }
                        .request()
                    false
                }
        }

        private fun hasReadPhoneStatePermission(): Boolean {
            return ContextCompat.checkSelfPermission(
                requireContext(),
                Permissions.READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED
        }

        private fun hasOverlayPermission(): Boolean {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                    Settings.canDrawOverlays(requireContext())
        }

        private fun requestOverlayPermission() {
            PermissionsCompat.Builder()
                .addPermissions(Permissions.SYSTEM_ALERT_WINDOW)
                .rationale(R.string.float_permission_rationale)
                .request()
        }

        private fun upPreferenceSummary(preference: Preference?, value: String) {
            when (preference) {
                is ListPreference -> {
                    val index = preference.findIndexOfValue(value)
                    preference.summary = if (index >= 0) preference.entries[index] else null
                }

                else -> {
                    preference?.summary = value
                }
            }
        }

        override fun upSpeakEngineSummary() {
            upPreferenceSummary(
                findPreference(PreferKey.ttsEngine),
                speakEngineSummary
            )
        }
    }
}
