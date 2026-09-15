package io.legado.app.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiBuiltinDefaultsTest {

    @Test
    fun sessionIdIsStableForSameDevice() {
        val first = AiBuiltinDefaults.sessionIdOf("device-a")
        val second = AiBuiltinDefaults.sessionIdOf("device-a")

        assertEquals(first, second)
        assertTrue(first.startsWith("legado-sk-"))
        assertEquals("legado-sk-".length + 16, first.length)
    }

    @Test
    fun sessionIdDiffersAcrossDevices() {
        // 匿名额度按 IP 计；同一 id 跨设备复用会互相顶掉会话，必须逐设备不同
        assertNotEquals(
            AiBuiltinDefaults.sessionIdOf("device-a"),
            AiBuiltinDefaults.sessionIdOf("device-b")
        )
    }

    @Test
    fun headersCarryEveryZenRequiredField() {
        val sessionId = AiBuiltinDefaults.sessionIdOf("device-a")
        val lines = AiBuiltinDefaults.openCodeHeaders(sessionId).lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            .toMap()

        // 实测：缺会话头或 user-agent 时 Zen 免费通道返回 400 MissingSessionID
        assertEquals("opencode/1.17.9", lines["user-agent"])
        assertEquals(sessionId, lines["x-session-id"])
        assertEquals("cli", lines["x-opencode-client"])
        assertEquals(sessionId, lines["x-opencode-session"])
        assertEquals(sessionId, lines["x-opencode-project"])
        assertEquals(sessionId, lines["x-session-affinity"])
    }

    private fun parse(raw: String): Map<String, String> = raw.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        .toMap()

    /**
     * 存量补齐必须"只增不覆盖"：用户自改过的请求头不能被整条替换冲掉。
     * 回归背景：原实现 `copy(headers = headers)` 只要不含 X-Session-Id 就整条覆盖，
     * 与 [fillDefaultAiHeadersIfNeeded] 的「用户清空请求头保持清空」承诺相矛盾。
     */
    @Test
    fun mergeKeepsUserHeadersAndOnlyAddsMissingOnes() {
        val builtin = AiBuiltinDefaults.openCodeHeaders(AiBuiltinDefaults.sessionIdOf("device-a"))
        val user = "user-agent: my-custom-agent\nX-Custom-Auth: secret"

        val merged = parse(AiBuiltinDefaults.mergeMissingHeaders(user, builtin))

        // 用户已有值优先
        assertEquals("my-custom-agent", merged["user-agent"])
        assertEquals("secret", merged["x-custom-auth"])
        // 缺失的会话头被补上
        assertTrue(merged.containsKey("x-session-id"))
        assertTrue(merged.containsKey("x-opencode-session"))
        assertTrue(merged.containsKey("x-opencode-client"))
    }

    /** 键名大小写不敏感：用户写了 `User-Agent` 也算已有，不应被出厂值覆盖。 */
    @Test
    fun mergeTreatsHeaderNamesCaseInsensitively() {
        val builtin = AiBuiltinDefaults.openCodeHeaders("legado-sk-test")
        val merged = parse(AiBuiltinDefaults.mergeMissingHeaders("USER-AGENT: keep-me", builtin))

        assertEquals("keep-me", merged["user-agent"])
        assertTrue(merged.containsKey("x-session-id"))
    }

    /** 存量装机最常见的形态：出厂请求头为空串 → 应完整补上出厂头。 */
    @Test
    fun mergeFillsBlankHeadersEntirely() {
        val builtin = AiBuiltinDefaults.openCodeHeaders("legado-sk-test")

        assertEquals(builtin, AiBuiltinDefaults.mergeMissingHeaders("", builtin))
        assertEquals(builtin, AiBuiltinDefaults.mergeMissingHeaders(null, builtin))
    }

    /** 已齐全时保持原样，不产生重复行或多余空白。 */
    @Test
    fun mergeIsNoOpWhenNothingIsMissing() {
        val builtin = AiBuiltinDefaults.openCodeHeaders("legado-sk-test")

        assertEquals(builtin, AiBuiltinDefaults.mergeMissingHeaders(builtin, builtin))
    }

    /**
     * 结果里不允许出现重复的会话头键（否则请求头语义不确定）。
     *
     * 注意：用户已有的 `x-session-id` 会被**保留**（用户显式配置优先），
     * 出厂值只在缺失时补 —— 故此处断言的是"不重复"而非"等于出厂值"。
     */
    @Test
    fun mergeNeverDuplicatesSessionHeader() {
        val builtin = AiBuiltinDefaults.openCodeHeaders("legado-sk-test")
        val merged = AiBuiltinDefaults.mergeMissingHeaders("x-session-id: old-value", builtin)

        val sessionHeaderCount = merged.lineSequence()
            .count { it.substringBefore(':').trim().equals("x-session-id", ignoreCase = true) }

        assertEquals(1, sessionHeaderCount)
        // 用户已有值保留，其余出厂头补齐
        assertEquals("old-value", parse(merged)["x-session-id"])
        assertEquals("cli", parse(merged)["x-opencode-client"])
    }
}
