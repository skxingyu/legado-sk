package io.legado.app.ui.config

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.AppLog
import io.legado.app.databinding.ActivityTtsEngineManageBinding
import io.legado.app.databinding.ItemTtsEngineV2Binding
import io.legado.app.help.tts.TtsEngineSetting
import io.legado.app.help.tts.TtsEngineStore
import io.legado.app.help.tts.TtsScriptEngineClient
import io.legado.app.help.tts.TtsScriptOption
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.share
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 脚本引擎详情页。唯一入口是经典朗读引擎选择弹窗中脚本行的长按；
 * 这里不再承担引擎选择、系统 TTS 或内置语音包的重复入口。
 */
class TtsEngineManageActivity : BaseActivity<ActivityTtsEngineManageBinding>() {

    companion object {
        const val EXTRA_SCRIPT_ENGINE_ID = "scriptEngineId"

        fun intent(context: Context, engineId: String): Intent {
            return Intent(context, TtsEngineManageActivity::class.java)
                .putExtra(EXTRA_SCRIPT_ENGINE_ID, engineId)
        }
    }

    override val binding by viewBinding(ActivityTtsEngineManageBinding::inflate)

    private val engineId: String by lazy {
        intent.getStringExtra(EXTRA_SCRIPT_ENGINE_ID)
            ?.takeIf { it.isNotBlank() }
            ?: error("脚本引擎详情页缺少引擎 id")
    }
    private val adapter by lazy { Adapter() }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        setSupportActionBar(binding.toolBar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        initRecyclerView()
        refreshData()
    }

