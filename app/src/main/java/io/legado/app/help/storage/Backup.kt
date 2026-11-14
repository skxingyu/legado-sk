package io.legado.app.help.storage

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.config.ThemePackageManager
import io.legado.app.help.config.NavigationBarIconConfig
import io.legado.app.help.book.ShelfIdentity
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.webdav.ProgressListener
import io.legado.app.model.BookCover
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.createFolderIfNotExist
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.getSharedPreferences
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.outputStream
import io.legado.app.utils.toastOnUiBrief
import io.legado.app.utils.writeToOutputStream
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import androidx.core.content.edit
import io.legado.app.model.VideoPlay.VIDEO_PREF_NAME

/**
 * 只保留真正存在于 [dir] 下的条目。
 *
 * 备份目录里的 json 由 `Backup.writeListToJson` 按「列表非空」写出，空表**不会落盘**；
 * 而 `ZipUtils.zipFile` 已改为 `require` 源文件存在，把不存在的路径交给它会让
 * 一个空表直接中止整次备份。所以打包前必须按实际落盘结果过滤，而不是按清单全量拼路径。
 *
 * 独立成顶层函数（不挂在 `Backup` object 上）以便 JVM 单测直接覆盖：
 * `Backup` 的初始化会触碰 `appCtx`/`appDb` 等 Android 依赖。
 */
internal fun existingZipSources(dir: String, names: Collection<String>): List<String> {
    return names.map { File(dir, it) }.filter { it.exists() }.map { it.absolutePath }
}

/**
 * covers 目录是否应进入备份包。
 *
 * `prepareCustomCoverBackup()` 会无条件创建 covers 目录，所以"目录存在"不能作为入包依据 ——
 * 否则从未设过自定义封面时会把一个空目录写进 zip（`ZipUtils` 对空目录会写 ZipEntry）。
 *
 * ⚠️ 判据必须是**目录的实际内容**，不能是"本次拷贝了几个文件"：
 * 用户通过「选择本地图片」设的封面本身就落在 covers 目录内，
 * `prepareCustomCoverBackup()` 对这类路径会直接跳过拷贝（无需复制到自身），
 * 用拷贝数判定会把最常见的场景误判为空、导致封面漏备份。
 *
 * 独立成顶层函数以便 JVM 单测直接覆盖（同 [existingZipSources]）。
 */
internal fun coverDirShouldBeZipped(coversDir: File): Boolean {
    return coversDir.listFiles()?.isNotEmpty() == true
}

/**
 * 导航栏图标目录名与共享清单里的字面量必须一致。
 *
 * `BackupItems.navigationBarDirName` 刻意写成字面量（引用 `rootDir` 会读 `appCtx`，
 * 使整份清单无法在 JVM 单测中求值），于是这里成了可能漂移的第二处事实。
 * 漂移即「导航栏图标」这一项静默失效：用户勾了不打包、取消了照旧打包。
 *
 * 独立成顶层函数以便 JVM 单测直接覆盖（同 [existingZipSources]）。
 */
internal fun navigationBarDirNameStaysInSync(): Boolean {
    return BackupItems.navigationBarDirName == NavigationBarIconConfig.rootDir.name
}

/**
 * 云端最近一次备份是否仍落在 24 小时窗口内。
 *
 * 判据只看**服务端返回的最后修改时间**（＝上传时刻），**不区分设备**：
 * 平板在 24 小时内备份过，手机就不应再传一份自己的。
 * `lastModify` 为 0 表示服务器没给出时间（或没有备份），按"没有"处理。
 *
 * 独立成顶层函数以便 JVM 单测直接覆盖（[Backup] 初始化会触碰 `appCtx`）。
 */
internal fun isCloudBackupWithinOneDay(lastModify: Long, now: Long): Boolean {
    return lastModify + TimeUnit.DAYS.toMillis(1) > now
}

/**
 * 备份
 */
object Backup {

    val backupPath: String by lazy {
        appCtx.filesDir.getFile("backup").createFolderIfNotExist().absolutePath
    }
    val zipFilePath = "${appCtx.externalFiles.absolutePath}${File.separator}tmp_backup.zip"

