package io.legado.app.help.config

import android.content.Context
import android.net.Uri
import androidx.annotation.Keep
import androidx.core.graphics.toColorInt
import androidx.documentfile.provider.DocumentFile
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.AppWebDav
import io.legado.app.utils.EncoderUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefString
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.compress.ZipUtils
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.util.zip.ZipFile

object ThemePackageManager {

    private const val packageFileName = "theme.json"
    private const val mainBackgroundPrefix = "background"
    private const val bookInfoBackgroundPrefix = "book_info_background"
    private const val uiFontPrefix = "ui_font"
    private const val titleFontPrefix = "title_font"
    private const val defaultDayPrimary = "#F1F2F6"
    private const val legacyDefaultDayPrimary = 0xFF795548.toInt()

    val rootDir: File
        get() = appCtx.externalFiles.getFile("themePackages")

    suspend fun load(isNightTheme: Boolean): List<Entry> = withContext(IO) {
        val local = loadLocal(isNightTheme).associateBy { it.dirName }
        val remote = if (AppConfig.syncThemePackages) {
            loadRemoteOrCache(isNightTheme).associateBy { it.dirName }
        } else {
            emptyMap()
        }
        val keys = local.keys + remote.keys
        sortEntries(keys.mapNotNull { key ->
            val localEntry = local[key]
            val remoteEntry = remote[key]
            when {
                localEntry != null && remoteEntry != null -> localEntry.copy(
                    source = Source.BOTH,
                    remoteUpdatedAt = remoteEntry.remoteUpdatedAt
                )

                localEntry != null -> localEntry
                remoteEntry != null -> remoteEntry
                else -> null
            }
        })
    }

    suspend fun loadLocalOnly(isNightTheme: Boolean): List<Entry> = withContext(IO) {
        sortEntries(loadLocal(isNightTheme))
    }

    suspend fun localThemeExists(
        isNightTheme: Boolean,
        themeName: String,
        excludeDirName: String? = null
    ): Boolean = withContext(IO) {
        val normalizedDirName = themeName.trim().normalizeFileName()
        loadLocal(isNightTheme).any {
            it.dirName == normalizedDirName && it.dirName != excludeDirName
        }
    }

    suspend fun addFromCurrent(context: Context, name: String, isNightTheme: Boolean): Entry =
        withContext(IO) {
            val normalizedName = name.trim().ifBlank { if (isNightTheme) "夜间主题" else "日间主题" }
            val config = ThemeConfig.getDurConfig(context).copy(
                themeName = normalizedName,
                isNightTheme = isNightTheme
            )
            saveConfig(config)
        }

    suspend fun addFromConfig(config: ThemeConfig.Config): Entry = withContext(IO) {
        saveConfig(config)
    }

    suspend fun themeExists(
        isNightTheme: Boolean,
        themeName: String,
        excludeDirName: String? = null
    ): Boolean = withContext(IO) {
        val normalizedDirName = themeName.trim().normalizeFileName()
        val localExists = loadLocal(isNightTheme).any {
            it.dirName == normalizedDirName && it.dirName != excludeDirName
        }
        if (localExists) {
            return@withContext true
        }
        if (!AppConfig.syncThemePackages) {
            return@withContext false
        }
        loadRemoteOrCache(isNightTheme).any {
            it.dirName == normalizedDirName && it.dirName != excludeDirName
        }
    }

    suspend fun upload(entry: Entry) = withContext(IO) {
        if (!AppConfig.syncThemePackages) return@withContext
        AppWebDav.uploadThemePackage(
            entry.packageInfo.isNightTheme,
            entry.dirName,
            exportZip(entry)
        )
    }

    suspend fun download(entry: Entry): Entry = withContext(IO) {
        val zipFile = tempDir.getFile("${entry.dirName}.zip")
        AppWebDav.downloadThemePackage(entry.packageInfo.isNightTheme, entry.dirName, zipFile)
        importZipInternal(zipFile, entry.remoteUpdatedAt).copy(source = Source.BOTH)
    }

