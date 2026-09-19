package io.legado.app.ui.book.info

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.script.rhino.runScriptWithContext
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookMediaType
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoBooksDirException
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.ai.AiChapterPurifyService
import io.legado.app.help.AppWebDav
import io.legado.app.help.book.addType
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.BookUpsert
import io.legado.app.help.book.CacheManifestHelper
import io.legado.app.help.book.getExportFileName
import io.legado.app.help.book.getRemoteUrl
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.book.isSameNameAuthor
import io.legado.app.help.book.isVideo
import io.legado.app.help.book.isWebFile
import io.legado.app.help.book.removeType
import io.legado.app.help.book.updateTo
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.lib.webdav.ObjectNotFoundException
import io.legado.app.model.BookCover
import io.legado.app.model.ReadBook
import io.legado.app.model.ReadManga
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.model.SourceCallBack
import io.legado.app.ui.login.SourceLoginJsExtensions
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.UrlUtil
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import java.io.FileNotFoundException

class BookInfoViewModel(application: Application) : BaseViewModel(application) {
    val bookData = MutableLiveData<Book>()
    val chapterListData = MutableLiveData<List<BookChapter>>()
    val bookInfoLoadingData = MutableLiveData<Boolean>()
    val chapterLoadingData = MutableLiveData<Boolean>()
    val webFiles = mutableListOf<WebFile>()
    var inBookshelf = false
    var hasCustomBtn = false
    var bookSource: BookSource? = null
    private var changeSourceCoroutine: Coroutine<*>? = null
    val waitDialogData = MutableLiveData<Boolean>()
    val actionLive = MutableLiveData<String>()

    private fun findBook(intent: Intent): Book? {
        val bookUrl = intent.getStringExtra("bookUrl").orEmpty()
        if (bookUrl.isNotBlank()) {
            appDb.bookDao.getBook(bookUrl)?.let { return it }
            appDb.searchBookDao.getSearchBook(bookUrl)?.toBook()?.let { return it }
        }
        val name = intent.getStringExtra("name").orEmpty()
        val author = intent.getStringExtra("author").orEmpty()
        if (name.isBlank()) return null
        val searchBooks = appDb.searchBookDao.getByNameAuthor(name, author)
        if (intent.hasExtra(BookMediaType.EXTRA_MEDIA_TYPE)) {
            val mediaType = intent.getIntExtra(
                BookMediaType.EXTRA_MEDIA_TYPE,
                BookMediaType.text
            )
            appDb.bookDao.getBook(name, author, mediaType)?.let { return it }
            return searchBooks
                .firstOrNull { BookMediaType.fromBookType(it.type) == mediaType }
                ?.toBook()
        }
        val storedBooks = appDb.bookDao.getBooks(name, author)
        if (storedBooks.size > 1) {
            throw NoStackTraceException("同名书包含多个媒体类别，必须提供 bookUrl")
        }
        storedBooks.singleOrNull()?.let { return it }
        val searchMediaTypes = searchBooks.map {
            BookMediaType.fromBookType(it.type)
        }.distinct()
        if (searchMediaTypes.size > 1) {
            throw NoStackTraceException("同名搜索结果包含多个媒体类别，必须提供 bookUrl")
        }
        return searchBooks.firstOrNull()?.toBook()
    }

    fun initData(intent: Intent) {
        execute {
            val name = intent.getStringExtra("name") ?: ""
            val author = intent.getStringExtra("author") ?: ""
            val bookUrl = intent.getStringExtra("bookUrl") ?: ""
            val origin = intent.getStringExtra("origin") ?: ""
            val originName = intent.getStringExtra("originName") ?: ""
            findBook(intent)?.let {
                inBookshelf = appDb.bookDao.has(it.bookUrl) && !it.isNotShelf
                upBook(it)
                return@execute
            }
            if (bookUrl.isNotBlank() && origin.isNotBlank()) {
                upBook(
                    Book(
                        name = name,
                        author = author,
                        bookUrl = bookUrl,
                        origin = origin,
                        originName = originName,
                        type = BookMediaType.toBookType(
                            intent.getIntExtra(
                                BookMediaType.EXTRA_MEDIA_TYPE,
                                BookMediaType.text
                            )
                        )
                    )
                )
                return@execute
            }
            throw NoStackTraceException("未找到书籍")
        }.onError {
            AppLog.put(it.localizedMessage, it)
            context.toastOnUi(it.localizedMessage)
        }
    }