    private const val TAG = "Backup"

    private val mutex = Mutex()

    /**
     * 「书架变动自动备份」的去抖任务。
     * 连续触发时取消上一个重开计时，而不是叠加多个备份任务。
     */
    private var pendingShelfChangeJob: Coroutine<Unit>? = null

    /**
     * 书架变动后的去抖时长：等连续增删（批量导入/删除）停下来再备份。
     *
     * 取 3 秒（10110 由 8 秒下调，作者要求 3~3.5 秒区间）：去抖窗口内的进程被杀会让这次备份
     * **永久丢失**（`lastShelfKeys` 只在成功后记账，启动时不补帐），而「加完书就退出」是常见操作。
     * 窗口越短丢的概率越低；但再短会让连续操作容易被拆成多次备份（多传几次 WebDAV）。
     */
    private const val SHELF_CHANGE_DEBOUNCE_MS = 3_000L

    // 注意：不含 "covers"。该目录由 prepareCustomCoverBackup() 在打包清单构建期间才创建，
    // 放进这里会被存在性判定（此时目录还不存在）提前跳过，导致自定义封面漏备份。
    private val backgroundAssetDirNames = arrayOf(
        "bg",
        "font",
        "readRecordGoalAvatar",
        "illustrations",
        PreferKey.bgImage,
        PreferKey.bgImageN,
        PreferKey.bookInfoBgImage,
        PreferKey.bookInfoBgImageN
    )

    private val backupFileNames by lazy {
        arrayOf(
            "bookshelf.json",
            "bookmark.json",
            "bookIllustration.json",
            "bookGroup.json",
            "bookSource.json",
            "rssSources.json",
            "rssStar.json",
            "replaceRule.json",
            "readRecord.json",
            "readRecordDaily.json",
            "searchHistory.json",
            "sourceSub.json",
            "txtTocRule.json",
            "httpTTS.json",
            "keyboardAssists.json",
            "dictRule.json",
            BackupItems.loginCookieFileName,
            BackupItems.loginCacheFileName,
            "servers.json",
            DirectLinkUpload.ruleFileName,
            ReadBookConfig.configFileName,
            ReadBookConfig.shareConfigFileName,
            ThemeConfig.configFileName,
            BookCover.configFileName,
            "config.xml",
            "videoConfig.xml",
            io.legado.app.help.agent.AgentBackup.FILE_NAME
        )
    }

    private fun getNowZipFileName(): String {
        val backupDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(Date(System.currentTimeMillis()))
        val deviceName = AppConfig.webDavDeviceName
        return if (deviceName?.isNotBlank() == true) {
            "backup${backupDate}-${deviceName}.zip"
        } else {
            "backup${backupDate}.zip"
        }.normalizeFileName()
    }

    /**
     * 把书架/分组自定义封面指向的本地图片复制进 covers 目录，
     * 恢复时才能按文件名重新映射路径
     */
    private fun prepareCustomCoverBackup() {
        val coversDir = appCtx.externalFiles.getFile("covers").apply {
            createFolderIfNotExist()
        }
        val coverPaths = arrayListOf<String>()
        appDb.bookDao.all.forEach { book ->
            book.customCoverUrl?.let { coverPaths.add(it) }
        }
        appDb.bookGroupDao.all.forEach { group ->
            group.cover?.let { coverPaths.add(it) }
        }
        coverPaths.distinct().forEach { path ->
            if (path.isBlank() ||
                path.startsWith("http", ignoreCase = true) ||
                path.isContentScheme()
            ) {
                return@forEach
            }
            if (!path.contains(File.separator)) return@forEach
            if (path.startsWith(coversDir.absolutePath)) return@forEach
            val source = File(path)
            if (!source.isFile) return@forEach
            val fileName = source.name.takeIf { it.isNotBlank() } ?: return@forEach
            runCatching {
                source.copyTo(File(coversDir, fileName), overwrite = true)
            }.onFailure {
                AppLog.put("备份自定义封面失败 $path\n${it.localizedMessage}", it)
            }
        }
    }

