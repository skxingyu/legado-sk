<div align="center">

> ⚠️ **本应用主要针对阅读、听书体验做优化更改，按个人喜好开发，不喜勿来。**

</div>

<table align="center">
  <tr>
    <td align="center"><img src="docs/photo_2026-08-16_20-07-31.jpg" width="100%" alt="界面截图 1" /></td>
    <td align="center"><img src="docs/photo_2026-08-16_20-07-32.jpg" width="100%" alt="界面截图 2" /></td>
    <td align="center"><img src="docs/photo_2026-08-16_20-08-01.jpg" width="100%" alt="界面截图 3" /></td>
  </tr>
</table>

**好用的话，点个 star，谢谢啦**

## 项目介绍

本项目 fork 自 [legadoC](https://github.com/CCSSNE/legadoC)，在 legadoC 的基础上针对**阅读进度同步**与**朗读（听书）体验**做了针对性优化，按个人使用习惯开发，适合自用或自行打包分发。

感谢 [legadoC](https://github.com/CCSSNE/legadoC) 作者的无私奉献，也感谢上游 [legado](https://github.com/gedoor/legado) 开源项目。

## 主要优化方向

### 一、阅读进度同步增强

- **修复批量拉取提前中断 bug**：WebDAV 多本书进度拉取时，原来因第一本不符合条件就中断整批同步（`return`），现改为仅跳过当前书（`return@forEach`），书架内所有书都能同步到。
- **进度比较改为纯位置比较**：同步判断不再依赖枚举对比，而是直接比较 `durChapterIndex` / `durChapterPos` 位置，进度同步更准确、更符合实际阅读场景。
- **DEBUG 构建也自动同步**：移除 `!BuildConfig.DEBUG` 限制，调试版同样在退出阅读时自动同步/上传进度，方便日常使用与测试。
- **同步开关默认开启**：`阅读进度同步` 相关开关默认打开，开箱即用（需自行配置 WebDAV）。

> 该方向参考了 [legado-E](https://github.com/Luoyacheng/legado-E) 的实现思路，一并致谢。

### 二、朗读（听书）悬浮窗优化

- **阅读界面内隐藏悬浮窗**：朗读时，小说阅读界面内不再显示悬浮窗，避免遮挡正文、干扰阅读；退出阅读界面（回到书架等 App 内页面）后自动显示，方便随时暂停/继续/停止朗读。
- **新增配置项**：`设置 → 朗读配置 → 阅读界面内隐藏悬浮窗`（默认开启），可随时关闭恢复原行为。
- **朗读控制不受影响**：阅读界面内仍可通过朗读菜单暂停/继续/停止，应用退到后台后 App 内悬浮窗按系统规则不显示（不走桌面悬浮窗）。

### 三、构建与分发

- **新增 `sk2` 共存测试版**：`io.legado.app.sk2`（应用名「阅读SK」），可与正式版 `io.legado.app.c` 共存安装，方便新功能先行测试。
- **启用阿里云镜像仓库**：`settings.gradle` 启用 `maven.aliyun.com` 镜像，加快依赖下载与编译速度。

## 安装包

| 文件 | 包名 | 说明 |
|---|---|---|
| `legado_sk_3.26.0816c_10004_arm64-v8a.apk` | `io.legado.app.c` | 正式版（可与旧版覆盖升级） |
| `legado_sk_3.26.0816sk2_10004_arm64-v8a.apk` | `io.legado.app.sk2` | 共存测试版（应用名「阅读SK」） |

> 安装包见 Release 页面；仅提供 `arm64-v8a` 架构。

## 更新日志

完整更新说明见 [CHANGELOG.md](CHANGELOG.md)。
