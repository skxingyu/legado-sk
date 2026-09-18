package io.legado.app.ui.main.bookshelf

import android.app.Application
import androidx.lifecycle.MutableLiveData
import com.google.gson.stream.JsonWriter
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.BookMergeRules
import io.legado.app.help.book.BookUpsert
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.decompressed
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

class BookshelfViewModel(application: Application) : BaseViewModel(application) {
    val addBookProgressLiveData = MutableLiveData(-1)
    var addBookJob: Coroutine<*>? = null

    fun addBookByUrl(bookUrls: String) {
        var successCount = 0
        addBookJob = execute {
            val hasBookUrlPattern: List<BookSourcePart> by lazy {
                appDb.bookSourceDao.hasBookUrlPattern
            }
            val urls = bookUrls.split("\n")
            for (url in urls) {
                val bookUrl = url.trim()
                if (bookUrl.isEmpty()) continue
                if (appDb.bookDao.getBook(bookUrl) != null) {
                    successCount++
                    continue
                }
                val baseUrl = NetworkUtils.getBaseUrl(bookUrl) ?: continue
                var source: BookSource? = null
                val urlMatcher = AnalyzeUrl.paramPattern.matcher(bookUrl)
                if (urlMatcher.find()) { //指定书源
                    val origin = GSON.fromJsonObject<AnalyzeUrl.UrlOption>(
                        bookUrl.substring(urlMatcher.end())
                    ).getOrNull()?.getOrigin()
                    try {
                        origin?.let {
                            appDb.bookSourceDao.getBookSource(it)?.let { bs ->
                                if (bookUrl.matches(bs.bookUrlPattern!!.toRegex())) {
                                    source = bs
                                }
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
                if (source == null) { //根据域名找书源
                    source = appDb.bookSourceDao.getBookSourceAddBook(baseUrl)
                }
                if (source == null) {
                    for (bookSource in hasBookUrlPattern) { //在所有启用的书源中查找
                        try {
                            val bs = bookSource.getBookSource()!!
                            if (bookUrl.matches(bs.bookUrlPattern!!.toRegex())) {
                                source = bs
                                break
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
                val bookSource = source ?: continue
                val book = Book(
                    bookUrl = bookUrl,
                    origin = bookSource.bookSourceUrl,
                    originName = bookSource.bookSourceName
                )
                kotlin.runCatching {
                    WebBook.getBookInfoAwait(bookSource, book)
                }.onSuccess {
                    if (appDb.bookDao.getBook(it.bookUrl) != null) {
                        successCount++
                        addBookProgressLiveData.postValue(successCount)
                        return@onSuccess
                    }
                    it.order = appDb.bookDao.minOrder - 1
                    it.save()
                    successCount++
                    addBookProgressLiveData.postValue(successCount)
                }
            }
        }.onSuccess {
            if (successCount > 0) {
                context.toastOnUi(R.string.success)
            } else {
                context.toastOnUi("添加网址失败")
            }
        }.onError {
            AppLog.put("添加网址出错\n${it.localizedMessage}", it, true)
        }.onFinally {
            addBookProgressLiveData.postValue(-1)
        }
    }

    fun exportBookshelf(books: List<Book>?, success: (file: File) -> Unit) {
        execute {
            books?.let {
                val path = "${context.filesDir}/books.json"
                FileUtils.delete(path)
                val file = FileUtils.createFileWithReplace(path)
                FileOutputStream(file).use { out ->
                    val writer = JsonWriter(OutputStreamWriter(out, "UTF-8"))
                    writer.setIndent("  ")
                    writer.beginArray()
                    books.forEach {
                        val bookMap = hashMapOf<String, String?>()
                        bookMap["name"] = it.name
                        bookMap["author"] = it.author
                        bookMap["intro"] = it.getDisplayIntro()
                        GSON.toJson(bookMap, bookMap::class.java, writer)
                    }
                    writer.endArray()
                    writer.close()
                }
                file
            } ?: throw NoStackTraceException("书籍不能为空")
        }.onSuccess {
            success(it)
        }.onError {
            context.toastOnUi("导出书籍出错\n${it.localizedMessage}")
        }
    }

    fun importBookshelf(str: String, groupId: Long) {
        execute {
            val text = str.trim()
            when {
                text.isAbsUrl() -> {
                    okHttpClient.newCallResponseBody {
                        url(text)
                    }.decompressed().text().let {
                        importBookshelf(it, groupId)
                    }
                }

                text.isJsonArray() -> {
                    importBookshelfByJson(text, groupId)
                }

                else -> {
                    throw NoStackTraceException("格式不对")
                }
            }
        }.onError {
            context.toastOnUi(it.localizedMessage ?: "ERROR")
        }
    }

    private fun importBookshelfByJson(json: String, groupId: Long) {
        execute {
            val bookSourceParts = appDb.bookSourceDao.allEnabledPart
            val semaphore = Semaphore(AppConfig.threadCount)
            GSON.fromJsonArray<Map<String, String?>>(json).getOrThrow().forEach { bookInfo ->
                val name = bookInfo["name"] ?: ""
                val author = bookInfo["author"] ?: ""
                if (name.isEmpty()) {
                    return@forEach
                }
                semaphore.withPermit {
                    WebBook.preciseSearch(
                        this, bookSourceParts, name, author,
                        semaphore = semaphore
                    ).onSuccess {
                        val book = it.first
                        if (appDb.bookDao.has(book.bookUrl)) {
                            return@onSuccess
                        }
                        if (groupId > 0) {
                            book.group = groupId
                        }
                        book.save()
                    }.onError { e ->
                        context.toastOnUi(e.localizedMessage)
                    }
                }
            }
        }.onFinally {
            context.toastOnUi(R.string.success)
        }
    }

    // ---- 书架「去重」：把同书不同源的重复记录合并为一条 ------------------------

    val mergeDuplicatesState = MutableLiveData<Boolean>()
    val mergeDuplicatesProgress = MutableLiveData<String>()
    var mergeDuplicatesJob: Coroutine<*>? = null

    /**
     * 扫描全库，找出可以合并的重复组。
     *
     * 判据与合并规则见 [BookMergeRules]（书名 + 作者 + 媒体类型；本地书/未入架书不参与）。
     * 扫描是只读的，供交互第一步「先扫描再确认」使用 —— 没有重复时不弹确认框。
     */
    suspend fun findDuplicateGroups(): List<List<Book>> = withContext(Dispatchers.IO) {
        BookMergeRules.duplicateGroups(appDb.bookDao.all)
    }

    /**
     * 执行合并：每组保留一本，其余并入。
     *
     * 保留项按「最近打开阅读」判定（`readRecentBooks.lastRead`），
     * 无阅读记录时回退 `durChapterTime` —— 见 [BookMergeRules.pickKeeper]。
     */
    fun mergeDuplicates(
        groups: List<List<Book>>,
        onDone: (mergedGroupCount: Int, mergedBookCount: Int) -> Unit
    ) {
        if (groups.isEmpty()) return
        mergeDuplicatesJob?.cancel()
        mergeDuplicatesJob = execute {
            var mergedGroups = 0
            var mergedBooks = 0
            groups.forEachIndexed { index, group ->
                mergeDuplicatesProgress.postValue("${index + 1} / ${groups.size}")
                // 每轮重查：本组可能已被前一组处理掉（组间不会重叠，但重查是幂等的保险）。
                val alive = group.mapNotNull { appDb.bookDao.getBook(it.bookUrl) }
                    .filter { BookMergeRules.identityKeyOf(it) != null }
                if (alive.size < 2) return@forEachIndexed

                // 保留项：最近阅读的那本（无阅读记录时回退 durChapterTime）。
                val keeper = BookMergeRules.pickKeeper(alive, ::lastReadOf)
                // 章节取「最全的那份」：重复记录往往一本有目录、另一本目录为空或不完整，
                // 若一律用被并方的目录，会把保留项已有的完整目录覆盖成残缺的。
                val bestToc = alive
                    .map { appDb.bookChapterDao.getChapterList(it.bookUrl) }
                    .maxByOrNull { it.size }
                    .orEmpty()

                // 被并方以「除保留项外的最新一本」为准：把它的书源身份写进保留项。
                // 这样合并后保留项拿到的是新书源，而不是随便挑一本旧源的。
                val donor = alive.filter { it.bookUrl != keeper.bookUrl }
                    .maxByOrNull { it.durChapterTime } ?: return@forEachIndexed

                BookUpsert.upsertByIdentity(
                    incoming = donor,
                    toc = bestToc,
                    migrateFrom = keeper
                )
                mergedBooks += alive.size - 1
                mergedGroups++
            }
            mergedGroups to mergedBooks
        }.onSuccess { (groupCount, bookCount) ->
            onDone(groupCount, bookCount)
        }.onError {
            AppLog.put("合并重复书籍出错\n${it.localizedMessage}", it)
            context.toastOnUi(context.getString(R.string.merge_duplicates_failed, it.localizedMessage))
        }.onFinally {
            mergeDuplicatesState.postValue(false)
        }
        mergeDuplicatesState.postValue(true)
    }

    private fun lastReadOf(bookUrl: String): Long? =
        appDb.readRecentBookDao.getByBookUrl(bookUrl)?.lastRead

}