    private fun shouldBackup(): Boolean {
        val lastBackup = LocalConfig.lastBackup
        return lastBackup + TimeUnit.DAYS.toMillis(1) < System.currentTimeMillis()
    }

    /**
     * 「一天一次」的自动备份。
     *
     * 闸门分两层：
     * 1. 本机上次备份（[LocalConfig.lastBackup]）是否已满 24 小时 —— 纯本地、无网络；
     * 2. 云端**任一设备**最近一次备份是否在 24 小时内（[AppWebDav.lastBackUp]）。
     *    命中则本机不再上传，并把 `lastBackup` 记账到那次云端备份的时间，
     *    使窗口按云端事实推进，且避免每次退出都重复查询。
     */
    fun autoBack(context: Context) {
        // 恢复的「下载 / 解压 / 选项目」阶段也会写入 `Backup.backupPath`，
        // 而 `backup()` 开头会把它整个删掉 —— 必须与书架变动路径一样显式避让。
        if (Restore.isRestoring) {
            AppLog.put("自动备份已跳过：恢复流程进行中")
            return
        }
        if (shouldBackup()) {
            Coroutine.async {
                mutex.withLock {
                    if (shouldBackup()) {
                        val latestCloudBackup = AppWebDav.lastBackUp()
                            .onFailure {
                                AppLog.put("读取云端最近备份失败\n${it.localizedMessage}", it)
                            }
                            .getOrNull()
                        val now = System.currentTimeMillis()
                        if (latestCloudBackup != null &&
                            isCloudBackupWithinOneDay(latestCloudBackup.lastModify, now)
                        ) {
                            // 取云端时间而非 now：否则会把 24 小时窗口从"手机这次退出"起重算
                            LocalConfig.lastBackup = latestCloudBackup.lastModify.coerceAtMost(now)
                            AppLog.put("自动备份已跳过：云端 24 小时内有备份（${latestCloudBackup.displayName}）")
                            return@withLock
                        }
                        val backupZipFileName = getNowZipFileName()
                        if (!AppWebDav.hasBackUp(backupZipFileName)) {
                            // 自动备份同样要遵守「备份项目」，但全不勾时必须中止，不能生成空包。
                            when (
                                val decision =
                                    decideBackupScope(BackupTargetConfig.selectedTargetsOrNull())
                            ) {
                                BackupScopeDecision.Abort ->
                                    AppLog.put("自动备份已跳过：备份项目一个都没勾选")

                                is BackupScopeDecision.Proceed ->
                                    backup(context, AppConfig.backupPath, targets = decision.targets)
                            }
                        } else {
                            LocalConfig.lastBackup = System.currentTimeMillis()
                        }
                    }
                }
            }.onError {
                AppLog.put("自动备份失败\n${it.localizedMessage}")
            }
        }
    }

