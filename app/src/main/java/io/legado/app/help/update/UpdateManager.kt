package io.legado.app.help.update

import android.content.Context
import androidx.annotation.Keep
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.PreferKey
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.lib.dialogs.alert
import io.legado.app.model.Download
import io.legado.app.utils.GSON
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 检查 GitHub Release 上的新版本并提示下载安装
 * Release 的 tag 需使用 versionCode 作为标签（例如 10723 或 v10723）
 */
object UpdateManager {

    private const val GITHUB_API = "https://api.github.com/repos/skxingyu/legado-sk/releases?per_page=30"
    private const val MAX_BODY_LEN = 2000

    @Keep
    data class GitHubRelease(
        val tag_name: String? = null,
        val prerelease: Boolean = false,
        val body: String? = null,
        val assets: List<GitHubAsset> = emptyList()
    )

    @Keep
    data class GitHubAsset(
        val name: String? = null,
        val browser_download_url: String? = null
    )

    private data class UpdateInfo(
        val tagName: String,
        val versionCode: Long,
        val fileName: String,
        val downloadUrl: String,
        val body: String?
    )

    private data class ReleaseCandidate(
        val release: GitHubRelease,
        val versionCode: Long,
        // versionName 中的编译时刻(MMddHH): 同 versionCode 时用于区分新旧,
        // 保证 pre 转正的同号正式版能覆盖已安装的同号 pre 版
        val versionKey: Long,
        val asset: GitHubAsset
    )

    /**
     * 检查更新，有新版则弹出更新对话框
     * @param showUpToDate 无更新时是否提示"已是最新"
     * @param showError 检查失败时是否提示
     * @return 是否发现了新版本
     */
    suspend fun checkUpdate(
        context: Context,
        showUpToDate: Boolean = false,
        showError: Boolean = false
    ): Boolean {
        return try {
            val info = withContext(Dispatchers.IO) { fetchLatestRelease(context) }
            if (info == null) {
                if (showUpToDate) withContext(Dispatchers.Main) {
                    context.toastOnUi(R.string.update_none)
                }
                return false
            }
            withContext(Dispatchers.Main) { showUpdateDialog(context, info) }
            true
        } catch (e: Exception) {
            if (showError) withContext(Dispatchers.Main) {
                context.toastOnUi(R.string.update_check_failed)
            }
            false
        }
    }

    private suspend fun fetchLatestRelease(context: Context): UpdateInfo? {
        val includePre = context.getPrefBoolean(PreferKey.updateCheckPre)
        val resp = okHttpClient.newCallStrResponse(retry = 1) {
            url(GITHUB_API)
            header("Accept", "application/vnd.github+json")
            header("User-Agent", "LegadoSK/${AppConst.appInfo.versionName}")
        }
        val body = resp.body ?: throw IOException("empty response")
        val releases = GSON.fromJson(body, Array<GitHubRelease>::class.java)?.toList() ?: emptyList()
        // pre 开关决定是否接受预发布版本
        val eligible = releases.filter { includePre || !it.prerelease }
        // versionCode 必须从 APK 资产文件名解析（legado_app_3.26.081303_10539.apk -> 10539），
        // 不能从 tag 解析：tag 是版本名格式（v3.26.081303c），里面的数字是 MMddHH，
        // 提取出来会是 81303 这种假版本号，永远大于真实 versionCode，导致误报"发现新版本"。
        val candidates = eligible.mapNotNull { release ->
            val asset = release.assets.firstOrNull { it.name?.endsWith(".apk", true) == true }
                ?: return@mapNotNull null
            val code = parseVersionCodeFromAssetName(asset.name) ?: return@mapNotNull null
            val versionKey = parseVersionKeyFromAssetName(asset.name)
            ReleaseCandidate(release, code, versionKey, asset)
        }
        // 候选排序三级键: versionCode > 正式版优先(pre 与正式同级时正式版胜出) > versionName 编译时刻;
        // 这样同 versionCode 的 pre 转正场景, 正式版会排在已发布的 pre 之前被选中
        val best = candidates.maxWithOrNull(
            compareBy(
                { it.versionCode },
                { it.release.prerelease == false },
                { it.versionKey }
            )
        ) ?: return null
        // 更新判定: versionCode 更高, 或 versionCode 相同但线上编译时刻比本地新
        // (覆盖「已安装同号 pre 版, 线上发布同号正式版」的更新检测);
        // 若本地就是最新的同号正式版, 两个条件都不满足, 不会误报
        val isNewer = best.versionCode > AppConst.appInfo.versionCode ||
            (
                best.versionCode == AppConst.appInfo.versionCode &&
                    best.versionKey > versionNameSortKey(AppConst.appInfo.versionName)
                )
        if (!isNewer) return null
        val originalUrl = best.asset.browser_download_url ?: return null
        return UpdateInfo(
            tagName = best.release.tag_name ?: best.versionCode.toString(),
            versionCode = best.versionCode,
            fileName = best.asset.name ?: "legado-update.apk",
            downloadUrl = resolveAcceleratedUrl(context, originalUrl),
            body = best.release.body
        )
    }

