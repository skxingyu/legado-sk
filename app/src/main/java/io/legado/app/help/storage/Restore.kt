package io.legado.app.help.storage

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.R
import io.legado.app.constant.AppConst.androidId
import io.legado.app.constant.EventBus
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookIllustration
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.DictRule
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.KeyboardAssist
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.ReadRecordDaily
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.RssStar
import io.legado.app.data.entities.RuleSub
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.data.entities.Server
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.LauncherIconHelp
import io.legado.app.help.book.BookMergeRules
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.upType
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.NavigationBarIconConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.config.ThemePackageManager
import io.legado.app.model.VideoPlay.VIDEO_PREF_NAME
import io.legado.app.model.BookCover
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.ACache
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.getSharedPreferences
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.openInputStream
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream

/**
 * 恢复
 */
object Restore {

    private val mutex = Mutex()

    private const val TAG = "Restore"

    internal val backgroundAssetDirNames = arrayOf(
        "bg",
        "font",
        "covers",
        "readRecordGoalAvatar",
        PreferKey.bgImage,
        PreferKey.bgImageN,
        PreferKey.bookInfoBgImage,
        PreferKey.bookInfoBgImageN
    )

    suspend fun restore(context: Context, uri: Uri) {
        LogUtils.d(TAG, "开始恢复备份 uri:$uri")
        kotlin.runCatching {
            FileUtils.delete(Backup.backupPath)
            if (uri.isContentScheme()) {
                DocumentFile.fromSingleUri(context, uri)!!.openInputStream()!!.use {
                    ZipUtils.unZipToPath(it, Backup.backupPath)
                }
            } else {
                ZipUtils.unZipToPath(File(uri.path!!), Backup.backupPath)
            }
        }.onFailure {
            AppLog.put("复制解压文件出错\n${it.localizedMessage}", it)
            return
        }
        kotlin.runCatching {
            restoreLocked(Backup.backupPath)
            LocalConfig.lastBackup = System.currentTimeMillis()
        }.onFailure {
            appCtx.toastOnUi("恢复备份出错\n${it.localizedMessage}")
            AppLog.put("恢复备份出错\n${it.localizedMessage}", it)
        }
    }

    suspend fun restoreLocked(path: String) {
        mutex.withLock {
            RestoreJournal.begin(RestoreJournal.buildSnapshotTargets(path))
            try {
                restore(path)
                RestoreJournal.markPendingValidation()
            } catch (e: Throwable) {
                RestoreJournal.rollbackNow("恢复过程异常: ${e.localizedMessage}")
                throw e
            }
        }
    }