    /**
     * 「书架变动时自动备份」。
     *
     * 与 [autoBack] 的区别（**两者不可互相替代**）：
     * - [autoBack] 是「**一天一次**」的定时兜底（`shouldBackup()` 闸门）；
     * - 本方法由书架**增删**触发，且**刻意绕开** `shouldBackup()` 闸门 ——
     *   否则「变动即备份」会被一天一次的闸门吞掉。
     * ⚠️ 也**不能**复用 `autoBack`：`backup()` 开头会无条件推高 `LocalConfig.lastBackup`，
     * 那会反过来压制既有的 `autoBack` 周期。
     *
     * ## 判据
     *
     * 比对**在线书身份键集合**（[ShelfIdentity.keysOf]）与本机上次记账的集合：
     * - 换源（`bookUrl` 变、身份键不变）**不算变动**；
     * - 阅读进度/书源更新等只改字段的动作**不算变动**；
     * - 离线书的增删**不算变动**；
     * - 只有在线书的**增删**才算。
     *
     * @param context 用于 [backup] 的落盘上下文
     */
    fun autoBackupOnShelfChangeIfNeeded(context: Context) {
        if (!AppConfig.autoBackupOnShelfChange) {
            return
        }
        // P0 守卫：恢复流程进行中一律不备份。
        // 恢复正把备份解压到 `backupPath`，而 `backup()` 开头会 `FileUtils.delete(backupPath)`
        // —— 中途备份会把正在被读取的备份目录删掉，使恢复读到空/半截内容。
        if (Restore.isRestoring) {
            AppLog.put("书架变动自动备份已跳过：恢复流程进行中")
            return
        }
        val currentKeys = encodeKeys(ShelfIdentity.keysOf(appDb.bookDao.all))
        if (currentKeys == LocalConfig.lastShelfKeys) {
            // ⚠️ 这里**必须记日志**：这是"触发了但没备份"的唯一静默出口。
            // 没有它时，用户在实机上看到"加了书却没有备份"无法区分三种情形：
            // ① 触发点没接上（代码缺陷）；② 触发了但身份键集合没变（设计上的幂等）；
            // ③ 备份正在去抖/上传中（还没到云端）。
            // 10076 排查时就因为这条静默 return 而无法从日志定性，只能靠读代码推断。
            AppLog.put("书架变动自动备份已跳过：书架身份键集合未变化（无在线书增删）")
            return
        }
        pendingShelfChangeJob?.cancel()
        pendingShelfChangeJob = Coroutine.async {
            // 去抖：连续的增删（如批量导入、批量删除）只触发一次。
            delay(SHELF_CHANGE_DEBOUNCE_MS)
            // 去抖期间可能又进了恢复，这里复查一次。
            if (Restore.isRestoring) {
                return@async
            }
            mutex.withLock {
                // ⚠️ 这里**不能**用 `backupPath.isNullOrBlank()` 当"没配置备份"的判据：
                // `AppConfig.backupPath` 只是**本地/SAF 目录**，与 WebDAV 完全无关；
                // `backup()` 内部会无条件 `AppWebDav.backUpWebDav()`，WebDAV 是独立配置项。
                // 手动备份在 `backupPath` 为空时是**弹目录选择器**（见 BackupConfigFragment.backup），
                // 不是"跳过"。自动备份没有 UI 可弹，故为空时**只跳过本地落盘**、仍走 WebDAV。
                // 曾经写成 `isNullOrBlank() → return`，导致"只配了 WebDAV"的用户
                // （最常见的用法）自动备份**永远静默不执行**。
                val localPath = AppConfig.backupPath?.takeIf { it.isNotBlank() }
                if (localPath == null && !AppWebDav.isOk) {
                    // 本地与 WebDAV 都没配 ⇒ 确实无处可写，跳过并记日志（不弹 UI）。
                    AppLog.put("书架变动自动备份已跳过：未配置本地备份目录且未配置 WebDAV")
                    return@withLock
                }
                val verified = ShelfIdentity.keysOf(appDb.bookDao.all)
                // ⚠️ 拿锁后**必须复查**一次：`pendingShelfChangeJob` 是普通 var（无 volatile/无锁），
                // 并发调用时后写覆盖前者，两个 job 都可能走到这里排队。先到的那个备份完并记了账，
                // 后到的若不复查就会**再备份一遍同样的书架**（不损坏数据，但白白多传一次）。
                if (encodeKeys(verified) == LocalConfig.lastShelfKeys) {
                    return@withLock
                }
                // 与手动入口共用同一判据：全不勾必须中止，否则会生成空 zip 覆盖唯一的旧备份。
                val targets = when (
                    val decision = decideBackupScope(BackupTargetConfig.selectedTargetsOrNull())
                ) {
                    BackupScopeDecision.Abort -> {
                        AppLog.put("书架变动自动备份已跳过：备份项目一个都没勾选")
                        return@withLock
                    }

                    is BackupScopeDecision.Proceed -> decision.targets
                }
                backup(context, localPath, targets = targets)
                // ⚠️ 记账必须放在**备份成功之后**：放在开头的话，备份中途被取消/进程被杀
                // 会留下「标记已更新但备份没做成」，该次变动此后永不补备份。
                LocalConfig.lastShelfKeys = encodeKeys(verified)
                AppLog.put("书架变动自动备份完成：${verified.size} 本在线书")
                context.toastOnUiBrief(R.string.auto_backup_shelf_done)
            }
        }.onError {
            AppLog.put("书架变动自动备份失败\n${it.localizedMessage}", it)
        }
    }