    suspend fun importZip(zipFile: File): List<Entry> = withContext(IO) {
        if (isMd3ThemePackage(zipFile)) {
            return@withContext importMd3Internal(zipFile)
        }
        val pkg = peekPackage(zipFile)
        if (themeExists(pkg.isNightTheme, pkg.name)) {
            throw IllegalArgumentException("已存在同名主题")
        }
        listOf(importZipInternal(zipFile, 0L))
    }

    suspend fun exportZip(entry: Entry): File = withContext(IO) {
        val localEntry = if (entry.source == Source.REMOTE) download(entry) else entry
        val dir = localEntry.localDir ?: localDir(localEntry.packageInfo.isNightTheme, localEntry.dirName)
        val zipFile = tempDir.getFile("${localEntry.dirName}.zip")
        if (zipFile.exists()) zipFile.delete()
        ZipUtils.zipFile(dir, zipFile)
        zipFile
    }

    suspend fun deleteLocal(entry: Entry) = withContext(IO) {
        entry.localDir?.let { FileUtils.delete(it, deleteRootDir = true) }
    }

    suspend fun deleteRemote(entry: Entry) = withContext(IO) {
        AppWebDav.deleteThemePackage(entry.packageInfo.isNightTheme, entry.dirName)
    }

    fun apply(context: Context, entry: Entry, switchNightMode: Boolean = true) {
        val dir = entry.localDir ?: localDir(entry.packageInfo.isNightTheme, entry.dirName)
        val config = resolveConfigPaths(entry.packageInfo, dir)
        ThemeConfig.applyConfig(context, config, switchNightMode)
    }

    suspend fun reapplyRestoredAppliedThemes(context: Context) = withContext(IO) {
        val currentNight = AppConfig.isNightTheme
        reapplyRestoredAppliedTheme(context, !currentNight)
        reapplyRestoredAppliedTheme(context, currentNight)
    }

    fun getConfig(entry: Entry): ThemeConfig.Config {
        val dir = entry.localDir ?: localDir(entry.packageInfo.isNightTheme, entry.dirName)
        return resolveConfigPaths(entry.packageInfo, dir)
    }

    suspend fun ensureLocalAppliedTheme(context: Context, isNightTheme: Boolean): Entry =
        withContext(IO) {
            val currentConfig = ThemeConfig.getThemeConfig(context, isNightTheme)
            val config = currentConfig.copy(
                isNightTheme = isNightTheme,
                themeName = currentConfig.themeName.trim()
                    .ifBlank { if (isNightTheme) "夜间主题" else "日间主题" }
            )
            val dirName = config.themeName.normalizeFileName()
            val dir = localDir(isNightTheme, dirName)
            readPackage(dir)?.let { pkg ->
                return@withContext Entry(pkg, Source.LOCAL, localDir = dir)
            }
            saveConfig(config.copy(isNightTheme = isNightTheme))
        }

    private fun reapplyRestoredAppliedTheme(context: Context, isNightTheme: Boolean) {
        val themeName = context.getPrefString(
            if (isNightTheme) PreferKey.dNThemeName else PreferKey.dThemeName
        )?.trim().orEmpty()
        if (themeName.isBlank()) return
        val normalizedDirName = themeName.normalizeFileName()
        val directDir = localDir(isNightTheme, normalizedDirName)
        val entry = readPackage(directDir)?.let { pkg ->
            Entry(pkg, Source.LOCAL, localDir = directDir)
        } ?: loadLocal(isNightTheme).firstOrNull {
            it.dirName == normalizedDirName || it.packageInfo.name == themeName
        } ?: return
        val dir = entry.localDir ?: localDir(isNightTheme, entry.dirName)
        val config = resolveConfigPaths(entry.packageInfo, dir)
        ThemeConfig.applyConfig(context, config, switchNightMode = false, notify = false)
    }