    fun upBook(intent: Intent) {
        execute {
            findBook(intent)?.let { book ->
                upBook(book)
            }
        }
    }

    private fun upBook(book: Book) {
        execute {
            bookSource = if (book.isLocal) null else
                appDb.bookSourceDao.getBookSource(book.origin)?.also {
                    hasCustomBtn = it.customButton
                }
            syncBookSourceName(book)
            bookData.postValue(book)
            upCoverByRule(book)
            if (book.tocUrl.isEmpty() && !book.isLocal) {
                loadBookInfo(book, runPreUpdateJs = inBookshelf)
            } else {
                val chapterList = appDb.bookChapterDao.getChapterList(book.bookUrl)
                if (chapterList.isNotEmpty()) {
                    chapterListData.postValue(chapterList)
                } else {
                    loadChapter(book, isFromBookInfo = true)
                }
            }
        }
    }

    private fun upCoverByRule(book: Book) {
        execute {
            if (book.coverUrl.isNullOrBlank() && book.customCoverUrl.isNullOrBlank()) {
                val coverUrl = BookCover.searchCover(book)
                if (coverUrl.isNullOrBlank()) {
                    return@execute
                }
                book.customCoverUrl = coverUrl
                bookData.postValue(book)
                if (inBookshelf) {
                    saveBook(book)
                }
            }
        }
    }

    fun refreshBook(book: Book) {
        executeLazy(executeContext = IO) {
            if (book.isLocal) {
                book.tocUrl = ""
                book.getRemoteUrl()?.let {
                    val bookWebDav = AppWebDav.defaultBookWebDav
                        ?: throw NoStackTraceException("webDav没有配置")
                    val remoteBook = bookWebDav.getRemoteBook(it)
                    if (remoteBook == null) {
                        book.origin = BookType.localTag
                    } else if (remoteBook.lastModify > book.lastCheckTime) {
                        val uri = bookWebDav.downloadRemoteBook(remoteBook)
                        book.bookUrl = if (uri.isContentScheme()) uri.toString() else uri.path!!
                        book.lastCheckTime = remoteBook.lastModify
                    }
                }
            } else {
                if (syncBookSourceName(book)) {
                    bookData.postValue(book)
                }
            }
        }.onError {
            when (it) {
                is ObjectNotFoundException -> {
                    book.origin = BookType.localTag
                }

                else -> {
                    AppLog.put("下载远程书籍<${book.name}>失败", it)
                }
            }
        }.onFinally {
            loadBookInfo(book, false)
        }.start()
    }

    private fun syncBookSourceName(book: Book): Boolean {
        if (book.isLocal) return false
        val sourceName = bookSource?.bookSourceName ?: return false
        if (book.originName == sourceName) return false
        book.originName = sourceName
        if (inBookshelf) {
            appDb.bookDao.update(book)
        }
        return true
    }

