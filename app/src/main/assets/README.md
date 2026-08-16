<table align="center">
  <tr>
    <td align="center"><img src="https://github.com/skxingyu/legado-sk/raw/main/docs/photo_2026-08-16_20-07-31.jpg" width="100%" alt="界面截图 1" /></td>
    <td align="center"><img src="https://github.com/skxingyu/legado-sk/raw/main/docs/photo_2026-08-16_20-07-32.jpg" width="100%" alt="界面截图 2" /></td>
    <td align="center"><img src="https://github.com/skxingyu/legado-sk/raw/main/docs/photo_2026-08-16_20-08-01.jpg" width="100%" alt="界面截图 3" /></td>
  </tr>
</table>

**好用的话，点个 star，谢谢啦**

## 项目介绍

本项目 fork 自 [legadoC](https://github.com/CCSSNE/legadoC)，针对阅读、听书体验做了针对性优化，按个人喜好开发。

感谢 [legadoC](https://github.com/CCSSNE/legadoC) 作者的无私奉献，也感谢上游 [legado](https://github.com/gedoor/legado) 开源项目。

## 更新日志

### 3.26.0816（versionCode 10004）— 2026-08-16

**阅读进度同步增强**

- 修复 WebDAV 批量拉取进度提前中断的 bug（`return` → `return@forEach`），多本书可正常同步。
- 进度比较改为纯位置比较（`durChapterIndex` / `durChapterPos`），同步更准确。
- DEBUG 构建也自动同步进度，调试版体验与正式版一致。
- 进度同步开关默认开启。

**朗读（听书）悬浮窗优化**

- 阅读界面内不再显示听书悬浮窗，退出阅读界面后自动显示，避免遮挡正文。
- 新增「阅读界面内隐藏悬浮窗」开关（设置 → 朗读配置，默认开启），可随时关闭恢复原行为。
- 阅读界面内的朗读菜单控制不受影响。

**其他**

- 新增 `sk2` 共存测试版（`io.legado.app.sk2`，应用名「阅读SK」）。
- 启用阿里云镜像仓库，编译更快。