    private suspend fun restore(path: String) {
        val aes = BackupAES()
        restoreDbData(path, aes)   // M1: DB 段（bookshelf→servers.json）原子恢复
        File(path, DirectLinkUpload.ruleFileName).takeIf {
            it.exists()
        }?.runCatching {
            val json = readText()
            ACache.get(cacheDir = false).put(DirectLinkUpload.ruleFileName, json)
        }?.onFailure {
            AppLog.put("恢复直链上传出错\n${it.localizedMessage}", it)
        }
        //恢复主题配置
        File(path, ThemeConfig.configFileName).takeIf {
            it.exists()
        }?.runCatching {
            FileUtils.delete(ThemeConfig.configFilePath)
            copyTo(File(ThemeConfig.configFilePath))
            ThemeConfig.upConfig()
        }?.onFailure {
            AppLog.put("恢复主题出错\n${it.localizedMessage}", it)
        }
        File(path, BookCover.configFileName).takeIf {
            it.exists()
        }?.runCatching {
            val json = readText()
            BookCover.saveCoverRule(json)
        }?.onFailure {
            AppLog.put("恢复封面规则出错\n${it.localizedMessage}", it)
        }
        var restoredReadConfig = false
        if (!BackupConfig.ignoreReadConfig) {
            //恢复阅读界面配置
            File(path, ReadBookConfig.configFileName).takeIf {
                it.exists()
            }?.runCatching {
                FileUtils.delete(ReadBookConfig.configFilePath)
                copyTo(File(ReadBookConfig.configFilePath))
                restoredReadConfig = true
            }?.onFailure {
                AppLog.put("恢复阅读界面出错\n${it.localizedMessage}", it)
            }
            File(path, ReadBookConfig.shareConfigFileName).takeIf {
                it.exists()
            }?.runCatching {
                FileUtils.delete(ReadBookConfig.shareConfigFilePath)
                copyTo(File(ReadBookConfig.shareConfigFilePath))
                restoredReadConfig = true
            }?.onFailure {
                AppLog.put("恢复阅读界面出错\n${it.localizedMessage}", it)
            }
        }
        restoreBackgroundAssets(path)
        repairLocalCoverPaths()
        normalizeReadRecordGoalAvatar()
        if (restoredReadConfig) {
            ReadBookConfig.initConfigs()
            ReadBookConfig.initShareConfig()
        }
        ReadBookConfig.repairConfigIfNeeded()
        restoreThemePackages(path)
        restoreNavigationIcons(path)
        //AppWebDav.downBgs()
        appCtx.getSharedPreferences(path, "config")?.all?.let { map ->
            val edit = appCtx.defaultSharedPreferences.edit()

            map.forEach { (key, value) ->
                if (BackupConfig.keyIsNotIgnore(key)) {
                    when (key) {
                        PreferKey.webDavPassword -> {
                            kotlin.runCatching {
                                aes.decryptStr(value.toString())
                            }.getOrNull()?.let {
                                edit.putString(key, it)
                            } ?: let {
                                if (appCtx.getPrefString(PreferKey.webDavPassword)
                                        .isNullOrBlank()
                                ) {
                                    edit.putString(key, value.toString())
                                }
                            }
                        }

                        else -> when (value) {
                            is Int -> edit.putInt(key, value)
                            is Boolean -> edit.putBoolean(key, value)
                            is Long -> edit.putLong(key, value)
                            is Float -> edit.putFloat(key, value)
                            is String -> edit.putString(key, value)
                        }
                    }
                }
            }
            edit.commit()
        }
        normalizeBackgroundPrefs()
        normalizeStringPrefs()
        kotlin.runCatching {
            ThemePackageManager.ensureLocalAppliedTheme(appCtx, false)
            ThemePackageManager.ensureLocalAppliedTheme(appCtx, true)
            ThemePackageManager.reapplyRestoredAppliedThemes(appCtx)
        }.onFailure {
            AppLog.put("恢复默认主题包出错\n${it.localizedMessage}", it)
        }
        appCtx.getSharedPreferences(path, "videoConfig")?.all?.let { map ->
            appCtx.getSharedPreferences(VIDEO_PREF_NAME, Context.MODE_PRIVATE).edit().apply {
                map.forEach { (key, value) ->
                    when (value) {
                        is Int -> putInt(key, value)
                        is Boolean -> putBoolean(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is String -> putString(key, value)
                    }
                }
                apply()
            }
        }
        ReadBookConfig.apply {
            comicStyleSelect = appCtx.getPrefInt(PreferKey.comicStyleSelect)
            readStyleSelect = appCtx.getPrefInt(PreferKey.readStyleSelect)
            shareLayout = appCtx.getPrefBoolean(PreferKey.shareLayout)
            hideStatusBar = appCtx.getPrefBoolean(PreferKey.hideStatusBar)
            hideNavigationBar = appCtx.getPrefBoolean(PreferKey.hideNavigationBar)
            autoReadSpeed = appCtx.getPrefInt(PreferKey.autoReadSpeed, 46)
            resetActiveConfig()
        }
        val agentBackup = File(path, io.legado.app.help.agent.AgentBackup.FILE_NAME)
        if (agentBackup.exists()) {
            io.legado.app.help.agent.AgentBackup.restore(agentBackup)
        }
        postEvent(EventBus.UP_CONFIG, arrayListOf(1, 2, 5))
        appCtx.toastOnUi(R.string.restore_success)
        hintDuplicatesAfterRestore()
        withContext(Main) {
            delay(100)
            LauncherIconHelp.changeIcon(appCtx.getPrefString(PreferKey.launcherIcon))
            ThemeConfig.applyDayNight(appCtx)
        }
    }

    private suspend fun restoreDbData(path: String, aes: BackupAES) {
        // M1: DB 段（bookshelf→servers.json）整体事务化——任一步失败则整库回滚，杜绝半恢复态。
        // 用 Room withTransaction(suspend)：keyboardAssistsDao.deleteAll() 是 suspend DAO，
        // 原生 beginTransaction 无法让它在同一事务线程执行；withTransaction 在事务上下文中协调 suspend 与同步 DAO。
        appDb.withTransaction {
            // 备份书 URL → 最终落库的本机书 URL。配图段据此重映射并过滤，保证外键恒有父行。
            val restoredBookUrls = restoreShelfBooks(path)
            fileToListT<Bookmark>(path, "bookmark.json")?.let {
                appDb.bookmarkDao.insert(*it.toTypedArray())
            }
            restoreIllustrations(path, restoredBookUrls)
            fileToListT<BookGroup>(path, "bookGroup.json")?.let {
                appDb.bookGroupDao.insert(*it.toTypedArray())
            }
            fileToListT<BookSource>(path, "bookSource.json")?.let {
                appDb.bookSourceDao.insert(*it.toTypedArray())
            } ?: run {
                val bookSourceFile = File(path, "bookSource.json")
                if (bookSourceFile.exists()) {
                    val json = bookSourceFile.readText()
                    ImportOldData.importOldSource(json)
                }
            }
            fileToListT<RssSource>(path, "rssSources.json")?.let {
                appDb.rssSourceDao.insert(*it.toTypedArray())
            }
            fileToListT<RssStar>(path, "rssStar.json")?.let {
                appDb.rssStarDao.insert(*it.toTypedArray())
            }
            fileToListT<ReplaceRule>(path, "replaceRule.json")?.let {
                appDb.replaceRuleDao.insert(*it.toTypedArray())
            }
            fileToListT<SearchKeyword>(path, "searchHistory.json")?.let {
                appDb.searchKeywordDao.insert(*it.toTypedArray())
            }
            fileToListT<RuleSub>(path, "sourceSub.json")?.let {
                appDb.ruleSubDao.insert(*it.toTypedArray())
            }
            fileToListT<TxtTocRule>(path, "txtTocRule.json")?.let {
                appDb.txtTocRuleDao.insert(*it.toTypedArray())
            }
            fileToListT<HttpTTS>(path, "httpTTS.json")?.let {
                appDb.httpTTSDao.insert(*it.toTypedArray())
            }
            fileToListT<DictRule>(path, "dictRule.json")?.let {
                appDb.dictRuleDao.insert(*it.toTypedArray())
            }
            fileToListT<KeyboardAssist>(path, "keyboardAssists.json")?.let {
                appDb.keyboardAssistsDao.deleteAll() //先删除所有,保证和备份数据一样
                appDb.keyboardAssistsDao.insert(*it.toTypedArray())
            }
            fileToListT<ReadRecord>(path, "readRecord.json")?.let {
                it.forEach { readRecord ->
                    mergeReadRecord(readRecord)
                }
            }
            fileToListT<ReadRecordDaily>(path, "readRecordDaily.json")?.let {
                it.forEach { record ->
                    mergeReadRecordDaily(record)
                }
            }
            File(path, "servers.json").takeIf {
                it.exists()
            }?.runCatching {
                var json = readText()
                if (!json.isJsonArray()) {
                    json = aes.decryptStr(json)
                }
                GSON.fromJsonArray<Server>(json).getOrNull()?.let {
                    appDb.serverDao.insert(*it.toTypedArray())
                }
            }?.onFailure {
                AppLog.put("恢复服务器配置出错\n${it.localizedMessage}", it)
            }
        }
    }

    /**
     * 恢复后提示书架存在重复书籍。
     *
     * 恢复只负责**收敛备份与本次入库**的同书，**不清理本机既有的重复记录** ——
     * 用户在两台设备各选一个书源就会留下两条同书记录，这类历史残留属于
     * 书架菜单「合并重复书籍」的职责（那里已有完整的扫描/确认/合并链路）。
     *
     * 此处**只在确有重复时**提示，不打扰无重复的恢复；也**不做合并**（不静默改库）。
     */
    private fun hintDuplicatesAfterRestore() {
        val groups = BookMergeRules.duplicateGroups(appDb.bookDao.all)
        if (groups.isEmpty()) return
        appCtx.toastOnUi(R.string.restore_duplicates_hint)
    }

    /**
     * 恢复书架书籍，返回「备份书 URL → 最终落库的本机书 URL」映射。
     *
     * 身份收敛：`Book` 表主键是 `bookUrl`（详情页地址），同一本书在不同书源下 `bookUrl` 不同，
     * 朴素地按 `bookUrl` 合并会让书架出现两条记录（两设备选了不同书源时必然发生）。
     * 此处按 [BookMergeRules.identityKeyOf]（书名+作者+媒体类型）配对，命中则并入**本机记录**。
     *
     * ⚠️ 刻意**不复用** `BookUpsert.upsertByIdentity`：那是「换源」语义，会
     * ① 改写 `origin`/`tocUrl` 而恢复场景没有备份目录可重建章节；
     * ② 调 `clearIllustrations` 清掉本机配图（备份不含章节表，配图无法重建）。
     * 恢复需要的是保守合并，见 [BookMergeRules.mergeFromBackup]。
     *
     * ⚠️ 返回值必须覆盖**所有**最终存在于 `books` 表的书 URL，配图段据此过滤以防外键失败。
     */
    private fun restoreShelfBooks(path: String): Map<String, String> {
        val books = fileToBookList(path) ?: return emptyMap()
        books.forEach { book ->
            book.upType()
            // upType() 只改写 type（旧格式升级），不同步 mediaType 列。
            // 身份判据走 stableMediaType（由 type 推导），故不影响收敛；
            // 但 mediaType 有独立读取点（朗读语速 Book.kt:389），这里补一次同步。
            book.syncMediaType()
        }
        books.filter { book -> book.isLocal }
            .forEach { book ->
                book.coverUrl = LocalBook.getCoverPath(book)
            }
        val restoredBookUrls = hashMapOf<String, String>()
        val newBooks = arrayListOf<Book>()
        val ignoreLocalBook = BackupConfig.ignoreLocalBook
        books.forEach { book ->
            if (ignoreLocalBook && book.isLocal) {
                return@forEach
            }
            // 身份命中：并入本机记录（保留本机书源身份，不清任何本机数据）。
            val localBook = findLocalBookByIdentity(book)
            if (localBook != null) {
                val toc = appDb.bookChapterDao.getChapterList(localBook.bookUrl)
                val merged = BookMergeRules.mergeFromBackup(localBook, book, toc)
                appDb.bookDao.update(merged)
                restoredBookUrls[book.bookUrl] = localBook.bookUrl
                return@forEach
            }
            if (appDb.bookDao.has(book.bookUrl)) {
                try {
                    appDb.bookDao.update(book)
                } catch (_: SQLiteConstraintException) {
                    appDb.bookDao.insert(book)
                }
                restoredBookUrls[book.bookUrl] = book.bookUrl
            } else {
                newBooks.add(book)
                restoredBookUrls[book.bookUrl] = book.bookUrl
            }
        }
        appDb.bookDao.insert(*newBooks.toTypedArray())
        return restoredBookUrls
    }

    /**
     * 按身份键在本机找同书；不参与身份收敛的书（本地书 / 未入架 / 无书名）返回 null。
     *
     * 判据与书架「合并重复书籍」共用 [BookMergeRules]，不新造第三套规则。
     */
    private fun findLocalBookByIdentity(book: Book): Book? {
        val key = BookMergeRules.identityKeyOf(book) ?: return null
        return appDb.bookDao.getBooks(key.name, key.author)
            .firstOrNull { BookMergeRules.stableMediaType(it) == key.mediaType }
    }

    /**
     * 恢复配图。
     *
     * 两条硬约束（否则会触发外键失败导致整个 DB 段回滚）：
     * ① 每行的 `bookUrl` 必须重映射到**实际存在于 books 表**的书 URL
     *    （备份里 `ignoreLocalBook` 跳过的本地书、以及身份合并后的书，其原 URL 并不落库）；
     * ② 找不到父行的行直接跳过并记日志（暴露而非静默）。
     *
     * ⚠️ **不删除任何本机配图**：备份不含章节表，本机配图无法重建，清掉即永久丢失。
     * 已存在同章节配图时跳过，避免重复恢复不断累积副本（旧实现是裸 insert，会累积）。
     */
    private fun restoreIllustrations(path: String, restoredBookUrls: Map<String, String>) {
        val illustrations = fileToListT<BookIllustration>(path, "bookIllustration.json") ?: return
        val pending = arrayListOf<BookIllustration>()
        var skipped = 0
        illustrations.forEach { illustration ->
            val targetBookUrl = restoredBookUrls[illustration.bookUrl]
            if (targetBookUrl == null) {
                skipped++
                return@forEach
            }
            // 同源才带回：备份配图的 chapterIndex 锚定在备份源目录上，跨源时与本机目录不可比，
            // 硬塞会把配图挂到错误章节。跨源配图保留本机现状。
            val exist = appDb.bookIllustrationDao
                .getByBookAndChapter(targetBookUrl, illustration.chapterIndex)
            if (exist.isNotEmpty()) {
                return@forEach
            }
            if (targetBookUrl != illustration.bookUrl) {
                val targetBook = appDb.bookDao.getBook(targetBookUrl) ?: run {
                    skipped++
                    return@forEach
                }
                val backupBook = appDb.bookDao.getBook(illustration.bookUrl)
                // 跨源（origin 不同）时丢弃备份配图，避免章节锚点错位
                if (backupBook != null && backupBook.origin != targetBook.origin) {
                    skipped++
                    return@forEach
                }
            }
            pending.add(illustration.copy(id = 0, bookUrl = targetBookUrl))
        }
        if (pending.isNotEmpty()) {
            appDb.bookIllustrationDao.insert(*pending.toTypedArray())
        }
        if (skipped > 0) {
            LogUtils.d(TAG, "恢复配图跳过 $skipped 条（父书不存在或跨源锚点不可比）")
        }
    }

    private inline fun <reified T> fileToListT(path: String, fileName: String): List<T>? {
        try {
            val file = File(path, fileName)
            if (file.exists()) {
                LogUtils.d(TAG, "阅读恢复备份 $fileName 文件大小 ${file.length()}")
                FileInputStream(file).use {
                    return GSON.fromJsonArray<T>(it).getOrThrow().also { list ->
                        LogUtils.d(TAG, "阅读恢复备份 $fileName 列表大小 ${list.size}")
                    }
                }
            } else {
                LogUtils.d(TAG, "阅读恢复备份 $fileName 文件不存在")
            }
        } catch (e: Exception) {
            AppLog.put("$fileName\n读取解析出错\n${e.localizedMessage}", e)
            appCtx.toastOnUi("$fileName\n读取文件出错\n${e.localizedMessage}")
        }
        return null
    }

    private fun mergeReadRecord(readRecord: ReadRecord) {
        val normalized = readRecord.copy(
            deviceId = readRecord.deviceId.ifBlank { androidId }
        )
        val current = appDb.readRecordDao.getRecord(normalized.deviceId, normalized.bookName)
        if (current == null) {
            appDb.readRecordDao.insert(normalized)
            return
        }
        appDb.readRecordDao.insert(
            current.copy(
                readTime = maxOf(current.readTime, normalized.readTime),
                lastRead = maxOf(current.lastRead, normalized.lastRead)
            )
        )
    }

    private fun mergeReadRecordDaily(record: ReadRecordDaily) {
        val current = appDb.readRecordDailyDao.get(record.date)
        if (current == null) {
            appDb.readRecordDailyDao.insert(record)
            return
        }
        appDb.readRecordDailyDao.insert(
            current.copy(
                readTime = maxOf(current.readTime, record.readTime),
                updatedAt = maxOf(current.updatedAt, record.updatedAt)
            )
        )
    }

    private fun fileToBookList(path: String): List<Book>? {
        val fileName = "bookshelf.json"
        try {
            val file = File(path, fileName)
            if (file.exists()) {
                LogUtils.d(TAG, "阅读恢复备份 $fileName 文件大小 ${file.length()}")
                val list = arrayListOf<Book>()
                file.reader().use { reader ->
                    val jsonArray = JsonParser.parseReader(reader).asJsonArray
                    jsonArray.forEachIndexed { index, element ->
                        val bookJson = element.deepCopy()
                        sanitizeBookJson(bookJson)
                        runCatching {
                            GSON.fromJson(bookJson, Book::class.java)
                        }.onSuccess { book ->
                            if (book != null) {
                                list.add(book)
                            }
                        }.onFailure {
                            AppLog.put("$fileName 第${index + 1}项读取失败\n${it.localizedMessage}", it)
                        }
                    }
                }
                LogUtils.d(TAG, "阅读恢复备份 $fileName 列表大小 ${list.size}")
                return list
            } else {
                LogUtils.d(TAG, "阅读恢复备份 $fileName 文件不存在")
            }
        } catch (e: Exception) {
            AppLog.put("$fileName\n读取解析出错\n${e.localizedMessage}", e)
            appCtx.toastOnUi("$fileName\n读取文件出错\n${e.localizedMessage}")
        }
        return null
    }

    private fun sanitizeBookJson(element: JsonElement) {
        if (!element.isJsonObject) {
            return
        }
        val bookJson = element.asJsonObject
        val readConfig = bookJson.get("readConfig") ?: return
        if (!readConfig.isJsonObject) {
            bookJson.remove("readConfig")
            return
        }
        sanitizeReadConfigJson(readConfig.asJsonObject)
    }

    private fun sanitizeReadConfigJson(readConfig: JsonObject) {
        val startDate = readConfig.get("startDate") ?: return
        if (startDate.isJsonPrimitive && startDate.asJsonPrimitive.isString) {
            val legacyStartDate = runCatching {
                JsonParser.parseString(startDate.asString)
            }.getOrNull()
            if (legacyStartDate?.isJsonObject == true && legacyStartDate.asJsonObject.isValidLocalDateJson()) {
                readConfig.add("startDate", legacyStartDate)
            } else {
                readConfig.remove("startDate")
            }
        } else if (startDate.isJsonObject && !startDate.asJsonObject.isValidLocalDateJson()) {
            readConfig.remove("startDate")
        }
    }

    private fun JsonObject.isValidLocalDateJson(): Boolean {
        val year = getIntOrNull("year") ?: return true
        val month = getIntOrNull("month") ?: return true
        val day = getIntOrNull("day") ?: return true
        return year > 0 && month in 1..12 && day in 1..31
    }

    private fun JsonObject.getIntOrNull(name: String): Int? {
        val value = get(name)?.takeIf { it.isJsonPrimitive } ?: return null
        return runCatching { value.asInt }.getOrNull()
    }

    private fun restoreBackgroundAssets(path: String) {
        backgroundAssetDirNames.forEach { dirName ->
            val sourceDir = File(path, dirName)
            if (!sourceDir.exists() || !sourceDir.isDirectory) return@forEach
            val targetDir = appCtx.externalFiles.getFile(dirName)
            kotlin.runCatching {
                FileUtils.delete(targetDir, deleteRootDir = true)
                copyDir(sourceDir, targetDir)
            }.onFailure {
                AppLog.put("恢复背景图片出错 $dirName\n${it.localizedMessage}", it)
            }
        }
    }

    private fun restoreThemePackages(path: String) {
        val sourceDir = File(path, "themePackages")
        if (!sourceDir.exists() || !sourceDir.isDirectory) return
        val targetDir = ThemePackageManager.rootDir
        kotlin.runCatching {
            BackupThemePackageDedupe.restoreThemePackageFonts(File(path))
            FileUtils.delete(targetDir, deleteRootDir = true)
            copyDir(sourceDir, targetDir)
        }.onFailure {
            AppLog.put("恢复主题包出错\n${it.localizedMessage}", it)
        }
    }

    private fun restoreNavigationIcons(path: String) {
        val sourceDir = File(path, "navigationBarPackages")
        if (!sourceDir.exists() || !sourceDir.isDirectory) return
        val targetDir = NavigationBarIconConfig.rootDir
        kotlin.runCatching {
            FileUtils.delete(targetDir, deleteRootDir = true)
            copyDir(sourceDir, targetDir)
        }.onFailure {
            AppLog.put("恢复导航栏图标出错\n${it.localizedMessage}", it)
        }
    }

    private fun normalizeBackgroundPrefs() {
        val edit = appCtx.defaultSharedPreferences.edit()
        var changed = false
        backgroundAssetDirNames.forEach { key ->
            val current = appCtx.getPrefString(key) ?: return@forEach
            if (current.isBlank() || current.startsWith("http")) return@forEach
            if (File(current).exists()) return@forEach
            val fileName = File(current).name.takeIf { it.isNotBlank() } ?: return@forEach
            val restoredFile = appCtx.externalFiles.getFile(key, fileName)
            if (restoredFile.exists()) {
                edit.putString(key, restoredFile.absolutePath)
                changed = true
            }
        }
        if (changed) {
            edit.commit()
        }
    }

    fun repairLocalCoverPaths() {
        val changedBooks = appDb.bookDao.all.mapNotNull { book ->
            var changed = false
            val coverUrl = normalizeLocalCoverPath(book.coverUrl)
            val customCoverUrl = normalizeLocalCoverPath(book.customCoverUrl)
            if (coverUrl != book.coverUrl) {
                book.coverUrl = coverUrl
                changed = true
            }
            if (customCoverUrl != book.customCoverUrl) {
                book.customCoverUrl = customCoverUrl
                changed = true
            }
            book.takeIf { changed }
        }
        if (changedBooks.isNotEmpty()) {
            appDb.bookDao.update(*changedBooks.toTypedArray())
        }

        val changedGroups = appDb.bookGroupDao.all.mapNotNull { group ->
            val cover = normalizeLocalCoverPath(group.cover)
            if (cover != group.cover) {
                group.cover = cover
                group
            } else {
                null
            }
        }
        if (changedGroups.isNotEmpty()) {
            appDb.bookGroupDao.update(*changedGroups.toTypedArray())
        }
    }

    fun normalizeLocalCoverPath(path: String?): String? {
        if (path.isNullOrBlank() ||
            path.startsWith("http", ignoreCase = true) ||
            path.isContentScheme()
        ) {
            return path
        }
        if (!path.contains(File.separator)) {
            return path
        }
        if (path.startsWith(appCtx.externalFiles.absolutePath) && File(path).exists()) {
            return path
        }
        val fileName = File(path).name.takeIf { it.isNotBlank() } ?: return path
        val restoredFile = appCtx.externalFiles.getFile("covers", fileName)
        if (restoredFile.exists()) {
            return restoredFile.absolutePath
        }
        return path
    }

    /**
     * 恢复后把阅读记录头像配置里的绝对路径修正到当前应用目录，
     * 找不到对应文件时保留原路径，不置空
     */
    private fun normalizeReadRecordGoalAvatar() {
        val raw = appCtx.getPrefString(PreferKey.readRecordGoalConfig) ?: return
        if (raw.isBlank()) return
        val json = runCatching { JsonParser.parseString(raw) }
            .getOrNull()
            ?.takeIf { it.isJsonObject }
            ?: return
        val avatarElement = json.asJsonObject.get("avatar") ?: return
        if (!avatarElement.isJsonPrimitive) return
        val avatar = avatarElement.asString
        if (avatar.isNullOrBlank() ||
            avatar.startsWith("http", ignoreCase = true) ||
            avatar.isContentScheme()
        ) {
            return
        }
        if (!avatar.contains(File.separator)) return
        val fileName = File(avatar).name.takeIf { it.isNotBlank() } ?: return
        val restoredFile = appCtx.externalFiles.getFile("readRecordGoalAvatar", fileName)
        if (!restoredFile.exists()) return
        json.asJsonObject.addProperty("avatar", restoredFile.absolutePath)
        appCtx.defaultSharedPreferences.edit()
            .putString(PreferKey.readRecordGoalConfig, json.toString())
            .commit()
    }

    private fun normalizeStringPrefs() {
        val stringKeys = setOf(
            PreferKey.language,
            PreferKey.themeMode,
            PreferKey.userAgent,
            PreferKey.customHosts,
            PreferKey.bookGroupStyle,
            PreferKey.bookshelfHiddenTags,
            PreferKey.bookshelfGroupTags,
            PreferKey.ttsEngine,
            PreferKey.prevKeys,
            PreferKey.nextKeys,
            PreferKey.mergedDiscoveryRssTarget,
            PreferKey.modernDiscoverySourceUrl,
            PreferKey.modernRssSourceUrl,
            PreferKey.aiProviderList,
            PreferKey.aiCurrentProviderId,
            PreferKey.aiModelConfigList,
            PreferKey.aiCurrentModelId,
            PreferKey.themePackageSyncTasks,
            PreferKey.aiTavilySearchDepth,
            PreferKey.aiTavilyTopic,
            PreferKey.aiBaseUrl,
            PreferKey.aiApiKey,
            PreferKey.aiCurrentModel,
            PreferKey.aiModelList,
            PreferKey.bookshelfLayout,
            PreferKey.bookshelfSort,
            PreferKey.bookExportFileName,
            PreferKey.bookImportFileName,
            PreferKey.episodeExportFileName,
            PreferKey.fontFolder,
            PreferKey.backupPath,
            PreferKey.webDavUrl,
            PreferKey.webDavAccount,
            PreferKey.webDavPassword,
            PreferKey.webDavDir,
            PreferKey.exportType,
            PreferKey.chineseConverterType,
            PreferKey.launcherIcon,
            PreferKey.systemTypefaces,
            PreferKey.uiFontPath,
            PreferKey.titleFontPath,
            PreferKey.bottomBarEffectMode,
            PreferKey.bottomBarLayoutMode,
            PreferKey.bottomBarSidebarGravity,
            PreferKey.uiCornerScale,
            PreferKey.uiCornerEffectMode,
            PreferKey.defaultCover,
            PreferKey.defaultCoverDark,
            PreferKey.screenOrientation,
            PreferKey.exportCharset,
            PreferKey.mangaFooterConfig,
            PreferKey.mangaColorFilter,
            PreferKey.contentSelectMenuConfig,
            PreferKey.contentSelectActions,
            PreferKey.contentSelectActionsOrder,
            PreferKey.contentSelectDefaultOpen,
            PreferKey.advancedTitleConfig,
            PreferKey.advancedTitleLottieJson,
            PreferKey.advancedTitleLottiePath,
            PreferKey.doublePageHorizontal,
            PreferKey.defaultBookTreeUri,
            PreferKey.readRecordComponents,
            PreferKey.readRecordRecentSnapshots,
            PreferKey.readRecordGoalConfig,
            PreferKey.localBookImportSort,
            PreferKey.progressBarBehavior,
            PreferKey.webDavDeviceName,
            PreferKey.defaultHomePage,
            PreferKey.clickImgWay,
            PreferKey.bottomWebViewDialogHeight,
            PreferKey.dThemeName,
            PreferKey.dNThemeName,
            PreferKey.bgImage,
            PreferKey.bookInfoBgImage,
            PreferKey.bgImageN,
            PreferKey.bookInfoBgImageN,
            "navigationBarPackageDay",
            "navigationBarPackageNight"
        )
        val all = appCtx.defaultSharedPreferences.all
        val edit = appCtx.defaultSharedPreferences.edit()
        var changed = false
        stringKeys.forEach { key ->
            val value = all[key] ?: return@forEach
            if (value !is String) {
                edit.putString(key, value.toString())
                changed = true
            }
        }
        if (changed) {
            edit.commit()
        }
    }

    private fun copyDir(source: File, target: File) {
        if (!target.exists()) {
            target.mkdirs()
        }
        source.listFiles()?.forEach { file ->
            val targetFile = File(target, file.name)
            if (file.isDirectory) {
                copyDir(file, targetFile)
            } else {
                targetFile.parentFile?.mkdirs()
                file.copyTo(targetFile, overwrite = true)
            }
        }
    }

}