    /** 身份键集合 → 持久化字符串。键本身不含 `\u0001` 与换行。 */
    internal fun encodeKeys(keys: Set<ShelfIdentity.Key>): String =
        keys.map { "${it.mediaType}\u0001${it.name}\u0001${it.author}" }
            .sorted()
            .joinToString("\n")

    suspend fun backupLocked(
        context: Context,
        path: String?,
        onWebDavUploadProgress: ProgressListener? = null,
        targets: Set<String>? = null
    ) {
        mutex.withLock {
            withContext(IO) {
                backup(context, path, onWebDavUploadProgress, targets)
            }
        }
    }

    private suspend fun backup(
        context: Context,
        path: String?,
        onWebDavUploadProgress: ProgressListener? = null,
        targets: Set<String>? = null
    ) {
        LogUtils.d(TAG, "开始备份 path:$path")
        LocalConfig.lastBackup = System.currentTimeMillis()
        val aes = BackupAES()
        FileUtils.delete(backupPath)
        writeListToJson(appDb.bookDao.all, "bookshelf.json", backupPath)
        if (targets.shouldBackupTarget(io.legado.app.help.agent.AgentBackup.FILE_NAME)) {
            io.legado.app.help.agent.AgentBackup.write(File(backupPath, io.legado.app.help.agent.AgentBackup.FILE_NAME))
        }
        writeListToJson(appDb.bookmarkDao.all, "bookmark.json", backupPath)
        writeListToJson(appDb.bookIllustrationDao.all, "bookIllustration.json", backupPath)
        writeListToJson(appDb.bookGroupDao.all, "bookGroup.json", backupPath)
        writeListToJson(appDb.bookSourceDao.all, "bookSource.json", backupPath)
        writeListToJson(appDb.rssSourceDao.all, "rssSources.json", backupPath)
        writeListToJson(appDb.rssStarDao.all, "rssStar.json", backupPath)
        writeListToJson(appDb.replaceRuleDao.all, "replaceRule.json", backupPath)
        writeListToJson(appDb.readRecordDao.all, "readRecord.json", backupPath)
        writeListToJson(appDb.readRecordDailyDao.allDesc, "readRecordDaily.json", backupPath)
        writeListToJson(appDb.searchKeywordDao.all, "searchHistory.json", backupPath)
        writeListToJson(appDb.ruleSubDao.all, "sourceSub.json", backupPath)
        writeListToJson(appDb.txtTocRuleDao.all, "txtTocRule.json", backupPath)
        writeListToJson(appDb.httpTTSDao.all, "httpTTS.json", backupPath)
        writeListToJson(appDb.keyboardAssistsDao.all, "keyboardAssists.json", backupPath)
        writeListToJson(appDb.dictRuleDao.all, "dictRule.json", backupPath)
        writeListToJson(appDb.cookieDao.all(), BackupItems.loginCookieFileName, backupPath)
        writeListToJson(appDb.cacheDao.sourceState(), BackupItems.loginCacheFileName, backupPath)
        GSON.toJson(appDb.serverDao.all).let { json ->
            // SK 定制：加密失败必须中止备份，绝不允许静默退明文（拒绝静默兜底红线）
            val encrypted = aes.runCatching {
                encryptBase64(json)
            }.getOrElse {
                throw IllegalStateException("Web服务配置加密失败", it)
            }
            FileUtils.createFileIfNotExist(backupPath + File.separator + "servers.json")
                .writeText(encrypted)
        }
        currentCoroutineContext().ensureActive()
        GSON.toJson(ReadBookConfig.configList.toList()).let {
            FileUtils.createFileIfNotExist(backupPath + File.separator + ReadBookConfig.configFileName)
                .writeText(it)
        }
        GSON.toJson(ReadBookConfig.shareConfig).let {
            FileUtils.createFileIfNotExist(backupPath + File.separator + ReadBookConfig.shareConfigFileName)
                .writeText(it)
        }
        GSON.toJson(ThemeConfig.configList.toList()).let {
            FileUtils.createFileIfNotExist(backupPath + File.separator + ThemeConfig.configFileName)
                .writeText(it)
        }
        DirectLinkUpload.getConfig()?.let {
            FileUtils.createFileIfNotExist(backupPath + File.separator + DirectLinkUpload.ruleFileName)
                .writeText(GSON.toJson(it))
        }
        BookCover.getConfig()?.let {
            FileUtils.createFileIfNotExist(backupPath + File.separator + BookCover.configFileName)
                .writeText(GSON.toJson(it))
        }
        currentCoroutineContext().ensureActive()
        appCtx.getSharedPreferences(backupPath, "config")?.let { sp ->
            val edit = sp.edit()
            appCtx.defaultSharedPreferences.all.forEach { (key, value) ->
                if (BackupConfig.keyIsNotIgnore(key)) {
                    when (key) {
                        PreferKey.webDavPassword -> {
                            // SK 定制：加密失败必须中止备份，绝不允许静默退明文（拒绝静默兜底红线）
                            val encrypted = aes.runCatching {
                                encryptBase64(value.toString())
                            }.getOrElse {
                                throw IllegalStateException("WebDAV密码加密失败", it)
                            }
                            edit.putString(key, encrypted)
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
        currentCoroutineContext().ensureActive()
        appCtx.getSharedPreferences(backupPath, "videoConfig")?.let { sp ->
            sp.edit(commit = true) {
                appCtx.getSharedPreferences(VIDEO_PREF_NAME, Context.MODE_PRIVATE).all.forEach { (key, value) ->
                    when (value) {
                        is Int -> putInt(key, value)
                        is Boolean -> putBoolean(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is String -> putString(key, value)
                    }
                }
            }
        }
        currentCoroutineContext().ensureActive()
        val zipFileName = getNowZipFileName()
        val paths = existingZipSources(backupPath, backupFileNames.filter { targets.shouldBackupTarget(it) })
            .toMutableList()
        backgroundAssetDirNames.forEach { dirName ->
            if (targets.shouldBackupTarget(dirName)) {
                val dir = appCtx.externalFiles.getFile(dirName)
                // 同上：从未配置过背景/字体等时这些目录不存在，不是错误，跳过即可
                if (dir.exists()) paths.add(dir.absolutePath)
            }
        }
        if (targets.shouldBackupTarget("covers")) {
            // 目录由 prepareCustomCoverBackup() 创建，必须在此之后再判定；
            // 且只有目录里确实有封面才入包，避免空 covers/ 目录进 zip。
            prepareCustomCoverBackup()
            if (coverDirShouldBeZipped(appCtx.externalFiles.getFile("covers"))) {
                paths.add(appCtx.externalFiles.getFile("covers").absolutePath)
            }
        }
        if (targets.shouldBackupTarget(BackupThemePackageDedupe.themePackagesDirName)) {
            BackupThemePackageDedupe.prepareBackupThemePackages(
                sourceRoot = ThemePackageManager.rootDir,
                backupRoot = File(backupPath)
            )?.let {
                paths.add(it.absolutePath)
                // manifest 仅在存在重复字体时才写出（见 prepareBackupThemePackages），
                // 无重复字体时该文件不存在，不能强加进 zip
                val manifest = File(backupPath, BackupThemePackageDedupe.manifestFileName)
                if (manifest.exists()) paths.add(manifest.absolutePath)
            }
        }
        if (targets.shouldBackupTarget(NavigationBarIconConfig.rootDir.name)) {
            // rootDir 未预建目录，用户从未自定义导航栏图标时并不存在
            val iconDir = NavigationBarIconConfig.rootDir
            if (iconDir.exists()) paths.add(iconDir.absolutePath)
        }
        FileUtils.delete(zipFilePath)
        FileUtils.delete(zipFilePath.replace("tmp_", ""))
        val backupFileName = if (AppConfig.onlyLatestBackup) {
            "backup.zip"
        } else {
            zipFileName
        }
        if (ZipUtils.zipFiles(paths, zipFilePath)) {
            when {
                path.isNullOrBlank() -> {
                    copyBackup(context.getExternalFilesDir(null)!!, backupFileName)
                }

                path.isContentScheme() -> {
                    copyBackup(context, path.toUri(), backupFileName)
                }

                else -> {
                    copyBackup(File(path), backupFileName)
                }
            }
            try {
                AppWebDav.backUpWebDav(zipFileName, onWebDavUploadProgress)
            } catch (e: Exception) {
                AppLog.put("上传备份至webdav失败\n$e", e)
                if (onWebDavUploadProgress != null) {
                    throw e
                }
            }
        }
        FileUtils.delete(backupPath)
        FileUtils.delete(zipFilePath)
        currentCoroutineContext().ensureActive()
        ReadBookConfig.getAllPicBgStr().map {
            if (it.contains(File.separator)) {
                File(it)
            } else {
                appCtx.externalFiles.getFile("bg", it)
            }
        }.let {
            AppWebDav.upBgs(it.toTypedArray())
        }
    }

    private fun Set<String>?.shouldBackupTarget(target: String): Boolean {
        return this == null || contains(target)
    }

    private suspend fun writeListToJson(list: List<Any>, fileName: String, path: String) {
        currentCoroutineContext().ensureActive()
        withContext(IO) {
            if (list.isNotEmpty()) {
                LogUtils.d(TAG, "阅读备份 $fileName 列表大小 ${list.size}")
                val file = FileUtils.createFileIfNotExist(path + File.separator + fileName)
                file.outputStream().buffered().use {
                    GSON.writeToOutputStream(it, list)
                }
                LogUtils.d(TAG, "阅读备份 $fileName 写入大小 ${file.length()}")
            } else {
                LogUtils.d(TAG, "阅读备份 $fileName 列表为空")
            }
        }
    }

    @Throws(Exception::class)
    @Suppress("SameParameterValue")
    private fun copyBackup(context: Context, uri: Uri, fileName: String) {
        val treeDoc = DocumentFile.fromTreeUri(context, uri)!!
        // 先写 .part 再改名：中途失败最多留下一个残片，不会把上一份唯一备份删掉后写一半。
        val partName = "$fileName.part"
        treeDoc.findFile(partName)?.delete()
        val partDoc = treeDoc.createFile("", partName)
            ?: throw NoStackTraceException("创建文件失败")
        val output = partDoc.openOutputStream()
            ?: throw NoStackTraceException("打开OutputStream失败")
        output.use { outputS ->
            FileInputStream(zipFilePath).use { inputS -> inputS.copyTo(outputS) }
        }
        if (partDoc.renameTo(fileName)) return
        treeDoc.findFile(fileName)?.delete()
        if (partDoc.renameTo(fileName)) return
        // 个别 provider 不支持改名：退回逐字节复制，残片仍在，失败时数据不全丢。
        val fileDoc = treeDoc.createFile("", fileName)
            ?: throw NoStackTraceException("创建文件失败")
        val fileOutput = fileDoc.openOutputStream()
            ?: throw NoStackTraceException("打开OutputStream失败")
        fileOutput.use { outputS ->
            context.contentResolver.openInputStream(partDoc.uri)?.use { inputS ->
                inputS.copyTo(outputS)
            } ?: throw NoStackTraceException("打开临时备份文件失败")
        }
        partDoc.delete()
    }

    @Throws(Exception::class)
    @Suppress("SameParameterValue")
    private fun copyBackup(rootFile: File, fileName: String) {
        if (!rootFile.exists()) rootFile.mkdirs()
        val file = File(rootFile, fileName)
        val part = File(rootFile, "$fileName.part")
        FileInputStream(File(zipFilePath)).use { inputS ->
            FileOutputStream(part).use { outputS -> inputS.copyTo(outputS) }
        }
        if (part.renameTo(file)) return
        file.delete()
        if (part.renameTo(file)) return
        FileOutputStream(file).use { outputS ->
            FileInputStream(part).use { inputS -> inputS.copyTo(outputS) }
        }
        part.delete()
    }

    fun clearCache() {
        FileUtils.delete(backupPath)
        FileUtils.delete(zipFilePath)
    }
}
