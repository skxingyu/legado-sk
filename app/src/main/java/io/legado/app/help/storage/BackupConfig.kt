package io.legado.app.help.storage

import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.model.BookCover
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import splitties.init.appCtx

/**
 * 备份配置
 */
@Suppress("ConstPropertyName")
object BackupConfig {

    private val ignoreConfigPath = FileUtils.getPath(appCtx.filesDir, "restoreIgnore.json")
    val ignoreConfig: HashMap<String, Boolean> by lazy {
        val file = FileUtils.createFileIfNotExist(ignoreConfigPath)
        val json = file.readText()
        GSON.fromJsonObject<HashMap<String, Boolean>>(json).getOrNull() ?: hashMapOf()
    }

    private const val readConfigKey = "readConfig"
    private const val themeConfigKey = "themeConfig"
    private const val coverConfigKey = "coverConfig"
    private const val localBookKey = "localBook"

    //配置忽略key
    val ignoreKeys = arrayOf(
        readConfigKey,
        PreferKey.themeMode,
        themeConfigKey,
        coverConfigKey,
        PreferKey.bookshelfLayout,
        PreferKey.showRss,
        PreferKey.threadCount,
        localBookKey
    )

    //配置忽略标题
    val ignoreTitle = arrayOf(
        appCtx.getString(R.string.read_config),
        appCtx.getString(R.string.theme_mode),
        appCtx.getString(R.string.theme_config),
        appCtx.getString(R.string.cover_config),
        appCtx.getString(R.string.bookshelf_layout),
        appCtx.getString(R.string.show_rss),
        appCtx.getString(R.string.thread_count),
        appCtx.getString(R.string.local_book)
    )

    //自动忽略keys
    private val ignorePrefKeys = arrayOf(
        PreferKey.defaultCover,
        PreferKey.defaultCoverDark,
        PreferKey.backupPath,
        PreferKey.defaultBookTreeUri,
        PreferKey.webDavDeviceName,
        PreferKey.launcherIcon,
        PreferKey.bitmapCacheSize,
        PreferKey.webServiceWakeLock,
        PreferKey.readAloudWakeLock,
        PreferKey.audioPlayWakeLock,
        PreferKey.uiLayoutAlpha
    )

    //阅读配置
    private val readPrefKeys = arrayOf(
        PreferKey.readStyleSelect,
        PreferKey.comicStyleSelect,
        PreferKey.shareLayout,
        PreferKey.hideStatusBar,
        PreferKey.hideNavigationBar,
        PreferKey.autoReadSpeed,
        PreferKey.clickActionTL,
        PreferKey.clickActionTC,
        PreferKey.clickActionTR,
        PreferKey.clickActionML,
        PreferKey.clickActionMC,
        PreferKey.clickActionMR,
        PreferKey.clickActionBL,
        PreferKey.clickActionBC,
        PreferKey.clickActionBR,
        PreferKey.readAloudDoubleTapTimeout,
        PreferKey.readAloudScrollFollowTimeout,
        PreferKey.readAloudProgressPollInterval
    )

    private val themePrefKeys = arrayOf(
        PreferKey.cPrimary,
        PreferKey.cAccent,
        PreferKey.cBackground,
        PreferKey.cBBackground,
        PreferKey.bgImage,
        PreferKey.bgImageBlurring,
        PreferKey.bookInfoBgImage,
        PreferKey.bookInfoBgImageBlurring,
        PreferKey.uiCornerScale,
        PreferKey.uiLayoutAlpha,
        PreferKey.bookshelfCoverAlpha,
        PreferKey.uiCornerSearchFollow,
        PreferKey.uiCornerReplyFollow,
        PreferKey.tNavBar,
        PreferKey.cNPrimary,
        PreferKey.cNAccent,
        PreferKey.cNBackground,
        PreferKey.cNBBackground,
        PreferKey.bgImageN,
        PreferKey.bgImageNBlurring,
        PreferKey.bookInfoBgImageN,
        PreferKey.bookInfoBgImageNBlurring,
        PreferKey.tNavBarN
    )