    fun loadBookInfo(
        book: Book,
        canReName: Boolean = true,
        runPreUpdateJs: Boolean = true,
        scope: CoroutineScope = viewModelScope
    ) {
        bookInfoLoadingData.postValue(true)
        if (book.isLocal) {
            try {
                LocalBook.upBookInfo(book)
                bookData.postValue(book)
                loadChapter(book)
            } finally {
                bookInfoLoadingData.postValue(false)
            }
        } else {
            val bookSource = bookSource ?: let {
                bookInfoLoadingData.postValue(false)
                chapterListData.postValue(emptyList())
                context.toastOnUi(R.string.error_no_source)
                return
            }
            WebBook.getBookInfo(scope, bookSource, book, canReName = canReName)
                .onSuccess(IO) {
                    val dbBook = appDb.bookDao.getBook(
                        book.name,
                        book.author,
                        BookMediaType.fromBookType(book.type)
                    )
                    if (!inBookshelf && dbBook != null && !dbBook.isNotShelf && dbBook.origin == book.origin) {
                        /**
                         * book 来自搜索时(inBookshelf == false)，搜索的书名不存在于书架，但是加载详情后，书名更新，存在同名书籍
                         * 此时 book 的数据会与数据库中的不同，需要更新 #3652 #4619
                         * book 加载详情后虽然书名作者相同，但是又可能不是数据库中(书源不同)的那本书 #3149
                         */
                        dbBook.updateTo(it)
                        inBookshelf = true
                    }
                    bookData.postValue(it)
                    if (inBookshelf) {
                        it.save()
                    }
                    if (it.isWebFile) {
                        loadWebFile(it)
                    } else {
                        loadChapter(it, runPreUpdateJs, isFromBookInfo = true)
                    }
                }.onError {
                    AppLog.put("获取书籍信息失败\n${it.localizedMessage}", it)
                    context.toastOnUi(R.string.error_get_book_info)
                }.onFinally {
                    bookInfoLoadingData.postValue(false)
                }
        }
    }

    fun loadChapter(
        book: Book,
        runPreUpdateJs: Boolean = true,
        scope: CoroutineScope = viewModelScope,
        isFromBookInfo: Boolean = false
    ) {
        chapterLoadingData.postValue(true)
        if (book.isLocal) {
            execute(scope) {
                LocalBook.getChapterList(book).let {
                    appDb.bookDao.update(book)
                    appDb.bookChapterDao.delByBook(book.bookUrl)
                    appDb.bookChapterDao.insert(*it.toTypedArray())
                    CacheManifestHelper.refreshAsync(book, it)
                    ReadBook.onChapterListUpdated(book)
                    bookData.postValue(book)
                    chapterListData.postValue(it)
                }
            }.onError {
                when (it) {
                    is SecurityException, is FileNotFoundException -> {
                        actionLive.postValue("selectLocalBookDir")
                    }

                    else -> {
                        context.toastOnUi("LoadTocError:${it.localizedMessage}")
                    }
                }
            }.onFinally {
                chapterLoadingData.postValue(false)
            }
        } else {
            val bookSource = bookSource ?: let {
                chapterLoadingData.postValue(false)
                chapterListData.postValue(emptyList())
                context.toastOnUi(R.string.error_no_source)
                return
            }
            val oldBook = book.copy()
            WebBook.getChapterList(scope, bookSource, book, runPreUpdateJs, isFromBookInfo = isFromBookInfo)
                .onSuccess(IO) {
                    if (inBookshelf) {
                        val oldChapterList = appDb.bookChapterDao.getChapterList(oldBook.bookUrl)
                        BookHelp.remapContentCache(oldBook, oldChapterList, it)
                        book.removeType(BookType.updateError)
                        // 同 bookUrl 用 update：replace 是 delete+insert，会触发子表 CASCADE
                        if (oldBook.bookUrl == book.bookUrl) {
                            appDb.bookDao.update(book)
                        } else {
                            appDb.bookDao.replace(oldBook, book)
                            /**
                             * runPreUpdateJs 有可能会修改 book 的 bookUrl
                             */
                            BookHelp.updateCacheFolder(oldBook, book)
                        }
                        appDb.bookChapterDao.delByBook(oldBook.bookUrl)
                        appDb.bookChapterDao.insert(*it.toTypedArray())
                        CacheManifestHelper.refreshAsync(book, it)
                        ReadBook.onChapterListUpdated(book)
                    }
                    bookData.postValue(book)
                    chapterListData.postValue(it)
                }.onError {
                    chapterListData.postValue(emptyList())
                    AppLog.put("获取目录失败\n${it.localizedMessage}", it)
                    context.toastOnUi(R.string.error_get_chapter_list)
                }.onFinally {
                    chapterLoadingData.postValue(false)
                }
        }
    }