    private fun saveConfig(config: ThemeConfig.Config): Entry {
        val normalizedName = config.themeName.trim()
            .ifBlank { if (config.isNightTheme) "夜间主题" else "日间主题" }
        val dirName = normalizedName.normalizeFileName()
        val dir = localDir(config.isNightTheme, dirName).apply {
            if (!exists()) mkdirs()
        }
        val namedConfig = config.copy(themeName = normalizedName)
        val packagedConfig = copyAssetsIntoPackage(namedConfig, dir, config.isNightTheme)
        val pkg = Package(
            name = normalizedName,
            dirName = dirName,
            isNightTheme = config.isNightTheme,
            updatedAt = System.currentTimeMillis(),
            config = packagedConfig
        )
        File(dir, packageFileName).writeText(GSON.toJson(pkg))
        ThemeConfig.addConfig(resolveConfigPaths(pkg, dir))
        return Entry(pkg, Source.LOCAL, localDir = dir)
    }

    private fun loadLocal(isNightTheme: Boolean): List<Entry> {
        val typeDir = typeDir(isNightTheme)
        return typeDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                readPackage(dir)?.let { pkg ->
                    Entry(pkg, Source.LOCAL, localDir = dir)
                }
            }.orEmpty()
    }

    private fun sortEntries(entries: List<Entry>): List<Entry> {
        return entries.sortedWith(
            compareBy<Entry> { it.source == Source.REMOTE }
                .thenByDescending { if (it.source == Source.REMOTE) it.remoteUpdatedAt else it.packageInfo.updatedAt }
                .thenBy { it.packageInfo.name }
                .thenBy { it.dirName }
        )
    }

    private suspend fun loadRemote(isNightTheme: Boolean): List<Entry> {
        return AppWebDav.listThemePackages(isNightTheme).map { remoteDir ->
            val dirName = remoteDir.displayName.trimEnd('/').removeSuffix(".zip")
            Entry(
                packageInfo = Package(
                    name = dirName,
                    dirName = dirName,
                    isNightTheme = isNightTheme,
                    updatedAt = remoteDir.lastModify,
                    config = null
                ),
                source = Source.REMOTE,
                remoteUpdatedAt = remoteDir.lastModify
            )
        }
    }

    private suspend fun loadRemoteOrCache(isNightTheme: Boolean): List<Entry> {
        return runCatching {
            loadRemote(isNightTheme).also { writeRemoteCache(isNightTheme, it) }
        }.getOrElse {
            readRemoteCache(isNightTheme)
        }
    }

    private fun remoteCacheFile(isNightTheme: Boolean): File {
        return remoteCacheDir.getFile(if (isNightTheme) "night.json" else "day.json")
    }

    private fun readRemoteCache(isNightTheme: Boolean): List<Entry> {
        val file = remoteCacheFile(isNightTheme)
        if (!file.exists()) return emptyList()
        return GSON.fromJsonArray<Package>(file.readText()).getOrDefault(emptyList())
            .filter { it.isNightTheme == isNightTheme }
            .map { pkg ->
                Entry(pkg.copy(config = null), Source.REMOTE, remoteUpdatedAt = pkg.updatedAt)
            }
    }

    private fun writeRemoteCache(isNightTheme: Boolean, entries: List<Entry>) {
        val packages = entries.map {
            it.packageInfo.copy(
                config = null,
                updatedAt = it.remoteUpdatedAt.takeIf { time -> time > 0L } ?: it.packageInfo.updatedAt
            )
        }
        remoteCacheFile(isNightTheme).writeText(GSON.toJson(packages))
    }

    private fun readPackage(dir: File): Package? {
        val file = File(dir, packageFileName)
        if (!file.exists()) return null
        return GSON.fromJsonObject<Package>(file.readText()).getOrNull()
    }

    private fun peekPackage(zipFile: File): Package {
        val unzipDir = tempDir.getFile("peek_${System.currentTimeMillis()}").apply {
            if (exists()) FileUtils.delete(this, deleteRootDir = true)
            mkdirs()
        }
        return try {
            ZipUtils.unZipToPath(zipFile, unzipDir) { it.endsWith(packageFileName) }
            val packageFile = unzipDir.walkTopDown().firstOrNull { it.isFile && it.name == packageFileName }
                ?: throw IllegalArgumentException("未找到主题配置文件")
            GSON.fromJsonObject<Package>(packageFile.readText()).getOrThrow()
        } finally {
            FileUtils.delete(unzipDir, deleteRootDir = true)
        }
    }

    private fun importZipInternal(zipFile: File, remoteUpdatedAt: Long): Entry {
        val unzipDir = tempDir.getFile("import_${System.currentTimeMillis()}").apply {
            if (exists()) FileUtils.delete(this, deleteRootDir = true)
            mkdirs()
        }
        ZipUtils.unZipToPath(zipFile, unzipDir)
        val packageFile = unzipDir.walkTopDown().firstOrNull { it.isFile && it.name == packageFileName }
            ?: throw IllegalArgumentException("未找到主题配置文件")
        val pkg = GSON.fromJsonObject<Package>(packageFile.readText()).getOrThrow()
        val dirName = pkg.dirName.ifBlank { pkg.name.normalizeFileName() }
        val targetDir = localDir(pkg.isNightTheme, dirName)
        if (targetDir.exists()) {
            FileUtils.delete(targetDir, deleteRootDir = true)
        }
        targetDir.mkdirs()
        packageFile.parentFile?.copyRecursively(targetDir, overwrite = true)
        val restoredPackage = readPackage(targetDir) ?: pkg
        val targetPackage = if (remoteUpdatedAt == 0L) {
            restoredPackage.copy(updatedAt = System.currentTimeMillis())
        } else {
            restoredPackage
        }
        File(targetDir, packageFileName).writeText(GSON.toJson(targetPackage))
        ThemeConfig.addConfig(resolveConfigPaths(targetPackage, targetDir))
        return Entry(targetPackage, Source.LOCAL, localDir = targetDir, remoteUpdatedAt = remoteUpdatedAt)
    }

    private fun copyAssetsIntoPackage(
        config: ThemeConfig.Config,
        dir: File,
        isNightTheme: Boolean
    ): ThemeConfig.Config {
        val background = copyAsset(config.backgroundImgPath, dir, mainBackgroundPrefix)
        val bookInfo = copyAsset(
            config.bookInfoBackgroundImgPath,
            dir,
            bookInfoBackgroundPrefix
        )
        val uiFont = copyAsset(config.uiFontPath, dir, uiFontPrefix, keepOriginalName = true)
        val titleFont = copyAsset(config.titleFontPath, dir, titleFontPrefix, keepOriginalName = true)
        return config.copy(
            backgroundImgPath = background,
            bookInfoBackgroundImgPath = bookInfo,
            uiFontPath = uiFont,
            titleFontPath = titleFont
        )
    }

    private fun copyAsset(
        path: String?,
        dir: File,
        prefix: String,
        keepOriginalName: Boolean = false
    ): String? {
        if (path.isNullOrBlank()) {
            deletePackagedAssets(dir, prefix)
            return path
        }
        if (path.startsWith("http", ignoreCase = true)) {
            deletePackagedAssets(dir, prefix)
            return path
        }
        if (path.startsWith("content://", ignoreCase = true)) {
            return runCatching {
                val uri = Uri.parse(path)
                val name = DocumentFile.fromSingleUri(appCtx, uri)?.name.orEmpty()
                val target = File(dir, packageAssetName(prefix, name, keepOriginalName))
                appCtx.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: return path
                deletePackagedAssets(dir, prefix, target)
                target.name
            }.getOrDefault(path)
        }
        val source = File(path)
        if (!source.exists()) {
            return findPackagedAssetByPrefix(dir, prefix)?.name ?: path
        }
        if (source.parentFile?.canonicalFile == dir.canonicalFile && source.name.startsWith(prefix)) {
            deletePackagedAssets(dir, prefix, source)
            return source.name
        }
        val target = File(dir, packageAssetName(prefix, source.name, keepOriginalName))
        if (source.canonicalFile == target.canonicalFile) {
            deletePackagedAssets(dir, prefix, target)
            return target.name
        }
        source.copyTo(target, overwrite = true)
        deletePackagedAssets(dir, prefix, target)
        return target.name
    }

    private fun deletePackagedAssets(dir: File, prefix: String, keepFile: File? = null) {
        val keepCanonical = keepFile?.takeIf { it.exists() }?.canonicalFile
        dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(prefix) }
            ?.filter { keepCanonical == null || it.canonicalFile != keepCanonical }
            ?.forEach { it.delete() }
    }

    private fun findPackagedAssetByPrefix(dir: File, prefix: String): File? {
        return dir.listFiles()?.firstOrNull { it.isFile && it.name.startsWith(prefix) }
    }

    private fun packageAssetName(prefix: String, sourceName: String, keepOriginalName: Boolean): String {
        val suffix = sourceName.substringAfterLast('.', "")
            .takeIf { it.isNotBlank() }
            ?.let { ".$it" }
            .orEmpty()
        if (!keepOriginalName) {
            return "$prefix$suffix"
        }
        val normalizedName = sourceName.normalizeFileName()
        if (normalizedName.startsWith("$prefix.")) {
            return "$prefix$suffix"
        }
        val cleanName = normalizedName.removePrefix("${prefix}_")
        return if (cleanName.isBlank()) {
            "$prefix$suffix"
        } else {
            "${prefix}_${cleanName}"
        }
    }

    private fun resolveConfigPaths(pkg: Package, dir: File): ThemeConfig.Config {
        val config = pkg.config ?: ThemeConfig.Config(
            themeName = pkg.name,
            isNightTheme = pkg.isNightTheme,
            primaryColor = if (pkg.isNightTheme) "#252528" else defaultDayPrimary,
            accentColor = "#E53935",
            backgroundColor = if (pkg.isNightTheme) "#212121" else "#F5F5F5",
            bottomBackground = if (pkg.isNightTheme) "#303030" else "#EEEEEE",
            transparentNavBar = true,
            backgroundImgPath = null,
            backgroundImgBlur = 0
        )
        return normalizeLegacyDefaultDayPrimary(config).copy(
            themeName = pkg.name,
            isNightTheme = pkg.isNightTheme,
            backgroundImgPath = resolvePath(config.backgroundImgPath, dir),
            bookInfoBackgroundImgPath = resolvePath(config.bookInfoBackgroundImgPath, dir),
            uiFontPath = resolvePath(config.uiFontPath, dir),
            titleFontPath = resolvePath(config.titleFontPath, dir)
        )
    }

    private fun normalizeLegacyDefaultDayPrimary(config: ThemeConfig.Config): ThemeConfig.Config {
        if (config.isNightTheme) return config
        val isLegacyDefault = runCatching {
            config.primaryColor.toColorInt() == legacyDefaultDayPrimary
        }.getOrDefault(false)
        if (!isLegacyDefault) return config
        return config.copy(primaryColor = defaultDayPrimary)
    }

    private fun resolvePath(path: String?, dir: File): String? {
        if (path.isNullOrBlank() || path.startsWith("http", ignoreCase = true)) return path
        val file = File(path)
        if (file.isAbsolute) {
            if (isReadableOwnFile(file)) return path
            findPackagedAsset(dir, file.name)?.let { return it.absolutePath }
            findPackagedAssetByPrefix(dir, file.name.substringBeforeLast('.', file.name))?.let {
                return it.absolutePath
            }
            return null
        }
        val packagedFile = File(dir, path)
        if (isReadableOwnFile(packagedFile)) return packagedFile.absolutePath
        findPackagedAsset(dir, file.name)?.let { return it.absolutePath }
        return packagedFile.absolutePath
    }

    private fun isReadableOwnFile(file: File): Boolean {
        if (!file.isFile) return false
        if (isOtherAppExternalDataPath(file.absolutePath)) return false
        return runCatching {
            FileInputStream(file).use { true }
        }.getOrDefault(false)
    }

    private fun isOtherAppExternalDataPath(path: String): Boolean {
        val marker = "/Android/data/"
        val normalized = path.replace('\\', '/')
        val start = normalized.indexOf(marker, ignoreCase = true)
        if (start < 0) return false
        val packageStart = start + marker.length
        val packageEnd = normalized.indexOf('/', packageStart).takeIf { it >= 0 } ?: normalized.length
        val ownerPackage = normalized.substring(packageStart, packageEnd)
        return ownerPackage.isNotBlank() && ownerPackage != appCtx.packageName
    }

    private fun findPackagedAsset(dir: File, fileName: String): File? {
        if (fileName.isBlank()) return null
        val lowerName = fileName.lowercase()
        return dir.walkTopDown().firstOrNull { file ->
            file.isFile && file.name.lowercase() == lowerName
        }
    }

    fun localDir(isNightTheme: Boolean, dirName: String): File {
        return typeDir(isNightTheme).getFile(dirName)
    }

    private val tempDir: File
        get() = rootDir.getFile("temp").apply {
            if (!exists()) mkdirs()
        }

    private val remoteCacheDir: File
        get() = rootDir.getFile("remote_cache").apply {
            if (!exists()) mkdirs()
        }

    private fun typeDir(isNightTheme: Boolean): File {
        return rootDir.getFile(if (isNightTheme) "night" else "day").apply {
            if (!exists()) mkdirs()
        }
    }

    data class Entry(
        val packageInfo: Package,
        val source: Source,
        val localDir: File? = null,
        val remoteUpdatedAt: Long = 0L
    ) {
        val dirName: String get() = packageInfo.dirName
    }

    @Keep
    data class Package(
        val name: String,
        val dirName: String,
        val isNightTheme: Boolean,
        val updatedAt: Long,
        val config: ThemeConfig.Config?
    )
    enum class Source {
        LOCAL,
        REMOTE,
        BOTH
    }

    // region MD3 主题包导入（兼容 MD3-main 分支导出格式）

    /** MD3 包清单文件名 */
    private const val md3ManifestFileName = "manifest.json"

    /** MD3 清单最大字节数，防止恶意超大文件 */
    private const val maxMd3ManifestBytes = 2L * 1024 * 1024

    /**
     * MD3-main 分支主题包清单。
     * 仅声明 SK 需要的字段；GSON 自动忽略导航图标、封面图集等未声明成员。
     * 注意：GSON 经 Unsafe 实例化，缺失成员不会应用 Kotlin 默认值，
     * 故引用型字段一律声明为可空并在使用处显式兜底。
     */
    @Keep
    private data class Md3Manifest(
        @SerializedName("formatVersion") val formatVersion: Int = 0,
        @SerializedName("name") val name: String? = null,
        @SerializedName("config") val config: Md3ThemeData? = null,
        @SerializedName("assets") val assets: Map<String, String>? = null
    )

    /**
     * MD3 扁平偏好参数中的颜色与背景部分。
     * 颜色为 ARGB Int；MD3 颜色无 alpha 语义，转换时丢弃 alpha。
     */
    @Keep
    private data class Md3ThemeData(
        @SerializedName("isPureBlack") val isPureBlack: Boolean = false,
        @SerializedName("themeColor") val themeColor: Int = 0,
        @SerializedName("secondaryThemeColor") val secondaryThemeColor: Int = 0,
        @SerializedName("themeBackgroundColor") val themeBackgroundColor: Int = 0,
        @SerializedName("labelContainerColor") val labelContainerColor: Int = 0,
        @SerializedName("themeColorNight") val themeColorNight: Int = 0,
        @SerializedName("secondaryThemeColorNight") val secondaryThemeColorNight: Int = 0,
        @SerializedName("themeBackgroundColorNight") val themeBackgroundColorNight: Int = 0,
        @SerializedName("labelContainerColorNight") val labelContainerColorNight: Int = 0,
        @SerializedName("bgImageLight") val bgImageLight: String? = null,
        @SerializedName("bgImageDark") val bgImageDark: String? = null,
        @SerializedName("bgImageBlurring") val bgImageBlurring: Int = 0,
        @SerializedName("bgImageNBlurring") val bgImageNBlurring: Int = 0,
        @SerializedName("fontScale") val fontScale: Int = 10
    )

    /**
     * 嗅探 zip 是否为 MD3-main 格式主题包：
     * 内含 manifest.json 且具备 formatVersion 与 config 成员即判定成立。
     * 非 zip 或结构不符一律返回 false，交由原生 theme.json 流程报错。
     */
    private fun isMd3ThemePackage(zipFile: File): Boolean {
        runCatching {
            ZipFile(zipFile).use { zip ->
                val entry = zip.getEntry(md3ManifestFileName) ?: return false
                // size 为 -1 表示未知（流式写入），不能据此排除；仅拒绝明确超限
                if (entry.size > maxMd3ManifestBytes) return false
                val json = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                val root = JsonParser.parseString(json).asJsonObject
                return root.has("formatVersion") && root.has("config")
            }
        }.onFailure { return false }
        return false
    }

    private fun readMd3Manifest(zipFile: File): Md3Manifest {
        ZipFile(zipFile).use { zip ->
            val entry = zip.getEntry(md3ManifestFileName)
                ?: throw IllegalArgumentException(appCtx.getString(R.string.md3_theme_invalid))
            require(entry.size in 0..maxMd3ManifestBytes) {
                appCtx.getString(R.string.md3_theme_invalid)
            }
            val json = zip.getInputStream(entry).bufferedReader().use { it.readText() }
            val manifest = GSON.fromJsonObject<Md3Manifest>(json).getOrNull()
                ?.takeIf { it.config != null }
                ?: throw IllegalArgumentException(appCtx.getString(R.string.md3_theme_invalid))
            return manifest
        }
    }

    /**
     * 导入 MD3 主题包。
     * 一个 MD3 包同时携带日/夜两套配色，SK 一个主题包只承载一个日夜形态，
     * 因此拆分为同名两份分别落入 day / night 目录；重复导入按目录重建语义覆盖更新。
     */
    private fun importMd3Internal(zipFile: File): List<Entry> {
        val manifest = readMd3Manifest(zipFile)
        val themeName = md3ThemeName(manifest.name, zipFile.name)
        val entries = mutableListOf<Entry>()
        for (isNight in listOf(false, true)) {
            val dirName = themeName.normalizeFileName().ifBlank { "md3_${System.currentTimeMillis()}" }
            val targetDir = localDir(isNight, dirName)
            if (targetDir.exists()) {
                FileUtils.delete(targetDir, deleteRootDir = true)
            }
            targetDir.mkdirs()
            val config = buildMd3Config(manifest, isNight, zipFile, targetDir, themeName)
            val pkg = Package(
                name = themeName,
                dirName = dirName,
                isNightTheme = isNight,
                updatedAt = System.currentTimeMillis(),
                config = config
            )
            File(targetDir, packageFileName).writeText(GSON.toJson(pkg))
            ThemeConfig.addConfig(resolveConfigPaths(pkg, targetDir))
            entries.add(Entry(pkg, Source.LOCAL, localDir = targetDir, remoteUpdatedAt = 0L))
        }
        return entries
    }

    /** 主题命名回退链：清单 name → zip 文件名（排除时间戳等无意义名称）→ 固定默认值 */
    private fun md3ThemeName(name: String?, zipFileName: String): String {
        name?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        runCatching {
            val base = zipFileName.substringAfterLast(File.separatorChar).substringBeforeLast('.')
            base.takeIf {
                it.isNotBlank() && !it.all(Char::isDigit) && !it.matches(Regex("import_\\d+"))
            }?.let { return it }
        }
        return "MD3主题"
    }

    /**
     * 将 MD3 参数映射为 SK 标准颜色主题配置。
     * 背景图提取后以 background 前缀资产规范存入包目录，config 记相对路径，
     * 由 resolveConfigPaths 在读取时解析为绝对路径。
     */
    private fun buildMd3Config(
        manifest: Md3Manifest,
        isNight: Boolean,
        zipFile: File,
        targetDir: File,
        themeName: String
    ): ThemeConfig.Config {
        // readMd3Manifest 已校验 config 非空；assets 缺失时按空映射处理
        val data = manifest.config ?: Md3ThemeData()
        val assets = manifest.assets.orEmpty()
        val assetRef = if (isNight) {
            assets["background.dark"]
                ?: assets["bgImageDark"]
                ?: data.bgImageDark
        } else {
            assets["background.light"]
                ?: assets["bgImageLight"]
                ?: data.bgImageLight
        }
        var backgroundPath: String? = null
        if (!assetRef.isNullOrBlank()) {
            extractMd3Asset(zipFile, assetRef, targetDir, mainBackgroundPrefix)?.let { fileName ->
                backgroundPath = fileName
            }
        }
        return ThemeConfig.Config(
            themeName = themeName,
            isNightTheme = isNight,
            primaryColor = md3ColorToHex(if (isNight) data.themeColorNight else data.themeColor),
            accentColor = md3ColorToHex(if (isNight) data.secondaryThemeColorNight else data.secondaryThemeColor),
            backgroundColor = md3ColorToHex(if (isNight) data.themeBackgroundColorNight else data.themeBackgroundColor),
            bottomBackground = md3ColorToHex(if (isNight) data.labelContainerColorNight else data.labelContainerColor),
            transparentNavBar = data.isPureBlack,
            backgroundImgPath = backgroundPath,
            backgroundImgBlur = (if (isNight) data.bgImageNBlurring else data.bgImageBlurring)
                .coerceIn(0, 25),
            // GSON 对缺失成员填 0 而非声明默认值 10；0 在 SK 语义中是非法缩放，
            // 因此仅采纳 1..16 区间内的非默认值，避免把用户系统缩放写成 0
            fontScale = data.fontScale.takeIf { it in 1..16 && it != 10 }
        )
    }

    /**
     * 提取 MD3 资产引用并写入包目录。
     * 引用值有三种形态，按序尝试：zip 内路径（精确命中 → 以路径段结尾匹配）、Base64 数据。
     * 成功返回写入的资产文件名，失败返回 null（背景缺失不阻断导入）。
     */
    private fun extractMd3Asset(
        zipFile: File,
        ref: String,
        targetDir: File,
        prefix: String
    ): String? {
        val normalizedRef = ref.replace('\\', '/')
        var tempFile: File? = null
        runCatching {
            ZipFile(zipFile).use { zip ->
                var entry = zip.getEntry(normalizedRef)
                if (entry == null || entry.isDirectory) {
                    entry = zip.entries().asSequence()
                        .firstOrNull { !it.isDirectory && it.name.endsWith("/$normalizedRef") }
                }
                entry?.let {
                    val tmp = tempDir.getFile("md3_asset_${System.nanoTime()}")
                    zip.getInputStream(it).use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output) }
                    }
                    tempFile = tmp
                }
            }
        }
        if (tempFile == null && !ref.contains('/')) {
            runCatching {
                EncoderUtils.base64DecodeToByteArray(ref)?.let { bytes ->
                    val tmp = tempDir.getFile("md3_asset_${System.nanoTime()}")
                    tmp.writeBytes(bytes)
                    tempFile = tmp
                }
            }
        }
        val source = tempFile ?: return null
        val suffix = source.extension.takeIf { it.isNotBlank() }?.let { ".$it" } ?: ".jpg"
        val target = File(targetDir, "$prefix$suffix")
        return runCatching {
            source.copyTo(target, overwrite = true)
            target.name
        }.getOrNull().also {
            runCatching { source.delete() }
        }
    }

    /** ARGB Int 转 #RRGGBB 十六进制字符串（丢弃 alpha，MD3 颜色本无 alpha 语义） */
    private fun md3ColorToHex(color: Int): String {
        val rgb = color and 0xFFFFFF
        return String.format(Locale.US, "#%06X", rgb)
    }

    // endregion

}
