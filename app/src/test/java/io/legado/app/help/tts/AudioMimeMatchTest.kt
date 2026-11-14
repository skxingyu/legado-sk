package io.legado.app.help.tts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 脚本引擎的音频 Content-Type 比对（直接调真实纯函数）。
 *
 * 旧实现用 `contentType.matches(expected.toRegex())` 全串匹配：服务端回
 * `audio/x-wav`、`audio/mp3` 这类等价写法时会被判成「TTS服务器返回错误」，
 * 正常音频被当错误体吞掉。
 */
class AudioMimeMatchTest {

    @Test
    fun identicalTypesMatch() {
        assertTrue(audioMimeMatches("audio/mpeg", "audio/mpeg"))
    }

    @Test
    fun parametersAndCaseAreIgnored() {
        assertTrue(audioMimeMatches("Audio/MPEG; charset=binary", "audio/mpeg"))
    }

    @Test
    fun xPrefixIsEquivalent() {
        assertTrue(audioMimeMatches("audio/x-wav", "audio/wav"))
        assertTrue(audioMimeMatches("audio/wav", "audio/x-wav"))
    }

    @Test
    fun commonAliasesAreEquivalent() {
        assertTrue(audioMimeMatches("audio/mp3", "audio/mpeg"))
        assertTrue(audioMimeMatches("audio/wave", "audio/wav"))
    }

    @Test
    fun differentAudioTypesStillFail() {
        assertFalse(audioMimeMatches("audio/mpeg", "audio/wav"))
        assertFalse(audioMimeMatches("audio/ogg", "audio/mpeg"))
    }
}
