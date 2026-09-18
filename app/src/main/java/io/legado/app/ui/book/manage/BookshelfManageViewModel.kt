package io.legado.app.ui.book.manage

import android.app.Application
import androidx.lifecycle.MutableLiveData
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.ai.AiChapterPurifyService
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.BookMergeRules
import io.legado.app.help.book.BookUpsert
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.removeType
import io.legado.app.help.book.BookShortcutHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.writeToOutputStream
import kotlinx.coroutines.delay
import java.io.File


class BookshelfManageViewModel(application: Application) : BaseViewModel(application) {
    var groupId: Long = -1L
    var groupName: String? = null
    val batchChangeSourceState = MutableLiveData<Boolean>()
    val batchChangeSourceProcessLiveData = MutableLiveData<String>()
    var batchChangeSourceCoroutine: Coroutine<Unit>? = null

    fun upCanUpdate(books: List<Book>, canUpdate: Boolean) {
        execute {
            val bodyBooks = books.map { appDb.bookDao.getBook(it.bookUrl) ?: it }
                .distinctBy { it.bookUrl }
                .map {
                    it.copy(canUpdate = canUpdate).apply {
                    if (!canUpdate) {
                        removeType(BookType.updateError)
                    }
                }
            }
            appDb.bookDao.update(*bodyBooks.toTypedArray())
        }
    }

    fun updateBook(vararg book: Book) {
        execute {
            BookShortcutHelp.update(*book)
        }
    }

    fun deleteBook(
        books: List<Book>,
        deleteBody: Boolean = false,
        deleteOriginal: Boolean = false
    ) {
        execute {
            BookShortcutHelp.delete(books, deleteBody, deleteOriginal)
        }
    }

    fun saveAllUseBookSourceToFile(success: (file: File) -> Unit) {
        execute {
            val path = "${context.filesDir}/shareBookSource.json"
            FileUtils.delete(path)
            val file = FileUtils.createFileWithReplace(path)
            val sources = appDb.bookDao.getAllUseBookSource()
            file.outputStream().buffered().use {
                GSON.writeToOutputStream(it, sources)
            }
            file
        }.onSuccess {
            success.invoke(it)
        }.onError {
            context.toastOnUi(it.stackTraceStr)
        }
    }

    fun changeSource(books: List<Book>, source: BookSource) {
        batchChangeSourceCoroutine?.cancel()
        batchChangeSourceCoroutine = execute {
            val changeSourceDelay = AppConfig.batchChangeSourceDelay * 1000L
            // ⚠️ 必须按身份去重：选中项里可能同时存在互为重复的两本（同书不同源）。
            // 否则第二本会再次以第一本的合并结果为 keep 合并一次，用 src 的进度把
            // 刚写好的进度静默覆盖，并且白做一轮删/写章节。
            val bodyBooks = books.map { appDb.bookDao.getBook(it.bookUrl) ?: it }
                .distinctBy { it.bookUrl }
                .distinctBy { BookMergeRules.identityKeyOf(it) ?: it.bookUrl }
            bodyBooks.forEachIndexed { index, book ->
                batchChangeSourceProcessLiveData.postValue("${index + 1} / ${bodyBooks.size}")
                if (book.isLocal) return@forEachIndexed
                if (book.origin == source.bookSourceUrl) return@forEachIndexed
                val newBook = WebBook.preciseSearchAwait(source, book.name, book.author)
                    .onFailure {
                        AppLog.put("搜索书籍出错\n${it.localizedMessage}", it, true)
                    }.getOrNull() ?: return@forEachIndexed
                kotlin.runCatching {
                    if (newBook.tocUrl.isEmpty()) {
                        WebBook.getBookInfoAwait(source, newBook)
                    }
                }.onFailure {
                    AppLog.put("获取书籍详情出错\n${it.localizedMessage}", it, true)
                    return@forEachIndexed
                }
                WebBook.getChapterListAwait(source, newBook)
                    .onFailure {
                        AppLog.put("获取目录出错\n${it.localizedMessage}", it, true)
                    }.getOrNull()?.let { toc ->
                        book.migrateTo(newBook, toc)
                        newBook.removeType(BookType.updateError)
                        // 统一入库：命同书时合并进既有记录，不再无脑插新（旧版本刻意保留旧书
                        // 导致批量换源必然产生重复）。
                        BookUpsert.upsertByIdentity(newBook, toc, migrateFrom = book)
                    }
                delay(changeSourceDelay)
            }
        }.onStart {
            batchChangeSourceState.postValue(true)
        }.onFinally {
            batchChangeSourceState.postValue(false)
        }
    }

    fun clearCache(books: List<Book>) {
        execute {
            books.forEach {
                BookHelp.clearCache(it)
                // 清除全书缓存：净化记录一并清空，重新出现的章节缓存按常规判定重跑
                AiChapterPurifyService.dropBookRecords(it)
            }
        }.onSuccess {
            context.toastOnUi(R.string.clear_cache_success)
        }
    }

}