    private val coverPrefKeys = arrayOf(
        PreferKey.useDefaultCover,
        PreferKey.loadCoverOnlyWifi,
        PreferKey.loadCoverHighQuality,
        PreferKey.coverShowName,
        PreferKey.coverShowAuthor,
        PreferKey.coverShowNameN,
        PreferKey.coverShowAuthorN
    )

    fun keyIsNotIgnore(key: String): Boolean {
        return when {
            ignorePrefKeys.contains(key) -> false
            ignoreReadConfig && readPrefKeys.contains(key) -> false
            ignoreThemeConfig && themePrefKeys.contains(key) -> false
            ignoreCoverConfig && coverPrefKeys.contains(key) -> false
            PreferKey.themeMode == key && ignoreThemeMode -> false
            PreferKey.bookshelfLayout == key && ignoreBookshelfLayout -> false
            PreferKey.showRss == key && ignoreShowRss -> false
            PreferKey.threadCount == key && ignoreThreadCount -> false
            else -> true
        }
    }

    val ignoreReadConfig: Boolean
        get() = ignoreConfig[readConfigKey] == true
    private val ignoreThemeMode: Boolean
        get() = ignoreConfig[PreferKey.themeMode] == true
    private val ignoreThemeConfig: Boolean
        get() = ignoreConfig[themeConfigKey] == true
    private val ignoreCoverConfig: Boolean
        get() = ignoreConfig[coverConfigKey] == true
    private val ignoreBookshelfLayout: Boolean
        get() = ignoreConfig[PreferKey.bookshelfLayout] == true
    private val ignoreShowRss: Boolean
        get() = ignoreConfig[PreferKey.showRss] == true
    private val ignoreThreadCount: Boolean
        get() = ignoreConfig[PreferKey.threadCount] == true
    val ignoreLocalBook: Boolean
        get() = ignoreConfig[localBookKey] == true

    fun saveIgnoreConfig() {
        val json = GSON.toJson(ignoreConfig)
        FileUtils.createFileIfNotExist(ignoreConfigPath).writeText(json)
    }

}

/**
 * 一个可整体勾选的备份单元（如「主题配置」）。
 *
 * [targets] 是 [Backup] 打包时使用的目标名（文件名或外部目录名），
 * 与恢复侧按项目删除内容时使用的是同一批字符串 —— 两侧口径必须逐字一致，
 * 否则会出现「备份勾了但删不掉/恢复不了」的静默错位。
 */
internal data class BackupTargetItem(
    val key: String,
    val title: String,
    val targets: List<String>
)

/**
 * 备份/恢复项目清单的唯一来源。
 *
 * ⚠️ 备份（选取打包内容）与恢复（选取恢复内容、按项目删除备份文件）**必须共用这一份清单**：
 * 分成两份就会漂移 —— 备份能选到某个项目、恢复侧却不认识它，只能静默丢弃。
 *
 * ⚠️ [BackupTargetItem.key] 一旦发布就是**存量设备上已保存选择**的持久化键，
 * 只能新增、不能改名或复用（改名＝用户既有选择被静默重置为默认）。
 */
@Suppress("ConstPropertyName")
internal object BackupItems {

    val themePackagesDirName = "themePackages"

    /**
     * 导航栏图标目录名。
     *
     * ⚠️ 必须是**字面量**，不能写 `NavigationBarIconConfig.rootDir.name`：
     * `rootDir` 在类初始化时读 `appCtx`，会让整份清单无法在 JVM 单测中求值
     * （`BackupItems.all` 是唯一可被单测直接校验的清单契约）。
     * `navigationBarIconDirNameStaysInSync` 断言二者一致。
     */
    const val navigationBarDirName = "navigationBarPackages"

