package io.legado.app.help

import android.graphics.Bitmap
import android.webkit.JavascriptInterface
import androidx.annotation.Keep
import androidx.collection.LruCache
import io.legado.app.data.appDb
import io.legado.app.data.entities.Cache
import io.legado.app.model.analyzeRule.QueryTTF
import io.legado.app.utils.ACache
import io.legado.app.utils.memorySize

private val queryTTFMap = LruCache<String, QueryTTF>(4)

/**
 * 内存条目的过期时间（毫秒时间戳，0 = 不过期）。内存层原先不记 TTL，
 * 带 saveTime 的 `put` 在进程内一直命中旧值（令牌/元数据过期后仍返回）。
 */
private val memoryDeadlines = LruCache<String, Long>(4096)

/** 缓存过期判据：`deadline == 0` 表示永不过期；内存层与数据库层共用。 */
internal fun cacheDeadlineExpired(deadline: Long, now: Long): Boolean =
    deadline != 0L && deadline <= now

/**
 * 最多只缓存50M的数据,防止OOM
 */
private val memoryLruCache = object : LruCache<String, Any>(1024 * 1024 * 50) {

    override fun sizeOf(key: String, value: Any): Int {
        return value.estimatedMemorySize().coerceAtLeast(1)
    }

}

private fun Any?.estimatedMemorySize(): Int {    val size = when (this) {
        null -> 0L
        is String -> memorySize().toLong()
        is CharSequence -> toString().memorySize().toLong()
        is ByteArray -> size.toLong()
        is Bitmap -> runCatching { allocationByteCount }.getOrElse { rowBytes * height }.toLong()
        is Int, is Float -> 4L
        is Long, is Double -> 8L
        is Boolean, is Byte -> 1L
        is Short, is Char -> 2L
        is Pair<*, *> -> 16L + first.estimatedMemorySize() + second.estimatedMemorySize()
        is Collection<*> -> fold(16L) { acc, item -> acc + item.estimatedMemorySize() }
        is Map<*, *> -> entries.fold(32L) { acc, entry ->
            acc + entry.key.estimatedMemorySize() + entry.value.estimatedMemorySize()
        }
        else -> toString().memorySize().toLong()
    }
    return size.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}

object AppCacheManager {

    fun put(key: String, queryTTF: QueryTTF) {
        queryTTFMap.put(key, queryTTF)
    }

    fun getQueryTTF(key: String): QueryTTF? {
        return queryTTFMap[key]
    }

    fun clearSourceVariables() {
        memoryLruCache.snapshot().keys.forEach {
            if (it.startsWith("v_")
                || it.startsWith("userInfo_")
                || it.startsWith("loginHeader_")
                || it.startsWith("sourceVariable_")
            ) {
                memoryLruCache.remove(it)
            }
        }
    }

}


@Keep
@Suppress("unused")
object CacheManager {

    /**
     * saveTime 单位为秒
     */
    @JvmOverloads
    fun put(key: String, value: Any, saveTime: Int = 0) {
        val deadline =
            if (saveTime == 0) 0 else System.currentTimeMillis() + saveTime * 1000
        when (value) {
            is ByteArray -> ACache.get().put(key, value, saveTime)
            else -> {
                val valueStr = value.toString()
                putMemory(key, valueStr, deadline)
                val cache = Cache(key, valueStr, deadline)
                appDb.cacheDao.insert(cache)
            }
        }
    }

    fun putMemory(key: String, value: Any) {
        // 直接写内存（会话 cookie、WebView 结果等）不带 TTL，必须清掉旧条目的截止时间。
        memoryDeadlines.remove(key)
        memoryLruCache.put(key, value)
    }

    private fun putMemory(key: String, value: Any, deadline: Long) {
        memoryLruCache.put(key, value)
        if (deadline == 0L) memoryDeadlines.remove(key) else memoryDeadlines.put(key, deadline)
    }

    //从内存中获取数据 使用lruCache
    fun getFromMemory(key: String): Any? {
        val value = memoryLruCache[key] ?: return null
        if (cacheDeadlineExpired(memoryDeadlines[key] ?: 0L, System.currentTimeMillis())) {
            deleteMemory(key)
            return null
        }
        return value
    }

    fun deleteMemory(key: String) {
        memoryLruCache.remove(key)
        memoryDeadlines.remove(key)
    }

    fun get(key: String): String? {
        getFromMemory(key)?.let {
            if (it is String) return it
        }
        val cache = appDb.cacheDao.get(key)
        if (cache != null) {
            if (cacheDeadlineExpired(cache.deadline, System.currentTimeMillis())) {
                return null
            }
            return cache.value?.also {
                putMemory(key, it, cache.deadline)
            }
        }
        return null
    }

    fun get(key: String, onlyDisk: Boolean): String? {
        if (!onlyDisk) {
            return get(key)
        }
        val cache = appDb.cacheDao.get(key)
        if (cache != null && !cacheDeadlineExpired(cache.deadline, System.currentTimeMillis())) {
            return cache.value
        }
        return null
    }

    fun getInt(key: String): Int? {
        getFromMemory(key)?.let {
            if (it is Int) return it
        }
        return get(key, true)?.toIntOrNull()
    }

    fun getLong(key: String): Long? {
        getFromMemory(key)?.let {
            if (it is Long) return it
        }
        return get(key, true)?.toLongOrNull()
    }

    fun getDouble(key: String): Double? {
        getFromMemory(key)?.let {
            if (it is Double) return it
        }
        return get(key, true)?.toDoubleOrNull()
    }

    fun getFloat(key: String): Float? {
        getFromMemory(key)?.let {
            if (it is Float) return it
        }
        return get(key, true)?.toFloatOrNull()
    }

    fun getByteArray(key: String): ByteArray? {
        return ACache.get().getAsBinary(key)
    }

    fun putFile(key: String, value: String, saveTime: Int = 0) {
        ACache.get().put(key, value, saveTime)
    }

    fun getFile(key: String): String? {
        return ACache.get().getAsString(key)
    }

    fun delete(key: String) {
        appDb.cacheDao.delete(key)
        deleteMemory(key)
        ACache.get().remove(key)
    }
}

@Keep
@Suppress("unused")
object WebCacheManager {
    @JvmOverloads
    @JavascriptInterface
    fun put(key: String, value: String, saveTime: Int = 0) {
        CacheManager.put(key, value, saveTime)
    }
    @JavascriptInterface
    fun putMemory(key: String, value: String) {
        memoryLruCache.put(key, value)
    }
    @JavascriptInterface
    fun getFromMemory(key: String): String? {
        return memoryLruCache[key]?.toString()
    }
    @JavascriptInterface
    fun deleteMemory(key: String) {
        memoryLruCache.remove(key)
    }
    @JavascriptInterface
    fun get(key: String): String? {
        return CacheManager.get(key)
    }
    @JavascriptInterface
    fun get(key: String, onlyDisk: Boolean): String? {
        return CacheManager.get(key, onlyDisk)
    }
    @JavascriptInterface
    fun putFile(key: String, value: String, saveTime: Int = 0) {
        CacheManager.putFile(key, value, saveTime)
    }
    @JavascriptInterface
    fun getFile(key: String): String? {
        return CacheManager.getFile(key)
    }
    @JavascriptInterface
    fun delete(key: String) {
        CacheManager.delete(key)
    }
}
