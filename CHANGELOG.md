# 更新日志 / CHANGELOG

## 3.26.0816c（versionCode 10004）— 2026-08-16

### 一、阅读进度同步增强（移植 legado-E）

- **修复批量拉取提前中断 bug**：`AppWebDav.downloadAllBookProgress` 中 `return` → `return@forEach`，多本书进度拉取不再因第一本不符合条件而中断
- **进度比较改为纯位置比较**：`ReadBook` / `ReadManga` / `ReadBookViewModel` / `ReadMangaViewModel` 的同步判断从 `when(compareWith)` 改为基于 `durChapterIndex` / `durChapterPos` 的位置比较，同步更准确
- **DEBUG 构建也自动同步**：`ReadBookActivity` / `ReadMangaActivity` 的 `onPause` 移除 `!BuildConfig.DEBUG` 限制，调试版同样自动同步/上传进度
- **同步开关默认开启**：`syncBookProgressPlus` 默认值改为 `true`

### 二、朗读悬浮窗优化

- **阅读界面内隐藏悬浮窗**：朗读（听书）时，小说阅读界面（ReadBookActivity）内不再显示悬浮窗，避免遮挡阅读内容；退出阅读界面后在应用内其他页面显示（App 内悬浮窗），应用退到后台不显示
- **新增配置项**：`阅读界面内隐藏悬浮窗`（`readAloudHideFloatingInReadBook`，默认开启），位于 设置 → 朗读配置；依赖「隐藏朗读悬浮窗」关闭时生效，可随时关闭恢复原行为
- **朗读控制不受影响**：阅读界面内仍可通过朗读菜单（ReadAloudDialog）暂停/继续/停止

### 三、其他

- **新增 `sk2` 构建变体**：`io.legado.app.sk2`（应用名「阅读SK」），可与正式版 `io.legado.app.c` 共存安装，方便测试
- **启用阿里云镜像仓库**：`settings.gradle` 启用 `maven.aliyun.com` 镜像，加快依赖下载

### 四、品牌与文档（10005）

- 应用名统一为「阅读SK」（正式版 `io.legado.app.c` 桌面名由「阅读 C」改名为「阅读SK」）
- 「关于」页移除 QQ 群入口与相关文案（不含任何交流群）
- 「关于」页更新日志改为从本项目仓库（skxingyu/legado-sk）拉取
- 应用内更新检查源改为本项目仓库的 Releases
- README 重写为项目介绍 + 主要优化方向说明，并致谢 legadoC / legado 原作者

### 安装包

| 文件 | 包名 | 说明 |
|---|---|---|
| `legado_sk_3.26.0816c_10004_arm64-v8a.apk` | `io.legado.app.c` | 正式版（可覆盖升级，保留数据） |
| `legado_sk_3.26.0816sk2_10004_arm64-v8a.apk` | `io.legado.app.sk2` | 共存测试版（应用名「阅读SK」） |

> 提示：从旧版（release 签名）升级时若遇签名不兼容，可用本仓库提供的数据迁移方案（详见 README），或先在应用内备份导出再导入。