    override fun onResume() {
        super.onResume()
        refreshData()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun initRecyclerView() = binding.run {
        recyclerView.setEdgeEffectColor(primaryColor)
        recyclerView.layoutManager = LinearLayoutManager(this@TtsEngineManageActivity)
        recyclerView.adapter = adapter
    }

    private fun refreshData() {
        lifecycleScope.launch(IO) {
            val engine = TtsEngineStore.scriptEngineById(engineId)
            val voiceCounts = TtsEngineStore.voiceCounts()
            withContext(Dispatchers.Main) {
                if (engine == null) {
                    val message = "脚本引擎详情无法打开：引擎「$engineId」不存在"
                    AppLog.put(message)
                    toastOnUi(message)
                    finish()
                    return@withContext
                }
                title = getString(R.string.tts_engine_script_detail)
                adapter.setItems(listOf(engine), voiceCounts)
            }
        }
    }

    private fun exportEngine(engine: TtsEngineSetting) {
        lifecycleScope.launch {
            val text = withContext(IO) { engine.script }
            runCatching { share(text, "${engine.name}.js") }
                .onSuccess { toastOnUi(R.string.tts_engine_v2_export_success) }
                .onFailure { sendToClip(text) }
        }
    }

    inner class Adapter :
        RecyclerAdapter<TtsEngineSetting, ItemTtsEngineV2Binding>(this@TtsEngineManageActivity) {

        private var voiceCounts: Map<String, Int> = emptyMap()

        fun setItems(items: List<TtsEngineSetting>, voiceCounts: Map<String, Int>) {
            this.voiceCounts = voiceCounts
            setItems(items)
        }

        override fun getViewBinding(parent: ViewGroup): ItemTtsEngineV2Binding {
            return ItemTtsEngineV2Binding.inflate(inflater, parent, false)
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemTtsEngineV2Binding) {
            binding.root.setOnClickListener {
                getItemByLayoutPosition(holder.layoutPosition)?.let(::showItemMenu)
            }
            binding.root.setOnLongClickListener {
                getItemByLayoutPosition(holder.layoutPosition)?.let { engine ->
                    showItemMenu(engine)
                    true
                } ?: false
            }
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemTtsEngineV2Binding,
            item: TtsEngineSetting,
            payloads: MutableList<Any>
        ) {
            binding.run {
                tvName.text = item.name
                val state = if (item.enabled) {
                    getString(R.string.tts_engine_v2_state_enabled)
                } else {
                    getString(R.string.tts_engine_v2_state_disabled)
                }
                val voiceCount = maxOf(
                    item.effectiveVoices().size,
                    voiceCounts[item.id] ?: 0
                )
                tvSummary.text = getString(
                    R.string.tts_engine_v2_summary_format,
                    "V2", voiceCount, state
                )
            }
        }

        private fun showItemMenu(engine: TtsEngineSetting) {
            val options = buildList {
                add(
                    if (engine.enabled) getString(R.string.tts_engine_v2_disable)
                    else getString(R.string.tts_engine_v2_enable)
                )
                if (engine.supportsVoiceFetch()) {
                    add(getString(R.string.tts_engine_v2_fetch_voices))
                }
                add(getString(R.string.tts_engine_v2_pick_voice))
                add(getString(R.string.tts_engine_v2_options))
                add(getString(R.string.export))
                add(getString(R.string.delete))
            }
            alert(title = engine.name) {
                items(options) { _: DialogInterface, index: Int ->
                    when (options[index]) {
                        getString(R.string.tts_engine_v2_enable),
                        getString(R.string.tts_engine_v2_disable) -> toggleEnabled(engine)
                        getString(R.string.tts_engine_v2_fetch_voices) -> fetchVoices(engine)
                        getString(R.string.tts_engine_v2_pick_voice) -> pickVoice(engine)
                        getString(R.string.tts_engine_v2_options) -> editOptions(engine)
                        getString(R.string.export) -> exportEngine(engine)
                        getString(R.string.delete) -> deleteEngine(engine)
                    }
                }
            }
        }

        private fun fetchVoices(engine: TtsEngineSetting) {
            lifecycleScope.launch(IO) {
                toastOnUi(R.string.tts_engine_v2_fetching)
                runCatching {
                    TtsEngineStore.ensureVoiceCatalog(engine.id, forceRefresh = true)
                }.onSuccess {
                    toastOnUi(R.string.tts_engine_v2_fetch_done)
                    refreshData()
                }.onFailure { error ->
                    AppLog.put("获取发音人目录失败\n${error.localizedMessage}", error)
                    toastOnUi(error.localizedMessage)
                }
            }
        }

        private fun pickVoice(engine: TtsEngineSetting) {
            // selectVoice 只处理已启用的引擎，禁用时先提示，避免点了没反应
            if (!engine.enabled) {
                toastOnUi(getString(R.string.tts_engine_v2_enable_first, engine.name))
                return
            }
            lifecycleScope.launch {
                val voices = withContext(IO) {
                    runCatching {
                        if (engine.effectiveVoices().isEmpty() && engine.supportsVoiceFetch()) {
                            TtsEngineStore.ensureVoiceCatalog(engine.id)
                        } else {
                            engine
                        }
                    }.onFailure { error ->
                        AppLog.put("读取发音人目录失败\n${error.localizedMessage}", error)
                        toastOnUi(error.localizedMessage)
                    }.getOrNull()
                }?.effectiveVoices().orEmpty()
                if (voices.isEmpty()) {
                    toastOnUi(R.string.tts_engine_v2_fetch_empty)
                    return@launch
                }
                // 同名音色（如度博文有精品/臻品/大模型三档）靠音库标签区分
                val labels = voices.map { voice ->
                    val tier = voice.tags.lastOrNull().orEmpty()
                    if (tier.isEmpty()) voice.name else "${voice.name} · $tier"
                }
                val checked = voices.indexOfFirst { it.id == engine.activeVoiceId }
                alert(title = engine.name) {
                    singleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                        dialog.dismiss()
                        val voice = voices[which]
                        lifecycleScope.launch(IO) {
                            if (TtsEngineStore.selectVoice(engine.id, voice.id) == null) {
                                AppLog.put("选择发音人失败: ${engine.id}/${voice.id}")
                            } else {
                                toastOnUi(voice.name)
                                refreshData()
                            }
                        }
                    }
                    negativeButton(R.string.cancel)
                }
            }
        }

        private fun editOptions(engine: TtsEngineSetting) {
            lifecycleScope.launch {
                val options = withContext(IO) {
                    runCatching { TtsScriptEngineClient.loadOptions(engine) }
                }.getOrElse { error ->
                    AppLog.put("读取脚本选项失败\n${error.localizedMessage}", error)
                    toastOnUi(error.localizedMessage)
                    return@launch
                }
                if (options.isEmpty()) {
                    toastOnUi(R.string.tts_engine_v2_options_empty)
                    return@launch
                }
                showOptions(engine, options, engine.effectiveOptionValues(options).toMutableMap())
            }
        }

        private fun showOptions(
            engine: TtsEngineSetting,
            options: List<TtsScriptOption>,
            values: MutableMap<String, String>
        ) {
            val rows = options.map { option ->
                val value = values[option.safeKey].orEmpty()
                val shown = when {
                    value.isEmpty() -> getString(R.string.tts_engine_v2_option_unset)
                    option.normalizedType == "password" -> "••••••"
                    else -> value
                }
                "${option.displayLabel}: $shown"
            }
            alert(title = engine.name) {
                items(rows) { _: DialogInterface, index: Int ->
                    editOption(engine, options, values, options[index])
                }
                positiveButton(R.string.sure) { saveOptions(engine, options, values) }
                negativeButton(R.string.cancel)
            }
        }

        private fun editOption(
            engine: TtsEngineSetting,
            options: List<TtsScriptOption>,
            values: MutableMap<String, String>,
            option: TtsScriptOption
        ) {
            val key = option.safeKey
            if (key.isEmpty()) return
            when (option.normalizedType) {
                "boolean" -> {
                    values[key] =
                        if (values[key] == "true" || values[key] == "1") "false" else "true"
                    showOptions(engine, options, values)
                }

                "select" -> {
                    val items = option.safeValues
                    if (items.isEmpty()) return
                    selector(title = option.displayLabel, items = items.map { it.label }) { _, index ->
                        values[key] = items[index].value
                        showOptions(engine, options, values)
                    }
                }

                else -> {
                    val input = EditText(this@TtsEngineManageActivity).apply {
                        setText(values[key].orEmpty())
                        setSingleLine(true)
                        hint = key
                        when (option.normalizedType) {
                            "password" -> inputType =
                                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

                            "number" -> inputType = InputType.TYPE_CLASS_NUMBER or
                                    InputType.TYPE_NUMBER_FLAG_DECIMAL or
                                    InputType.TYPE_NUMBER_FLAG_SIGNED
                        }
                    }
                    alert(title = option.displayLabel) {
                        customView { input }
                        positiveButton(R.string.sure) {
                            values[key] = input.text.toString().trim()
                            showOptions(engine, options, values)
                        }
                        negativeButton(R.string.cancel)
                    }
                }
            }
        }

        private fun saveOptions(
            engine: TtsEngineSetting,
            options: List<TtsScriptOption>,
            values: Map<String, String>
        ) {
            val current = engine.effectiveOptionValues(options)
            val updated = engine.optionValues.toMutableMap()
            options.forEach { option ->
                val key = option.safeKey
                if (key.isNotEmpty() && values[key] != current[key]) {
                    updated[key] = values[key].orEmpty()
                }
            }
            if (updated == engine.optionValues) return
            lifecycleScope.launch {
                runCatching {
                    withContext(IO) {
                        TtsEngineStore.saveEngine(engine.copy(optionValues = updated.toMap()))
                    }
                }.onSuccess {
                    toastOnUi(R.string.tts_engine_v2_options_saved)
                    refreshData()
                }.onFailure { error ->
                    AppLog.put("保存脚本选项失败\n${error.localizedMessage}", error)
                    toastOnUi(error.localizedMessage)
                }
            }
        }

        private fun toggleEnabled(engine: TtsEngineSetting) {
            lifecycleScope.launch(IO) {
                TtsEngineStore.saveEngine(engine.copy(enabled = !engine.enabled))
                refreshData()
            }
        }

        private fun deleteEngine(engine: TtsEngineSetting) {
            alert(message = getString(R.string.tts_engine_v2_delete_confirm, engine.name)) {
                positiveButton(R.string.sure) {
                    lifecycleScope.launch(IO) {
                        val deleted = TtsEngineStore.deleteEngine(engine.id)
                        withContext(Dispatchers.Main) {
                            if (deleted) finish()
                        }
                    }
                }
                negativeButton(R.string.cancel)
            }
        }
    }
}
