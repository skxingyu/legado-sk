package io.legado.app.help

import io.legado.app.data.entities.BookSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 出厂内置书源播种判重。
 *
 * 契约：按 `bookSourceUrl` 跳过库中已存在的书源，绝不覆盖用户自建或改过的同名书源；
 * 只有真正缺失的候选才写入。
 */
class DefaultDataSeedTest {

    private fun source(url: String) = BookSource(bookSourceUrl = url, bookSourceName = url)

    @Test
    fun skipsExistingSourceByUrl() {
        val candidates = listOf(source("https://a.com/"), source("https://b.com/"))
        val seeded = DefaultData.builtinBookSourcesToSeed(candidates) { it == "https://a.com/" }
        assertEquals(listOf("https://b.com/"), seeded.map { it.bookSourceUrl })
    }

    @Test
    fun seedsAllWhenNothingExists() {
        val candidates = listOf(source("https://a.com/"), source("https://b.com/"))
        val seeded = DefaultData.builtinBookSourcesToSeed(candidates) { false }
        assertEquals(2, seeded.size)
    }

    @Test
    fun seedsNothingWhenAllExist() {
        val candidates = listOf(source("https://a.com/"))
        val seeded = DefaultData.builtinBookSourcesToSeed(candidates) { true }
        assertTrue(seeded.isEmpty())
    }
}