    val all: List<BackupTargetItem>
        get() = listOf(
            BackupTargetItem(
                "bookshelf",
                "书架",
                listOf("bookshelf.json", "bookmark.json", "bookGroup.json", "covers")
            ),
            BackupTargetItem("bookSource", "书源", listOf("bookSource.json", "sourceSub.json")),
            BackupTargetItem("rss", "RSS", listOf("rssSources.json", "rssStar.json")),
            BackupTargetItem("replaceRule", "替换规则", listOf("replaceRule.json")),
            BackupTargetItem(
                "readRecord",
                "阅读记录",
                listOf("readRecord.json", "readRecordDaily.json", "readRecordGoalAvatar")
            ),
            BackupTargetItem("searchHistory", "搜索记录", listOf("searchHistory.json")),
            BackupTargetItem("txtTocRule", "TXT 目录规则", listOf("txtTocRule.json")),
            BackupTargetItem("httpTTS", "朗读引擎", listOf("httpTTS.json")),
            BackupTargetItem("dictRule", "字典规则", listOf("dictRule.json")),
            BackupTargetItem("keyboardAssists", "键盘助手", listOf("keyboardAssists.json")),
            BackupTargetItem("servers", "服务器配置", listOf("servers.json")),
            BackupTargetItem("directLink", "直链上传", listOf(DirectLinkUpload.ruleFileName)),
            BackupTargetItem(
                "readConfig",
                "阅读配置",
                listOf(
                    ReadBookConfig.configFileName,
                    ReadBookConfig.shareConfigFileName,
                    "bg",
                    "font",
                    PreferKey.bgImage,
                    PreferKey.bgImageN,
                    PreferKey.bookInfoBgImage,
                    PreferKey.bookInfoBgImageN
                )
            ),
            BackupTargetItem(
                "themeConfig",
                "主题配置",
                listOf(
                    ThemeConfig.configFileName,
                    themePackagesDirName,
                    BackupThemePackageDedupe.manifestFileName
                )
            ),
            BackupTargetItem("navigationBar", "导航栏图标", listOf(navigationBarDirName)),
            BackupTargetItem("coverRule", "封面规则", listOf(BookCover.configFileName)),
            BackupTargetItem("appConfig", "应用设置", listOf("config.xml", "videoConfig.xml")),
            BackupTargetItem(
                "agent",
                "Agent（模式、配置、完整会话、记忆）",
                listOf(io.legado.app.help.agent.AgentBackup.FILE_NAME)
            )
        )
}

/**
 * 「默认备份内容」配置。
 *
 * 备份不再每次弹框询问包含哪些项目，而是按这里保存的结果打包；
 * 恢复侧的「恢复忽略列表」是另一套语义（见 [BackupConfig]），两者互不干扰。
 *
 * ⚠️ 未保存过任何选择时**默认全选**（与旧行为一致）：存量设备升级后备份内容不变，
 * 只有用户主动取消过某些项才会收窄。缺省方向反了（默认不选）会让升级后的第一次
 * 备份变成空包。
 */
@Suppress("ConstPropertyName")
object BackupTargetConfig {

    private val configPath = FileUtils.getPath(appCtx.filesDir, "backupTarget.json")

    /** key → 是否勾选。只保存用户显式改过的项，未出现的项按默认（勾选）处理。 */
    private val selections: MutableMap<String, Boolean> by lazy {
        val file = FileUtils.createFileIfNotExist(configPath)
        GSON.fromJsonObject<HashMap<String, Boolean>>(file.readText()).getOrNull() ?: hashMapOf()
    }

    fun isSelected(key: String): Boolean = selections[key] ?: true

    fun isAllSelected(): Boolean = BackupItems.all.all { isSelected(it.key) }

    fun setSelection(key: String, selected: Boolean) {
        selections[key] = selected
    }

    /**
     * 本次备份应打包的目标名集合。
     *
     * ⚠️ 返回**空集**与 null（旧行为「全部打包」）语义完全不同：
     * 空集代表用户一个项目都没勾，调用方必须据此拒绝备份并提示，
     * 绝不能塌缩成「全打包」或「打个空包」。
     */
    fun selectedTargets(): Set<String> =
        BackupItems.all.filter { isSelected(it.key) }.flatMapTo(hashSetOf()) { it.targets }

    fun save() {
        FileUtils.createFileIfNotExist(configPath).writeText(GSON.toJson(selections))
    }
}