    fun loadGroup(groupId: Long, success: ((groupNames: String?) -> Unit)) {
        execute {
            appDb.bookGroupDao.getGroupNames(groupId).joinToString(",")
        }.onSuccess {
            success.invoke(it)
        }
    }

    private fun loadWebFile(book: Book) {
        execute {
            webFiles.clear()
            val fileNameNoExtension = if (book.author.isBlank()) book.name
            else "${book.name} 作者：${book.author}"
            book.downloadUrls!!.map {
                val analyzeUrl = AnalyzeUrl(
                    it, source = bookSource,
                    coroutineContext = coroutineContext
                )
                var mFileName = UrlUtil.getFileName(analyzeUrl)
                    ?: fileNameNoExtension
                analyzeUrl.type?.let { suffix ->
                    mFileName += ".${suffix}"
                }
                WebFile(it, mFileName)
            }
        }.onError {
            context.toastOnUi("LoadWebFileError\n${it.localizedMessage}")
        }.onSuccess {
            webFiles.addAll(it)
            book.latestChapterTitle = "已下载"
            bookData.postValue(book)
            chapterListData.postValue(emptyList())
        }
    }

    /* 导入或者下载在线文件 */
    fun <T> importOrDownloadWebFile(webFile: WebFile, success: ((T) -> Unit)?) {
        bookSource ?: return
        execute {
            waitDialogData.postValue(true)
            if (webFile.isSupported) {
                val book = LocalBook.importFileOnLine(
                    webFile.url,
                    bookData.value!!.getExportFileName(webFile.suffix),
                    bookSource
                )
                changeToLocalBook(book)
            } else {
                LocalBook.saveBookFile(
                    webFile.url,
                    bookData.value!!.getExportFileName(webFile.suffix),
                    bookSource
                )
            }
        }.onSuccess {
            @Suppress("unchecked_cast")
            success?.invoke(it as T)
        }.onError {
            when (it) {
                is NoBooksDirException -> actionLive.postValue("selectBooksDir")
                else -> {
                    AppLog.put("ImportWebFileError\n${it.localizedMessage}", it)
                    context.toastOnUi("ImportWebFileError\n${it.localizedMessage}")
                    webFiles.remove(webFile)
                }
            }
        }.onFinally {
            waitDialogData.postValue(false)
        }
    }

    fun getArchiveFilesName(archiveFileUri: Uri, onSuccess: (List<String>) -> Unit) {
        execute {
            ArchiveUtils.getArchiveFilesName(archiveFileUri) {
                AppPattern.bookFileRegex.matches(it)
            }
        }.onError {
            AppLog.put("getArchiveEntriesName Error:\n${it.localizedMessage}", it)
            context.toastOnUi("getArchiveEntriesName Error:\n${it.localizedMessage}")
        }.onSuccess {
            onSuccess.invoke(it)
        }
    }

    fun importArchiveBook(
        archiveFileUri: Uri,
        archiveEntryName: String,
        success: ((Book) -> Unit)? = null
    ) {
        execute {
            val suffix = archiveEntryName.substringAfterLast(".")
            LocalBook.importArchiveFile(
                archiveFileUri,
                bookData.value!!.getExportFileName(suffix)
            ) {
                it.contains(archiveEntryName)
            }.first()
        }.onSuccess {
            val book = changeToLocalBook(it)
            success?.invoke(book)
        }.onError {
            AppLog.put("importArchiveBook Error:\n${it.localizedMessage}", it)
            context.toastOnUi("importArchiveBook Error:\n${it.localizedMessage}")
        }
    }

