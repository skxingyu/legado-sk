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
}
