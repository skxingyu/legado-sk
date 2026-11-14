package io.legado.app.help

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缓存过期判据的行为断言（内存层与数据库层共用）。
 *
 * 缺陷：内存层原先只按 LRU 容量淘汰、不看 `deadline`，带 saveTime 的
 * `cache.put(key, token, 3600)` 在进程内过期后仍返回旧值；
 * 表现是书源令牌过期后「突然全线失败、重启 App 自愈」。
 */
class CacheDeadlineTest {

    @Test
    fun zeroDeadlineNeverExpires() {
        assertFalse(cacheDeadlineExpired(0L, Long.MAX_VALUE))
    }

    @Test
    fun futureDeadlineIsFresh() {
        assertFalse(cacheDeadlineExpired(2_000L, 1_000L))
    }

    @Test
    fun pastDeadlineIsExpired() {
        assertTrue(cacheDeadlineExpired(1_000L, 2_000L))
    }

    @Test
    fun deadlineEqualToNowIsExpired() {
        assertTrue(cacheDeadlineExpired(1_000L, 1_000L))
    }
}