    fun changeTo(source: BookSource, book: Book, toc: List<BookChapter>) {
        changeSourceCoroutine?.cancel()
        changeSourceCoroutine = execute {
            bookSource = source.also {
                hasCustomBtn = it.customButton
            }
            val oldBook = bookData.value
            oldBook?.migrateTo(book, toc)
            if (book.isWebFile) {
                loadWebFile(book)
            }
            // 统一入库：库里已有同书就合并进它，避免「书架里已有这本、从别的源再进详情页」
            // 时新增一条。不在架的书同样要收敛 —— 它可能已在库中（例如刚被移出书架）。
            book.removeType(BookType.updateError)
            val settled = BookUpsert.upsertByIdentity(book, toc, migrateFrom = oldBook)
            CacheManifestHelper.refreshAsync(settled, toc)
            // 入库成功后这本书确实在架上了（合并进既有记录也算在架）。
            inBookshelf = true
            // ⚠️ 必须推 settled：合并时它的 bookUrl 是既有记录的，
            // 后续 readBook()/startReadActivity 都从这里取 bookUrl。
            bookData.postValue(settled)
            chapterListData.postValue(toc)
        }.onFinally {
            postEvent(EventBus.SOURCE_CHANGED, book.bookUrl)
        }
    }

    fun topBook() {
        execute {
            bookData.value?.let { book ->
                val minOrder = appDb.bookDao.minOrder
                book.order = minOrder - 1
                book.durChapterTime = System.currentTimeMillis()
                appDb.bookDao.update(book)
            }
        }
    }

    /**
     * 把详情页当前这本书落库（点「开始阅读」「目录」等都会走这里）。
     *
     * ⚠️ 必须走 [BookUpsert.upsertByIdentity] 而不是裸 `book.save()`：
     * `save()` 在 `bookUrl` 不在库中时直接 `insert`，会让「换源时刚被合并掉的记录」
     * 在点「开始阅读」这一步又被插回来 —— 需求目标会因此失效。
     */
    fun saveBook(book: Book?, success: (() -> Unit)? = null) {
        book ?: return
        execute {
            if (book.order == 0) {
                book.order = appDb.bookDao.minOrder - 1
            }
            val settled = BookUpsert.upsertByIdentity(book)
            if (settled.bookUrl != book.bookUrl) {
                // 入库时命中了既有记录（同书不同源）：把当前页与引擎切到真正落库的那一本，
                // 否则后续 startReadActivity 会拿已不存在的 bookUrl 去查库。
                bookData.postValue(settled)
            }
            if (ReadBook.book?.isSameNameAuthor(settled) == true) {
                ReadBook.book = settled
            }
        }.onSuccess {
            success?.invoke()
        }
    }

    fun saveChapterList(success: (() -> Unit)?) {
        execute {
            chapterListData.value?.let {
                appDb.bookChapterDao.insert(*it.toTypedArray())
                bookData.value?.let { book -> CacheManifestHelper.refreshAsync(book, it) }
            }
        }.onSuccess {
            success?.invoke()
        }
    }

    fun saveBookAtChapter(book: Book, chapter: BookChapter, success: (() -> Unit)?) {
        execute {
            book.durChapterIndex = chapter.index
            book.durChapterPos = 0
            book.durChapterTitle = chapter.title
            if (!inBookshelf) {
                // 「试读不入架」：刻意打上 notShelf 标记后按 bookUrl 落库。
                // 这类记录不参与身份收敛（BookMergeRules 明确排除 notShelf），
                // 因此这里不走统一入口 —— 否则试读会被并进书架里的正式记录。
                book.addType(BookType.notShelf)
                if (book.order == 0) {
                    book.order = appDb.bookDao.minOrder - 1
                }
                book.save()
            } else {
                appDb.bookDao.update(book)
            }
            chapterListData.value?.let {
                appDb.bookChapterDao.insert(*it.toTypedArray())
                CacheManifestHelper.refreshAsync(book, it)
            }
        }.onSuccess {
            success?.invoke()
        }
    }