    /**
     * 从 APK 资产文件名解析 versionCode：
     * legado_sk_3.26.081303_10539.apk -> 10539
     * legado_sk_3.26.082015c_10014_arm64-v8a.apk -> 10014（兼容末尾 ABI 后缀）
     * 不能从 tag 解析：tag 是版本名格式（v3.26.082015c），里面的数字是 MMddHH，
     * 提取出来会是假版本号，永远大于真实 versionCode，导致误报"发现新版本"。
     */
    private fun parseVersionCodeFromAssetName(name: String?): Long? {
        name ?: return null
        return Regex("_(\\d+)(?:_[\\w-]+)?\\.apk$", RegexOption.IGNORE_CASE)
            .find(name)?.groupValues?.get(1)?.toLongOrNull()
    }

    /**
     * 从 APK 资产文件名解析 versionName 编译时刻键(MMddHH 数值):
     * legado_sk_3.26.082507c_10020_arm64-v8a.apk -> 82507
     * legado_sk_3.26.081303_10539.apk -> 81303
     * 解析失败返回 Long.MIN_VALUE, 该候选仅失去同号次级比较能力, 主判定(versionCode)不受影响
     */
    private fun parseVersionKeyFromAssetName(name: String?): Long {
        val versionName = name?.let {
            Regex("_([0-9]+(?:\\.[0-9]+)+)[cC]?_\\d+").find(it)?.groupValues?.get(1)
        }
        return versionNameSortKey(versionName)
    }

    /**
     * versionName -> 编译时刻数值键: "3.26.082507c" -> 82507
     * 格式固定为 3.26.MMddHH[c], MMddHH 固定六位, 数值比较与时间先后一致
     */
    private fun versionNameSortKey(versionName: String?): Long {
        return versionName
            ?.substringAfterLast('.')
            ?.trimEnd('c', 'C')
            ?.toLongOrNull()
            ?: Long.MIN_VALUE
    }

    /**
     * 根据设置的加速源改写下载地址
     */
    fun resolveAcceleratedUrl(context: Context, originalUrl: String): String {
        return when (context.getPrefString(PreferKey.updateAccelerator) ?: "ghfast") {
            "ghproxy" -> "https://ghproxy.net/" + originalUrl
            "gh-proxy" -> "https://gh-proxy.com/" + originalUrl
            "ghfast" -> "https://ghfast.top/" + originalUrl
            "custom" -> {
                val prefix = context.getPrefString(PreferKey.updateAcceleratorCustom)
                    ?.trim().orEmpty()
                when {
                    prefix.isBlank() -> originalUrl
                    prefix.endsWith("/") -> prefix + originalUrl
                    else -> "$prefix/" + originalUrl
                }
            }

            else -> originalUrl
        }
    }

    private fun showUpdateDialog(context: Context, info: UpdateInfo) {
        context.alert(
            context.getString(R.string.update_dialog_title) + " v" + info.tagName,
            info.body?.trim().orEmpty().take(MAX_BODY_LEN).ifBlank {
                context.getString(R.string.update_check_on_start_desc)
            }
        ) {
            positiveButton(R.string.update_download) {
                Download.start(context, info.downloadUrl, info.fileName)
                context.toastOnUi("开始下载更新包")
            }
            cancelButton()
        }
    }

}
