package io.legado.app.ui.main.bookshelf

import android.app.Application
import android.net.Uri
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
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.ShelfCleanupRules
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
import io.legado.app.utils.compress.ZipUtils
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
                // ⚠️ 后续每轮 merge 用的是上一轮的返回值：mergeInto 以内存对象为
                // 基底做字段裁决，链式传递才能让 group 并集、进度与身份逐轮累积，
                // 否则第二轮会拿原始 keeper 把第一轮的并集静默丢掉。
                var keeper = BookMergeRules.pickKeeper(alive, ::lastReadOf)
                // 章节取「最全的那份」：重复记录往往一本有目录、另一本目录为空或不完整，
                // 若一律用被并方的目录，会把保留项已有的完整目录覆盖成残缺的。
                val bestToc = alive
                    .map { appDb.bookChapterDao.getChapterList(it.bookUrl) }
                    .maxByOrNull { it.size }
                    .orEmpty()

                // 被并方全部并入：按 durChapterTime 升序逐本走显式 merge（keep 固定为
                // keeper，不重查重选）——最新动的书源最后写入，保留项最终拿到的是
                // 新书源；全部并完后实际并掉数量才与确认框口径一致。
                val donors = alive
                    .filter { it.bookUrl != keeper.bookUrl }
                    .sortedBy { it.durChapterTime }
                donors.forEach { donor ->
                    keeper = BookUpsert.merge(
                        keep = keeper,
                        src = donor,
                        toc = bestToc,
                        migrateFrom = keeper
                    )
                }
                mergedBooks += donors.size
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

    // ---- 按备份清理本机书籍（bug 2：删不掉、会回流） --------------------------

    /**
     * 扫描「本机存在、所选备份中没有」的书籍。
     *
     * 备份恢复是**增量合并**：A 设备删除书籍后备份，B 恢复时其本地未手动删除的书仍留存，
     * 且 B 再备份会把它们带回 A。此入口让用户用一份备份把本机多出来的书清理掉。
     *
     * ⚠️ 只读扫描，不删任何东西；删除由用户在确认后显式触发。
     * ⚠️ 基准是**用户显式选择的备份 zip**，不是恢复流程的临时目录（后者会被
     * `selectRestoreItems` 删掉未选中项，导致「备份里没有」与「用户选择不恢复」混淆）。
     */
    fun cleanupByBackup(
        uri: Uri,
        onScanned: (candidates: List<Book>, excludedCount: Int) -> Unit
    ) {
        execute {
            val backupBooks = withContext(Dispatchers.IO) {
                readBooksFromBackupZip(uri)
            }
            val localBooks = appDb.bookDao.all
            val candidates = ShelfCleanupRules.findLocalOnlyBooks(localBooks, backupBooks)
            val excluded = ShelfCleanupRules.countExcludedLocalBooks(localBooks)
            candidates to excluded
        }.onSuccess { (candidates, excluded) ->
            onScanned(candidates, excluded)
        }.onError {
            AppLog.put("按备份清理扫描出错\n${it.localizedMessage}", it)
            context.toastOnUi(context.getString(R.string.cleanup_by_backup_fail, it.localizedMessage))
        }
    }

    /** 从备份 zip 中读出 `bookshelf.json` 的书籍列表；读不到按空处理。 */
    private fun readBooksFromBackupZip(uri: Uri): List<Book> {
        val tempDir = File(context.cacheDir, "cleanup_backup")
        try {
            FileUtils.delete(tempDir)
            // 只解压 bookshelf.json：备份 zip 可能含数十 MB 的背景/封面/缓存，
            // 全量解压既慢又没必要（本入口只需要书籍身份）。
            zipUtilsUnzip(uri, tempDir)
            val shelfFile = File(tempDir, "bookshelf.json")
            if (!shelfFile.exists()) {
                return emptyList()
            }
            return shelfFile.inputStream().use { input ->
                GSON.fromJsonArray<Book>(input).getOrNull().orEmpty()
            }
        } finally {
            FileUtils.delete(tempDir)
        }
    }

    private fun zipUtilsUnzip(uri: Uri, targetDir: File) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipUtils.unZipToPath(input, targetDir) { name ->
                name == "bookshelf.json" || name.endsWith("/bookshelf.json")
            }
        }
    }

    /**
     * 删除用户勾选的书籍。
     *
     * ⚠️ 删除前先把「将被删项」清单落到备份目录：书源/书籍删除不可撤销，
     * 且这两张表**不在** `RestoreJournal` 的快照范围内（快照从不登记 `legado.db`），
     * 落一份清单能把「完全不可逆」降级为「可手工找回」（AGENTS.md 拒绝静默数据操作）。
     *
     * 走 [Book.delete]（已有）：它负责清阅读引擎状态、本地书资源，并级联删除
     * chapters / book_illustrations / book_shortcuts / book_collection_items。
     * 实测该级联**不触及** `book_sources` 与 `caches`，故不影响书源与登录态。
     */
    fun deleteBooksByCleanup(
        books: List<Book>,
        onDone: (deleted: Int, manifestPath: String?) -> Unit
    ) {
        execute {
            val manifestPath = withContext(Dispatchers.IO) {
                writeCleanupManifest(books)
            }
            books.forEach { book ->
                appDb.bookDao.getBook(book.bookUrl)?.delete()
            }
            books.size to manifestPath
        }.onSuccess { (deleted, manifestPath) ->
            onDone(deleted, manifestPath)
        }.onError {
            AppLog.put("按备份清理删除出错\n${it.localizedMessage}", it)
            context.toastOnUi(context.getString(R.string.cleanup_by_backup_fail, it.localizedMessage))
        }
    }

    /** 落一份将被删书籍的清单（含可还原书籍本体的字段），返回路径；失败不阻断删除但会记日志。 */
    private fun writeCleanupManifest(books: List<Book>): String? {
        return runCatching {
            val dir = File(Backup.backupPath).parentFile ?: context.filesDir
            dir.mkdirs()
            val file = File(dir, "deleted-books-${System.currentTimeMillis()}.json")
            file.writeText(GSON.toJson(books))
            file.absolutePath
        }.onFailure {
            AppLog.put("导出删除清单失败\n${it.localizedMessage}", it)
        }.getOrNull()
    }

}