    fun addToBookshelf(success: (() -> Unit)?) { //点击书架按钮或在加分组时触发
        execute {
            bookData.value?.let { current ->
                val book = current.copy().apply { removeType(BookType.notShelf) }
                if (book.order == 0) {
                    book.order = appDb.bookDao.minOrder - 1
                }
                // 统一入库：库里已有同书时合并进既有记录（保留其 bookUrl 与阅读进度），
                // 不再按 bookUrl 无脑插新 —— 否则同书不同源会在书架上出现第二条。
                val settled = BookUpsert.upsertByIdentity(book)
                if (ReadBook.book?.isSameNameAuthor(settled) == true) {
                    ReadBook.book = settled
                }
                if (settled.bookUrl != current.bookUrl) {
                    bookData.postValue(settled)
                }
                SourceCallBack.callBackBook(SourceCallBack.ADD_BOOK_SHELF, bookSource, settled)
            }
            chapterListData.value?.let {
                appDb.bookChapterDao.insert(*it.toTypedArray())
                bookData.value?.let { book -> CacheManifestHelper.refreshAsync(book, it) }
            }
            inBookshelf = true
        }.onSuccess {
            success?.invoke()
        }
    }

    fun getBook(toastNull: Boolean = true): Book? {
        val book = bookData.value
        if (toastNull && book == null) {
            context.toastOnUi("book is null")
        }
        return book
    }

    fun delBook(
        deleteOriginal: Boolean = false,
        deleteCache: Boolean = false,
        success: (() -> Unit)? = null
    ) {
        execute {
            bookData.value?.let {
                if (deleteCache) {
                    clearBookCache(it)
                }
                it.delete()
                inBookshelf = false
                if (it.isLocal) {
                    LocalBook.deleteBook(it, deleteOriginal)
                }
            }
        }.onSuccess {
            success?.invoke()
        }
    }

    private fun clearBookCache(book: Book) {
        if (book.isAudio || book.isVideo) {
            appDb.bookChapterDao.getChapterList(book.bookUrl)
                .forEach {
                    if (book.isVideo) {
                        ExoPlayerHelper.removeVideoCache(it.resourceUrl, book)
                    } else {
                        ExoPlayerHelper.removeMediaCache(it.resourceUrl, book)
                    }
                }
        }
        ExoPlayerHelper.releaseBookCaches(book)
        BookHelp.clearCache(book)
        // 清除全书缓存：全书净化记录一并清空，重新出现的章节缓存按常规判定重跑
        AiChapterPurifyService.dropBookRecords(book)
        if (ReadBook.book?.bookUrl == book.bookUrl) {
            ReadBook.clearTextChapter()
        }
        if (ReadManga.book?.bookUrl == book.bookUrl) {
            ReadManga.clearMangaChapter()
        }
    }

    fun clearCache(book: Book) {
        execute {
            clearBookCache(book)
        }.onSuccess {
            context.toastOnUi(R.string.clear_cache_success)
        }.onError {
            context.toastOnUi("清理缓存出错\n${it.localizedMessage}")
        }
    }

    fun upEditBook() {
        bookData.value?.let {
            appDb.bookDao.getBook(it.bookUrl)?.let { book ->
                bookData.postValue(book)
            }
        }
    }

    private fun changeToLocalBook(localBook: Book): Book {
        return LocalBook.mergeBook(localBook, bookData.value).let {
            bookData.postValue(it)
            loadChapter(it)
            inBookshelf = true
            it
        }
    }

    fun onButtonClick(activity: AppCompatActivity, name: String, click: String) {
        val source = bookSource ?: return
        val book = bookData.value ?: return
        execute {
            val java = SourceLoginJsExtensions(activity, source)
            runScriptWithContext {
                source.evalJS(click) {
                    put("result", null)
                    put("java", java)
                    put("book", book)
                }
            }
        }.onError {
            AppLog.put("${source.bookSourceName}: ${it.localizedMessage}", it)
            context.toastOnUi("$name click error\n${it.localizedMessage}")
        }
    }

    data class WebFile(
        val url: String,
        val name: String,
    ) {

        override fun toString(): String {
            return name
        }

        // 后缀
        val suffix: String = UrlUtil.getSuffix(name)

        // txt epub umd pdf等文件
        val isSupported: Boolean = AppPattern.bookFileRegex.matches(name)

        // 压缩包形式的txt epub umd pdf文件
        val isSupportDecompress: Boolean = AppPattern.archiveFileRegex.matches(name)

    }

}
