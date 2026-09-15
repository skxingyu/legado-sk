package io.legado.app.plugin

import io.legado.app.constant.AppConst

/**
 * 内置 AI 出厂配置注册表：启动时由各 flavor 的 [AppPlugins] 填充。
 * 开源构建保持空——内置供应商种入/补齐的 apiKey 与默认 LLM 请求头全部为空串，
 * 行为与未引入本注册表前完全一致；出厂值明文只存在于自有构建源集。
 */
object AiBuiltinDefaults {

    interface Plugin {
        /** 内置图片供应商「硅基流动」出厂 apiKey */
        fun builtinSiliconFlowApiKey(): String

        /** 内置图片/视频供应商「智谱」出厂 apiKey（同一控制台的同一把 key） */
        fun builtinZhipuApiKey(): String

        /** 默认 LLM 供应商出厂请求头（逐行 "K: V"） */
        fun defaultLlmHeaders(): String
    }

    private var plugin: Plugin? = null

    fun register(plugin: Plugin) {
        this.plugin = plugin
    }

    fun siliconFlowApiKey(): String = plugin?.builtinSiliconFlowApiKey().orEmpty()

    fun zhipuApiKey(): String = plugin?.builtinZhipuApiKey().orEmpty()

    fun llmHeaders(): String = plugin?.defaultLlmHeaders().orEmpty()

    /**
     * 本机稳定的 OpenCode 会话标识。
     *
     * OpenCode Zen 免费通道要求请求带会话头（缺则 MissingSessionID 400），且匿名额度按
     * IP 计；若所有安装共用同一字面量 id，不同设备/不同出口 IP 会互相顶掉同一会话，
     * 徒增触发限流与风控的概率。故按设备派生一个稳定、不可逆推的 id：
     * 同一台设备每次生成结果一致（重装/备份恢复后仍可复现），不同设备互不相同。
     */
    fun openCodeSessionId(): String = sessionIdOf(AppConst.androidId)

    /** 由任意设备种子派生会话 id；抽成纯函数便于单测，见 [openCodeHeaders]。 */
    fun sessionIdOf(deviceSeed: String): String = "legado-sk-" + stableDeviceToken(deviceSeed)

    /**
     * Zen 免费通道所需的会话请求头（逐行 "K: V"）。
     *
     * 实测最小必要集为「会话头 + user-agent」；其余关联头是与官方 CLI 同形的做法，
     * 一并带上以降低被判定为非 CLI 流量的概率。
     */
    fun openCodeHeaders(sessionId: String): String = buildString {
        append("user-agent: opencode/1.17.9").append('\n')
        append("x-opencode-client: cli").append('\n')
        append("X-Session-Id: ").append(sessionId).append('\n')
        append("x-opencode-session: ").append(sessionId).append('\n')
        append("x-opencode-project: ").append(sessionId).append('\n')
        append("x-session-affinity: ").append(sessionId)
    }

    /** 设备指纹 → 16 位十六进制；输入不含任何可识别个人的明文。 */
    private fun stableDeviceToken(seed: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(seed.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * 把出厂会话头合并进用户现有请求头，**只补缺失项，不整条替换**。
     *
     * 存量装机场景：早期版本出厂请求头为空，用户可能已自行填过 `user-agent` 等自定义头，
     * 但缺会话头 → Zen 免费通道 400 MissingSessionID。整条覆盖会冲掉用户的自定义头，
     * 故按**键名大小写不敏感**逐行合并：
     * - 用户已有的键保留用户值（用户显式配置优先）；
     * - 用户没有的键补上出厂值。
     *
     * 逐行 "K: V" 文本格式，与 [openCodeHeaders] / 供应商 headers 字段一致。
     * 抽成纯函数便于单测。
     */
    fun mergeMissingHeaders(existing: String?, builtin: String): String {
        val current = existing.orEmpty()
        val existingKeys = current.lineSequence()
            .mapNotNull { it.toHeaderPair()?.first?.lowercase() }
            .toSet()
        val missing = builtin.lineSequence()
            .mapNotNull { it.toHeaderPair() }
            .filter { it.first.lowercase() !in existingKeys }
            .map { "${it.first}: ${it.second}" }
            .toList()
        if (missing.isEmpty()) return current
        val base = current.trim().trimEnd('\n')
        val merged = if (base.isBlank()) missing else listOf(base) + missing
        return merged.joinToString("\n")
    }

    /** 解析一行 "K: V" 或 "K=V"；空行与 # 注释行返回 null。 */
    private fun String.toHeaderPair(): Pair<String, String>? {
        val line = trim()
        if (line.isBlank() || line.startsWith("#")) return null
        val separator = line.indexOf(':').takeIf { it > 0 }
            ?: line.indexOf('=').takeIf { it > 0 }
            ?: return null
        val key = line.substring(0, separator).trim()
        val value = line.substring(separator + 1).trim()
        return if (key.isNotBlank() && value.isNotBlank()) key to value else null
    }
}
