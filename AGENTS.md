# 阅读SK / legado-sk 项目总则

> 本文件是项目的长期规则来源，只保留可复用的原则、流程、环境约束和当前交付状态；一次性排障过程、界面细节、截图和临时记录不写入这里。
> 规则以 `AGENTS.md` 为准。2026-09-04 起在迁移后的电脑上工作：无 D 盘，不再使用 `D:\OneDrive\桌面\Ai\legado-sk\` 外部工作目录，配套文档策略见 §7「当前机器环境与配套文档」。`docs/` 存放设计文档与截图（`api.md`、`朗读链路.md`、`重构下载架构.md`、`ui-frida-debug.md`、`流程图/`、`预研发/` 等）。
> ⚠️ 本文件是随仓库分发的运行手册；其中 §2/§3/§7 含本机路径与设备信息，只在当前机器的检出副本上维护，不要把这些机器专属路径推送到公开仓库。

## 0. 新会话 AGENT 交接速读（凡在本仓库动手前必读）

> 目的：让**下一个新对话的 AGENT**（看不到此前任何会话，只能读工作目录文件）在动手改代码前，无歧义地弄清「项目做了什么、每个版本改了哪些、改哪里不能改错」。本小节为强制入口，按序读完再动代码。

1. **项目全貌与结构** → 读 `companion/项目文档.md`：项目定位/谱系、工程结构、核心改动方向（含「哪些是 SK 改的、哪些是上游自带」的来源辨析）、§2.1「版本索引」（一屏全貌）。
2. **改某个功能/开关/DB 前先反查它由哪个版本引入、有什么红线** → 读 `companion/发布版更新记录.md`（**版本事实的唯一权威来源**）：第 0 节「防改错速查」（全局透明度锁 0、进度同步三禁、朗读架构唯一形态=10023、DB 迁移线、R8 永不启用等）、§1 逐版净增量、第 2 节「功能→引入版本」反查表。
3. **需要作者原始文案佐证** → `companion/发布版更新原文-releasenotes.md`（GitHub Releases 逐版全文备份，可 grep）。
4. **红线与排错** → 以本文件（AGENTS.md）为准：§4 UI/工程质量、§3 构建版本产物、§6 交付基线；具体历史红线见 §4「功能红线」。
5. **机器环境/路径** → 本文件 §7「当前机器环境与配套文档」§7.1 环境快照、§7.2 companion 文档清单。
6. **动手前决定用几个子代理** → 读 §1.5「子代理编排」。大范围审计/排查**默认拆并行**，串行单代理是已知失败模式；边界（只读、写域切分、主代理收口）见该节。
7. **改朗读链路前必读** `docs/朗读链路.md`（朗读状态所有权契约，10023 架构的唯一细节来源）；**朗读引擎与上游「阅读」的差异、已删旧能力必读** `docs/朗读引擎-上游对齐差异台账.md`（有意保留的 6 项差异 + SK 独有能力清单，改回去即回归）；**改造缓存/评论任务域前必读** `docs/重构下载架构.md`（缓存域唯一架构契约）。
8. ⚠️ `companion/` 整目录在 `.gitignore` 中忽略、**不推送公开仓库**（含机器信息）；只读不随意改动，改动需与对应事实一致。若发现文档与源码事实不符，**以源码和 GitHub Releases 为准**并先核实再改文档。

## 1. 核心工作原则

每次开始编程前，先重申并遵守以下原则：

> 解决根本问题，拒绝任何兜底；有问题，直接暴露。统一维护、统一修复，避免特殊代码不断膨胀。鼓励调查，鼓励详细日志和探针，鼓励联网搜索。

具体要求：

- 先定位事实、边界和根因，再修改代码；不能用静默回退、吞异常、默认值补丁或仅覆盖症状的分支掩盖问题。
- 相同问题应收敛到共同抽象、共同入口或共同数据源。新增特殊逻辑前，先证明现有统一路径无法正确表达该需求。
- 结论必须区分“已由证据确认”和“仍属假设”。复杂问题要补足日志、探针、截图或 trace，使后续排查可以复现。
- 任何失败都必须说明原因和下一步。构建异常在解决后记录现象、根因、修复方式和是否交付；只把能长期复用的结论保留在本文件，并及时修正或删除失效规则。

## 1.5 子代理编排（默认激进并行；10038 审计实践提炼）

> 结论先行：**默认把工作拆给子代理并行跑**，主代理只做编排、交叉验证与收口。串行单代理处理大范围任务是本项目的**已知失败模式**（见下文反面案例）。

### 何时必须拆（触发条件，满足任一即拆）

- **审计/排查范围超过约 10 个文件**，或需要通读 `git diff` / 全库 grep 才能定性。
- **任务含 ≥3 个彼此独立的检查项**（如"审 P1~P8 八项方案"）。
- **需要"审查方"与"被审方"分离**——即结论需要被独立证伪时。
- **单项预计耗时长**（如逐文件比对、大量外部资料检索）。
- **同一批改动需要多角度验证**（静态审查 / 编译 / 运行时回归可并行准备）。

### 拆法（经验值）

- **按"独立结论单元"切，不按文件数平摊**：切分后每个子代理应能**独立给出可用结论**，不依赖其它子代理的输出。例：按缺陷项分组（第 1 批 P1~P7 / 第 2 批 P3~P4 / 第 3 批 T3~T6），而非"你读前 50 个文件"。
- **3~5 个并行是舒适区**；超过 8 个后汇总成本超过收益。
- **允许子代理再派子代理**（本会话第 3 批探索代理自行派了 2 层共 3 个后代，效果良好）。给它的提示词里写明"你可在需要时自行拆分"。
- **对抗性任务必须显式要求证伪**：提示词要写"默认假设方案有错，去源码找反证"、"找不到反证就明说未找到并列出验证过的证据"，否则子代理倾向"配合确认"。
- **给子代理的提示词必须自包含**：它看不到本对话。要带上背景、已知排除项（避免重复报误报）、边界约束、输出格式、以及"必须给出：位置/证据/影响/建议/风险等级/改动量"。

### 边界（硬约束，不可让渡）

- **写权限必须显式划界**：审查类子代理只能**只读**，唯一可写是**自己名下的报告文件**；须在提示词里点名"严禁修改 `app/src/` 下任何文件"。
- **多代理不得同时写同一文件**：并行任务按文件/目录切分写域，避免互相覆盖。若无法切分，改为串行。
- **主代理负责收口**：子代理结论**一律不盲信**，尤其"现状描述是否准确"与"是否有更优方案"两项必须亲自到源码复核。本会话中主代理据此**纠正了子代理 3 处误判**，同时被审查代理**纠正了自己 2 处实质错误**——双向纠错才是目的。
- **不改代码的探查阶段禁止改任何源码**；需要"方案 → 审查 → 再动手"闸门时，主代理在审查通过前不得编辑源码。
- **别在运行中给子代理追加需求**：见下文反面案例。

### 反面案例（10038 实测，必读）

**单代理串行 + 中途追加需求 = 失控**：一个审查代理被要求审 7 项方案，**54 分钟零产出、未派任何子代理**；期间主代理向其追加了两轮补充材料（新增 4 项 + 新证据）。追加内容作为 steering 消息插入其正在执行的步骤，极可能触发**反复重新规划已完成的工作**。
**纠正后**：按范围切成 3 个独立审查代理，**约 4 分钟产出 3 份共 105KB 报告**，且因相互独立而抓到主代理方案的 1 项实质性错误。

**由此得出**：
1. 大任务先拆再派，不要派一个"全能代理"。
2. **派单要一次说清**；确需补充时，等它返回本轮结果后**重新派单**，而不是往运行中的代理插消息。
3. 判断"卡住"vs"慢"的判据：**长时间（如 >20 分钟）零产出文件、且未派任何子代理** → 按卡住处理，中断并重切任务，不要继续等。

### 与既有流程的关系

- 本节**不改变** §5 的提交纪律（每个独立修改一个提交）与 §2/§3 的验证闭环；子代理只分担**调查、审查、方案编制**，**最终改代码与提交仍由主代理串行完成**，以保证提交边界清晰可回退。
- 子代理产出的中间报告写入已忽略的 `test-records/`（见 §7.2），不提交、不推送。

## 2. 设备与测试边界

### 真机设备

| 设备 | 型号 | 序列号 | 备注 |
|---|---|---|---|
| 手机 | 小米 Redmi `23049RAD8C`（`marble`） | `72a2b362` | 2026-09-29 核实：Android 13 / HyperOS（MIUI `V140`）/ arm64-v8a |
| 平板 | 联想 TB-9707F | `HA1KAPWG` | Android 14 |

- ⚠️ **作者自 2026-10-30 起主用正式版 `io.legado.app.c`，共存版 `io.legado.app.sk2` 不再主用**（只保留作并行验证/备用）。因此：真机覆盖安装优先 **`legado_sk_<ver>_<code>_arm64-v8a.apk`**（正式版）；若目标机只装过共存版，装正式版会变成"多出一个空 App"而不是升级（两包签名相同但包名不同，不能互覆）。
  - 历史：2026-09-29 核实过"两台真机都只装共存版"；目前已变化 —— **平板 `HA1KAPWG` 同时装了正式版与共存版（均 10108）**，且已用「备份→恢复」把共存版数据（书架 54 本 / 书源 11 条 / cookies 29 条 / 登录缓存 664 条）搬进正式版。
  - 手机 `72a2b362` 的包分布未重新核实，操作前先 `adb -s 72a2b362 shell pm list packages | grep io.legado.app`。
- ⚠️ **华为 MAR-AL00（`9HQDU19903003356`）已不在用**（2026-09-29 核实，`adb devices` 中不存在）。若日后重新接入，需先确认型号与序列号再补回本表。
- ⚠️ **HyperOS/MIUI 手机上 `adb install` 会被 `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user` 拒绝**，这是「USB 安装」开发者开关（设置 → 更多设置 → 开发者选项 → USB 安装），**必须用户在手机上手动打开**。已验证 adb 侧无法绕过：`verifier_verify_adb_installs 0`、`package_verifier_user_consent -1`、`appops set … REQUEST_INSTALL_PACKAGES allow` 三者均无效（`pm install-create` 能建会话但 commit 被 MIUI 否决）。遇到该报错**直接请用户开开关**，不要反复重试。
- 所有 `adb` 命令必须显式带 `-s <serial>`；执行前确认目标设备，禁止裸 `adb`。
- 真机安装统一 `adb -s <serial> install -r <..._arm64-v8a.apk>`（正式版；同 debug 签名，覆盖升级保数据）。**校验升级成功的判据是 `firstInstallTime` 不变**（`dumpsys package <pkg>`；该值变化即"重装"而非"升级"）。
- 换签名迁移数据走 run-as tar 打包流程：导出必须用 Python subprocess 二进制流（git bash `>` 重定向会 CRLF 污染 tar）；`pm uninstall -k` 不可行（数据绑定签名）。备份与导出在仓库根的 `backup\`（被 .gitignore 忽略）。
- 真机问题优先依据用户描述、代码和用户提供的日志排查。
- ⚠️ 若某台真机 `run-as` 报 `package not debuggable`，那是**正确行为**（release 包），不是故障。

### 雷电模拟器

- APK 安装、运行和调试只使用雷电模拟器（LDPlayer）。**迁移后本机（2026-09-04）使用的 LDPlayer 在 C 盘**，启动程序路径为 `C:\download\down\cloud-down\雷电模拟器14纯净绿色版+狐狸+LSP+微霸\LDPlayer14\dnplayer.exe`（同目录含 `ldconsole.exe`、`adb.exe`；实例 `leidian0`，instanceIndex=0）。未启动时可尝试启动；失败则请用户手动打开。旧的 `F:\down\...\LDPlayer14` 与本机 `F:\leidian\LDPlayer14` 均不再使用。
- android-dev 工具链统一目标在 `tools\android-dev\target.json`，已改指上述 C 盘 LDPlayer；其 ADB 走环路 `127.0.0.1:5555` + ldconsole 启动序列号校验（见 §4 分层调试），禁止以该环路之外的裸 serial 操作。常规手动 `adb` 序列号仍用 `emulator-5554`，每条命令都必须显式带 `-s`；执行前确认目标确为模拟器，不确定时停止。
- 分辨率 1440x2560，模拟器内建议配置 WebDAV。每条 `adb` 命令都必须显式带序列号，例如 `-s emulator-5554`。执行前确认目标确为模拟器；不确定时停止，禁止裸 `adb`。
- 真实小说优先用于阅读功能验证。`C:\Users\skxingyu\Documents\leidian14\Pictures` 与模拟器 Pictures 目录互通，可作为导入素材。
- ⚠️ 模拟器覆盖安装前，先 `adb -s <serial> shell dumpsys package io.legado.app.c` 读已装 versionCode，只允许 ≥ 已装版本的覆盖（当前基线见 §6）。

### 验证闭环

- 每次代码改动都按以下闭环执行：正式编译（`assembleAppRelease`）APK -> 安装到已确认的雷电模拟器 -> 复现并回归验证；真机最终验证由用户手动完成。
- AI 侧回归只在雷电模拟器执行；真机安装仅在用户明确指示下进行。

- 崩溃或行为异常时，先收集日志和复现证据，定位根因后修复，再重新正式编译和回归；不能报告未经验证的修复。
- UI 改动必须覆盖受影响的交互、显示、主题/状态切换和关闭重开等生命周期，而不是只确认一张静态截图。

## 3. 构建、版本与产物

### 不可变交付约束

- 代码改动只能用正式版（`app` flavor + `release` buildType，包名 `io.legado.app.c`）验证与交付，产物在 `app\build\outputs\apk\app\release`。禁止以中间 Gradle 任务、debug APK 或改名旧包充当验证/交付物。
- 覆盖安装前必须显式传入 `VERSION_CODE` 和 `VERSION_NAME`。新 `VERSION_CODE` 必须比最近一次交付大；`VERSION_NAME` 必须按 GMT+8 编译时刻单调递增，格式为 `3.26.MMddHH`。
- 正式版 = `app` flavor + `release` buildType（`assembleAppRelease`，包名 `io.legado.app.c`）。`versionName` 需直接传完整值（含 `c` 后缀，如 `3.26.090812c`），无自动加后缀机制；`versionCode` 遵循 SK 独立递增约定（当前基线见 §6）。
- 编译前先从模拟器已安装包确认版本；模拟器不可用时使用第 6 节的最近交付基线。确认新版本后，只删除 `app\build\outputs\apk\app\release` 中对应的旧 APK，绝不删除宽泛目录或源码。
- 编译前通读本节（「本机环境与正式命令」+「长命令和构建失败」+「产物验证」）——这是编译/排错的**唯一出处**，不存在单独的排错手册。

### 本机环境与正式命令（迁移后 2026-09-04 核对）

- 代码/构建唯一目录（无 D 盘，不再分编译树）：`C:\code\ai-code\legado-sk`（git 仓库，remote = `skxingyu/legado-sk`，main 分支，gh auth 直连推送）。
- JDK 17：`C:\Users\skxingyu\AndroidDev\jdk-17.0.2`
- Android SDK：`C:\Users\skxingyu\AndroidDev\android-sdk`（platforms `android-34`/`android-36`；build-tools `34.0.0`/`36.0.0`）
- Gradle：用项目 wrapper `gradlew.bat`（distributionUrl = gradle-8.14.4-bin，首次自动下载到 `C:\Users\skxingyu\.gradle\wrapper\dists`）；本机另有 `C:\Users\skxingyu\AndroidDev\gradle-9.7.1` 备用，勿覆盖 wrapper 约定
- Gradle user home：不显式设置 → 默认 `C:\Users\skxingyu\.gradle`
- 系统 adb：`C:\Users\skxingyu\AndroidDev\android-sdk\platform-tools\adb.exe`
- Gradle wrapper: `8.14.4`; AGP `8.13.2`; compileSdk `36`; 依赖/平台已按此装齐
- 交付 APK（`-Pabi=arm64-v8a`）产物在 `app\build\outputs\apk\app\release`

```powershell
$OutputEncoding = [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$env:JAVA_HOME = 'C:\Users\skxingyu\AndroidDev\jdk-17.0.2'
$env:ANDROID_HOME = 'C:\Users\skxingyu\AndroidDev\android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
# GRADLE_USER_HOME 不设置，走默认 C:\Users\skxingyu\.gradle
$env:Path = @(
  "$env:JAVA_HOME\bin",
  "$env:ANDROID_HOME\cmdline-tools\latest\bin",
  "$env:ANDROID_HOME\platform-tools",
  "$env:ANDROID_HOME\build-tools\36.0.0"
) + ($env:Path -split ';') -join ';'

Set-Location 'C:\code\ai-code\legado-sk'   # 编译必须在仓库根执行
$versionCode = <new-version-code>
$versionName = '3.26.<MMddHH>c'            # 完整版本名（含 c 后缀）
.\gradlew.bat ':app:assembleAppRelease' "-Pabi=arm64-v8a" "-PVERSION_CODE=$versionCode" "-PVERSION_NAME=$versionName" --console=plain --warning-mode=summary
# 共存版（与正式版 / 阅读C 同时安装）：与正式版同一次编译一并产出，除任务名外参数完全相同
.\gradlew.bat ':app:assembleAppSk2' "-Pabi=arm64-v8a" "-PVERSION_CODE=$versionCode" "-PVERSION_NAME=$versionName" --console=plain --warning-mode=summary
```

#### 共存版 `sk2`（2026-09-21 起恢复，与正式版并行交付）

> ⚠️ **作者自 2026-10-30 起主用正式版 `io.legado.app.c`，共存版降为备用/并行验证**。共存版**仍按本节要求每次编译一并产出**，但**真机安装、日常使用与验证口径以正式版为准**；文档与发布说明不要把共存版写成主用形态。

> 历史：10004–10006 曾以 `sk2` 共存版随正式版双发，10007 起停发并写明「不再提供 sk2」。**2026-09-21 作者决定恢复**，本日起每次正式编译都**必须同时**产出共存版，文档口径已同步更正。

- 变体 = `app` flavor + `sk2` buildType（**buildType，不是新 flavor**），包名 **`io.legado.app.sk2`**，应用名同「阅读SK」；与正式版 `io.legado.app.c`（`app`+`release`）、阅读C 三者可同时安装。
- 产物在 `app\build\outputs\apk\app\sk2`；⚠️ 产物目录就是 `app\sk2` 本身（是 buildType 名，与是否继承 debug 无关）。
- ⚠️ **`sk2` 必须 `initWith release`，禁止 `initWith debug`（2026-09-24 修正，勿改回）**：继承 debug 会让共存版带上 `android:debuggable=true` 且 dex 不合并/不优化 —— 实测 **23 个 dex vs 正式版 8 个**，APK 大 ~8 MB（44.5 MB vs 36.2 MB）。共存版与正式版的**唯一**差异只能是包名，行为与体积都必须对齐。同时必须显式覆盖继承自 `release` 的两项：`applicationIdSuffix '.sk2'`（否则变成 `.c.sk2` 之类）与 `versionNameSuffix ''`。
  - ⚠️ 同类陷阱对**任何**新 buildType 成立：先想清 `initWith` 的基底是否与交付语义一致，再显式覆盖它会带偏的每一项（applicationIdSuffix / versionNameSuffix / minify / shrinkResources / matchingFallbacks）。
  - ⚠️ 已存在的 sk2 产物（10073 之前，44.5 MB 那批）**是带 debuggable 的包**，如需发布共存量请用 `initWith release` 重编。
- Gradle 输出名同正式版（`legado_sk_<versionName>_<versionCode>.apk`，无 `_arm64-v8a` 后缀），**收进 `release/` 时按约定补后缀并加 `_sk2`**。
- ⚠️ **共存版与正式版共用同一 `VERSION_CODE` / `VERSION_NAME`，不另加版本后缀**（`sk2` 刻意**没有** `versionNameSuffix`）。两条产物的版本事实必须逐字一致，便于回溯「同一个 10058」。
- ⚠️ **两者同用 SDK debug 签名**：改的是包名不是签名，因此**不能互相覆盖安装**（这正是共存的前提）；也**不能覆盖安装阅读C**（签名不同，需走 §2 的数据迁移流程）。
- ⚠️ **共存版是独立应用、独立数据**：包名不同 → `filesDir` / `getExternalFilesDir(null)`（缓存、书籍、主题、pref、DB）全部隔离，**不共享书架与进度**。它是"并行再装一份"，不是"共用数据"；要同步数据走应用内备份导出/导入（WebDAV 或本地 zip）。私有目录之外的**用户可见路径共用**（如 `/sdcard/Download/yuedu` 导出目录、WebDAV 目录名），互导时注意覆盖。
- ✅ **无需为共存做任何"隔离类"改造**：隔离由 Android 平台按 `applicationId` 保证（不同包名 → 不同 uid / 数据沙箱 / `FileProvider` authority；manifest 中 `authorities` 全部是 `${applicationId}` 占位，`AppConst.authority` 取 `BuildConfig.APPLICATION_ID`）。除下面这一处由包名差异**必然**派生出的分支外，两者行为一致（10059 起内置书源与其授权守卫已整体移除，见 §6）。
  - ⚠️ **唯一按变体分支的地方：`UpdateManager` 选 Release 资产**（10097 引入）。两个 APK 同挂一个 Release，资产顺序 = 上传顺序，故**不能取"第一个 .apk"**，必须按文件名标记筛：正式版取无标记的，共存版取 `_sk2` 标记的（`selectUpdateAsset` + `isCoexistBuild()`）。**新增任何"从 Release 下包"的功能都必须走同一判据**，回归锁 `UpdateManagerAssetSelectionTest` 会挡住退化。
- ✅ **共存版同样参与更新检查，且下载的是共存版自己的包**（10097 起；此前 `assets.firstOrNull { .apk }` 会让共存版下到**正式包**，装上去只新增/更新正式版 App，共存版永远升不上去 —— 这是它"看起来不参与更新检查"的真实原因）。发布时两个 APK 必须**同时**上传为 Release 资产（2026-09-21 作者指示修正：此前写的「不发布 Release 资产」已作废，10065 起两个 APK 同时发布）。
  - ⚠️ 任一版本若**只发正式包**，共存版会判定"无我这一版"而提示已是最新（刻意如此：宁可误报最新，也不跨包名塞包）。故 10065 起的发布约定必须**成对上传**。
  - ⚠️ 发布说明里要**写清两者区别**：正式版可覆盖升级、与阅读C 签名不同不能互覆；共存版可与之三者并存、**独立数据不共享**、**不能覆盖安装正式版**。

编译成功后必须把新 APK 收进仓库根已忽略的交付目录：
1. 覆盖 `C:\code\ai-code\legado-sk\release\legado-sk-arm64-v8a.apk`（「当前交付 APK」，固定名；`/release` 已被 .gitignore 忽略）。
2. 按版本命名同存于 `C:\code\ai-code\legado-sk\release\`：`legado_sk_<versionName>c_<versionCode>_arm64-v8a.apk`。
3. 共存版同存于 `C:\code\ai-code\legado-sk\release\`：`legado_sk_<versionName>c_<versionCode>_arm64-v8a_（共存版）.apk`（本地文件名带「（共存版）」，让用户一眼分清哪个能共存）。
   - ⚠️ **GitHub Release 资产名不支持非 ASCII 字符**：上传 `…_（共存版）.apk` 会被静默截断成 `…_arm64-v8a_.apk`（实测，10097 复现）。**Release 资产名一律用 ASCII 后缀 `_sk2-coexist`**，并在发布说明里写明它就是共存版。
     - ⚠️ **`gh release upload "本地路径#目标名"` 的 rename 语法只在本地名是 ASCII 时有效**（10097 实测：本地名含中文时目标名不生效，只得到规整后的 ASCII 名，「用 `#` 就能指定目标名」是**错记**）。稳妥做法：**先把本地文件改名为纯 ASCII 再上传**，或上传后用 `gh api -X PATCH repos/skxingyu/legado-sk/releases/assets/<id> -f name='…_sk2-coexist.apk'` 改名。
     - ⚠️ **资产名必须含 `_sk2`**：这是共存版更新检查识别"自己那一版"的唯一标记（见下方 `UpdateManager` 条），漏了就静默失去更新能力。上传后**务必** `gh api … --jq '.assets[].name'` 复核。
4. ⚠️ **`release/` 只保留最新版本**（2026-09-30 作者指示）：一旦新版本构建完成并收集齐，**删掉上一版及更早的本地 APK**（历史版本从 GitHub Release 重新下载即可）。目录里应只有 `legado-sk-arm64-v8a.apk`（固定名）+ 当前版本的两个包，共 3 个文件。
   - ⚠️ **例外**：**从未发布过 Release 的版本删掉即不可恢复**（GitHub 上没有）。若某版本临时未发布且日后可能要用，删除前先向作者确认。

### 长命令和构建失败

- 任何可能超过 30 秒的命令必须实时监控。每 30 秒以内检查进程是否存活、CPU 是否增长、日志/产物是否更新；停滞时终止并报告，不能无限等待。
- 后台编译须保存 stdout、stderr 和退出码。`cmd /c` 的内联重定向不可靠时，改用 `.bat` 文件启动，不得把空日志误判为正常编译。
- 先阅读实际错误中的文件、行号和异常，再选择修复。不得把源码错误猜成内存问题后盲目重跑。
- 仅在证据指向缓存锁定、守护进程或原生内存问题时，先停止 Gradle，清理残留 Gradle/Kotlin/Java 进程，再用正式 `assembleAppRelease` 进行最小必要的冷编译诊断，例如 `--no-daemon --max-workers=1 -Dkotlin.incremental=false -Dksp.incremental=false -Dkotlin.compiler.execution.strategy=in-process`。目录清理仅限受影响模块的 `build` 目录。
- 构建无论成功或失败，执行 `.\gradlew.bat --stop` 并按 PID 清理残留构建进程，避免占用内存。
- 2026-08-15：`HeaderlessDialogChrome` 首次正式编译在 `AccentTextView(context)` 失败，因为该控件构造器强制要求 `AttributeSet?`；读取 Kotlin 报错后改为 `AccentTextView(context, null)`，同版本重编译成功。失败包未产出、未交付。动态创建项目自定义 View 时必须先核对构造器签名，不能假定存在单参构造器。
- 2026-08-15：首次启动 10608 构建时，把批处理和退出码写入拼在 `cmd /c` 参数中，Windows 报“文件名、目录名或卷标语法不正确”，没有 Gradle 进程、构建日志或 APK。改为由 `.bat` 自己记录退出码，再以 `Start-Process` 直接启动，构建正常。后台构建的重定向/引号错误必须以“未启动”处理，不能等待或误判为 Gradle 卡死。
- 2026-09-05（10029 编译两次失败复盘）：在 DSH 沙箱 `workspace-write` 会话里启动 `gradlew.bat`，wrapper 阶段即报 `gradle-8.14.4-bin.zip.lck (拒绝访问)` 退出。判别要点：① 报错在 `GradleWrapperMain`/`ExclusiveFileAccessManager` 而非 Gradle 任务 → 不是项目代码或内存问题，不要跑冷编译诊断；② 删锁文件、杀光残留 java 进程后**仍**报同一处拒绝访问，且系统无 java 进程持锁 → 说明不是锁被占用，而是进程根本没有写 `C:\Users\skxingyu\.gradle`（wrapper 锁/缓存/daemon，仓库外用户级目录）的沙箱授权。处理：用 `sandbox_permissions` 放开权限**原样重试同一条编译命令**（pwsh `danger-full-access`，justification 说明 Gradle 必须写 `.gradle`），一次成功。规则：**在本机跑 Gradle 构建（含 `gradlew --stop`）必须默认带放开权限执行**；`workspace-write` 下 Gradle 必失败，不要浪费轮次删锁/杀进程重试。另注意：残留 daemon 清理仍有价值（本次 10028 遗留 4.5GB+3.6GB 两个 java 进程），但它是例行卫生，不是该报错的根因。

### 产物验证

```powershell
$apk = 'C:\code\ai-code\legado-sk\app\build\outputs\apk\app\release\legado_sk_<version>_<code>.apk'
& "$env:ANDROID_HOME\build-tools\36.0.0\aapt.exe" dump badging $apk
& "$env:ANDROID_HOME\build-tools\36.0.0\apksigner.bat" verify --print-certs $apk
```

交付前确认：包名 `io.legado.app.c`、版本号递增、中文名 `阅读SK`、`arm64-v8a`、产物来自 `assembleAppRelease`，且 `apksigner` 退出码为 0。部分 `META-INF` 条目未受签名保护的提示可接受。

## 4. 工程质量规则

- 无头弹窗的统一策略只负责移除 `Toolbar` 并把菜单动作迁到标准底部操作区；不得以保留空白 Toolbar 伪装“无头”。移除 Toolbar 前必须核对布局测量：原来依赖 Toolbar 固定高度的 `0dp` / weight 内容区，要改成显式的“内容区 + 底部操作区”结构，否则 `wrap_content` Dialog 会塌缩。
- 无头迁移器向 `ConstraintLayout` 加入底部操作区时，所有原先 `bottomToBottom=parent` 的内容必须统一改为约束到 footer 顶部；禁止仅增加 parent padding 伪造预留空间，否则滚动内容会与按钮重叠。`dialog_content_edit` 于 2026-08-15 以此规则完成回归。
- 标准 `AlertDialog` 的标题不能直接追加到 `contentPanel`：该面板是叠放容器，会与选择列表重叠。统一表面路径应将标题和原内容重组为垂直内容列后再隐藏 `topPanel`，使标题成为同一玻璃面上的正文首行，而非独立顶栏。使用 `setCustomView` 时内容位于 `customPanel`；标题迁移后只能保持 `customPanel` 或 `contentPanel` 之一作为中段，禁止额外启用另一个面板挤占 `buttonPanel` 的测量空间。缺少相应面板属于结构错误，应直接暴露，不能悄悄丢弃标题或遮住首项。

### UI 内核与浮层规范

本项目的 UI 内核不是一套普通页面和另一套弹窗页面，而是四层单向组合。所有新 UI 必须先在此树中归类；业务页面只能使用下层能力，不能反向改写或复制下层逻辑。

```text
主题语义层
ThemeStore / ThemeUtils / UiCorner
    └─ UI、阅读、Dialog 三组颜色、透明度、圆角和描边语义
        │
表面描述与渲染层
SurfaceStyle / SurfaceStyles / SurfaceDrawable
    └─ 同一裁剪路径绘制模糊底图、tint、描边和几何
        │
表面生命周期层
SurfaceBackdrop
    └─ 稳定几何、PixelCopy、局部模糊、代际丢弃和位图回收
        │
宿主适配与内容层
BaseDialogFragment / BasePrefDialogFragment / BaseBottomSheetDialogFragment
AndroidAlertBuilder / SurfacePopupMenu / 阅读页显式浮层
    └─ Feature 的业务内容、操作和布局
```

#### 首先分类，不得按“看起来像”处理

| 类型 | 统一入口 | 表面规则 |
|---|---|---|
| 普通 Activity / Fragment 页面与页内控件 | `ThemeStore`、`UiCorner`、现有主题 View/样式 | 只使用 UI 组样式；不是模糊浮层，禁止为整页安装 `SurfaceBackdrop`。 |
| 普通模态 Dialog | `BaseDialogFragment` | 声明真实可见表面（优先 `vw_bg`），由基类安装 Dialog 表面。 |
| Preference Dialog | `BasePrefDialogFragment` 或现有 preference adapter | 走同一 Dialog 表面与无头 Alert 规则。 |
| 底部 Sheet / 阅读设置 Sheet | `BaseBottomSheetDialogFragment`；阅读页使用 `BaseReaderSheet*` | 仅上角几何；阅读色彩只能来自 `ReaderSheetStyle`。 |
| 简单确认、选择、输入框 | `alert` / `selector` / `AndroidAlertBuilder` | 由 `applyAlertSurface()` 处理 AppCompat 面板和无头标题。 |
| 右上角更多、列表行更多等 PopupWindow 菜单 | `SurfacePopupMenu` 或 `View.showPopupMenu` | 应用拥有唯一可见外壳，显示前完成其局部表面准备。 |
| 阅读页 Activity 内的主菜单、搜索菜单、文本操作浮层 | 调用方声明的专用背景层 | 这是同窗口浮层，不是 Dialog；只能刷新明确命名的目标表面。 |

Activity 页面标题和正文标题不是“弹窗头”，不得为追求无头规则而删除。无头规则只适用于广义浮层的独立顶栏：Dialog、Alert、Sheet、PopupWindow 和阅读页浮层都不得新增 `Toolbar` / `TitleBar` 顶栏；操作应放在内容内的标准底部操作区。标题有业务语义时只能作为正文首行，不能恢复独立 chrome。

#### 只有一个表面内核

- `SurfaceStyle` 只描述视觉：tint、圆角、描边、模糊半径；它不得知道窗口类型、布局树或业务状态。
- `SurfaceBackdrop` 是唯一可做 PixelCopy、模糊、稳定几何等待、显示代际和位图回收的地方。`SurfaceDrawable` 是唯一把底图、tint、描边绘入同一裁剪路径的地方。
- 每个浮层必须显式声明一个真实、唯一的可见表面。不能扫描控件树猜目标，不能把内容按钮、列表或宿主 decor 当作表面，也不能缓存宿主整页后按猜测坐标裁剪。
- UI、阅读、Dialog 的颜色和透明度只能经 `UiCorner` / `SurfaceStyles` / `ReaderSheetStyle` 取得；Feature 不得重算 alpha、圆角、描边、模糊半径或写另一套玻璃颜色公式。
- `updateStyle()` 只更新同一目标的样式，不得中断该目标在途取图；关闭、换目标、重新显示和尺寸变化才创建新代际。Feature 不得自行管理另一套 generation 或 Bitmap 生命周期。

#### 新代码的强制入口

- 新的自定义模态框只能继承相应 `Base*DialogFragment`。新的简单 Alert 只能走 `alert` / `selector` / `AndroidAlertBuilder`；新的菜单只能走 `SurfacePopupMenu` 或其扩展入口。
- 新的阅读页浮层必须先声明“宿主 Window、唯一背景层、显示前准备点、关闭点、尺寸变化点”，然后复用 `SurfaceBackdrop`。这些条件无法表达时，先扩展内核/宿主适配器并完成全路径验证，禁止在 Feature 内新建 `xxxBlur`、`xxxGlass`、`xxxPopup` 或私有表面助手。
- 需要跨两个以上 Feature 或两种以上宿主复用的视觉/交互模式，提升到 `lib/theme`、`lib/theme/surface`、`lib/dialogs` 或 `ui/widget` 的现有内核旁；只属于一个 Feature 的业务内容留在 Feature 内，但仍使用核心表面和样式。
- 现存直接 `Dialog`、`PopupWindow` 或第三方窗口类属于迁移存量，不是新代码模板。修改它们时优先接入上述入口；确有宿主限制时，先记录限制和适配方案，不能复制一份私有实现。

#### 绝对禁止

- 禁止给宿主 Activity `decorView` 做全局 `RenderEffect`；禁止 `FLAG_BLUR_BEHIND`、`setBackgroundBlurRadius`、`DIM_BEHIND` 或任何系统整窗变暗来替代局部表面。
- 禁止反射 PopupWindow 私有字段、共享可变背景 Drawable、叠加“矩形 Bitmap + 另一层圆角颜色”背景，或以透明/纯色/全屏模糊作为取图失败的 Feature 级兜底。
- 禁止在新 Dialog 布局中新增 `Toolbar` / `TitleBar`，禁止新建特定页面的 alpha、blur、corner、surface-color 常量或 `when (页面名)` 特例。
- 禁止为绕过本规范添加新的 suppress、静默 catch、默认回退目标或吞掉表面安装错误。内核无法表达的需求必须直接暴露并先修内核。

#### UI 变更验收清单

- [ ] 已明确它是普通 UI、Dialog、Preference、Sheet、Alert、PopupWindow 还是阅读页同窗口浮层，并使用了表中唯一入口。
- [ ] 浮层已明确真实背景层；目标 attach、连续两帧几何稳定后才取图，首次可见前背景已安装。
- [ ] 没有全局模糊、系统 DIM、私有反射、Feature 自建表面算法、独立 Bitmap 生命周期或页面专属兜底。
- [ ] Dialog/Alert/Popup 没有独立头栏；需要的操作在标准底部区，关闭、重开、主题变化和尺寸变化都不会让旧回调覆盖新表面。
- [ ] 已在雷电模拟器回归：截图检查范围/圆角/透明度，uiautomator2 检查层级和可点击性；普通证据不足才按分层调试规则同时采集 Perfetto、Winscope 与 Frida。

### 功能红线（历史踩坑，违反即回归）

- **R8/混淆永久禁用**：legado 是重反射应用（书源引擎 / JS 桥 / 动态类加载），开启 `minifyEnabled` / `shrinkResources` 会破坏反射链并误删系统过渡动画，实测运行时卡顿（10009 已回退）。瘦身只允许资源层：图片重编码但保持文件名不变、删除零引用资源、`resConfigs "zh"` 语言裁剪。
- **阅读进度同步三禁**（移植上游后逐项核对防回归）：
  1. `BookProgress.compareWith` 禁止时间戳优先，只比较 `durChapterIndex` → `durChapterPos`；
  2. `AppWebDav.getProgressFileName` 保持 `书名_作者.json` 双参无 mediaType 后缀；
  3. `ReadBookActivity` / `ReadMangaActivity.onPause` 自动同步禁止加 `BuildConfig.DEBUG` 限制。
- **听书翻页竞态守卫（10023 起为新架构）**：朗读跟随体系采用上游「两原语 + 纯函数跟随规则 + 派生脱节」（10017/10018 的 `pageTurnAnimating` / `TTS_PROGRESS` 存储式守卫已被 `shouldFollowAloudAdvance` 单调性规则整体替代，`readAloudPageDetached`/地板闩已删除）。防拽页由「显示页==朗读出发页且位置前进才跟随」单一规则保证，翻页由 UI 侧观察者单点执行，引擎只发布位置绝不直写 `durChapterPos`。移植上游时不得回退到旧的存储式 detach / 跟随地板方案，不得让引擎重新直写显示进度。
- **系统 TTS 必须保留两个无响应看门狗（10120 确立）**：`TTSReadAloudService` 的 `TTS_INIT_TIMEOUT_MS = 15_000L`（`initTts` 后 `onTtsInit` 不来 → 重建一次，再不来 `reportInitFailure()` + toast + `pauseReadAloud(false)`）与 `TTS_STALL_TIMEOUT_MS = 45_000L`（轮询 `lastTtsCallbackAt`，`onStart`/`onDone`/`onRangeStart` 更新，超时走 `handleSpeakError("tts no callback", retryWithReinit = true)`）。⚠️ 不得因为「看起来是兜底」删掉：引擎进程被杀或合成请求在引擎内丢失时，`UtteranceProgressListener` 三个回调都不会来，没有超时就表现为「服务与通知仍在、永久无声」（2026-11-09 平板 MultiTTS 实测，`stop/shutdown failed: not bound to TTS engine` 同场出现）。看门狗必须在 `clearTTS`/`playStop`/`pauseReadAloud`/`handleSpeakError` 撤销，`ttsWavMode` 不启用。
- **在线朗读的「暂停」必须真的停声（10113 确立）**：`HttpReadAloudService.pauseReadAloud()` / `resumeReadAloud()` 对 ExoPlayer 的操作必须 `lifecycleScope.launch { }` 在主线程执行，**禁止用 `runCatching` 包裹**。原因：`super.pauseReadAloud()` 内部已 `abandonFocus()` 放弃音频焦点，而合成失败回调跑在 `BaseService.execute` 默认的 `Dispatchers.IO`；异常一旦被吞，表现就是「焦点已弃、声音继续 → 缓冲播完章中静音」，服务与通知仍在、章节定时额度也没用完（2026-10-31 夜间实机事故）。合成失败必须走 `prepareScriptMediaItemsWithRetry()`（3 次、间隔 1.2s）并在最终失败时 `AppLog`(`LogModule.READ_ALOUD`) + toast 明示；一次失败即静默暂停属回归。回归锁 `ReadAloudPauseOnMainThreadGuardTest` 3 项。
- **原版共享偏好 key**：`BookCover.kt` 的 `legadoCoverRuleConfig` 是原版遗留 key，不能改名。
- **品牌与更新**：不做交流群（QQ 入口全删）；更新检查与仓库链接全部指向 `skxingyu/legado-sk`（`UpdateManager.GITHUB_API`、关于页 README 直连 `raw.githubusercontent.com/skxingyu/legado-sk/main/README.md`）；「更新设置」只存在于关于页，无启动自动检查。
- **内置书源与其授权守卫：10059 起永久移除，不要恢复**（2026-09-21 作者指示）。不得再引入 `defaultData/bookSources.json`、`DefaultData.builtinBookSources` / `seedBuiltinBookSourcesOnce()`、`LocalConfig.builtinBookSourceSeeded`、`JsExtensions.matchApp()` / `getAppName()` / `getAppPackageName()`、`AppInfo.packageName` / `appName`。回归锁 `BuiltinSourceRemovedTest`（已双向证伪）会在恢复时失败。⚠️ 通用接口 `getAppVersionName()` / `getAppVersionCode()` / `getAppVariant()` 与平台 API `appCtx.packageName` **不受影响，勿顺手删**。存量装机已播种的那条书源**保留不动**（无法区分系统播种与用户自建，主动删会误删用户数据）。
- **内置主题预设的可见性（10060 起播种）**：主题管理页列的是 `themePackages/{day,night}/` 下的**目录**（`loadLocal()` 的 `listFiles()`），**不读 `ThemeConfig.configList`**。10054 的 `MD3·墨墟`/`MD3·琴女` 预设原本只作为 `configList` 的**资产来源**存在、从未物化成目录，故 10060 之前**没有任何入口能选到**；10060 起由 `ThemePackageManager.seedBuiltinPresetsOnce()` 在**首次进入主题管理页**时落成普通主题包（可应用/可编辑/可删除）。⚠️ 三个不可改回的点：① 判据必须是 `LocalConfig.builtinThemePresetSeeded` **一次性标记**，改成「目录是否存在」会让用户删掉的预设复活；② 落包前必须 `resolvePresetBackgrounds` 解掉 `@asset:` 前缀，否则背景静默丢失；③ 播种时机是**进主题页**而非 App 启动。⚠️ **不要相信「`themeConfig.json` 存在会遮蔽主题预设」**——该遮蔽只影响读 `configList` 的资产合并，与主题管理页无关（唯一消费者 `ThemeListDialog` 是死代码）；2026-09-21 曾据此误判「共存版开过主题页导致预设消失」，**已证伪**。
- **内置主题预设改名后必须统一「已物化」判据（10063 确立；10062 因此翻车）**：落包入口有**两个**——`seedBuiltinPresetsOnce` 与 `ensureLocalAppliedTheme`——而主题管理页**纯目录扫描、不去重**。预设改名后旧名目录仍留在存量设备上，**只堵一个入口就会同主题并列两条**（10062 只堵了播种：`ensureLocalAppliedTheme` 用默认兜底名「白」查不到旧名目录「黑猫慢生活」→ 落出 `day/白` 空壳，日/夜各 4 条）。
  - ⚠️ **判据必须唯一**：`ThemePackageManager.findMaterializedPreset(isNightTheme, name)`——**连同旧名一起查，命中即返回该条目**；**任何新增落包入口都必须复用它**，不允许各自内联「只查新名」的目录检查。回归锁 `everyMaterializationEntryPointSharesLegacyAwarePredicate` 会失败。
  - ⚠️ **日后再改预设名时，必须把旧名补进 `presetLegacyDirNames`**，否则重复条目重现。
  - **作者选择方案 A「不动存量」**：旧名目录存在即跳过，**既不新建也不改名**；存量设备继续显示旧名，只有全新安装才显示新名。
  - ⚠️ **`白`/`黑` 预设 `backgroundImgPath` 本来就是 `None`（纯色预设），`bg=None` 不是空壳判据**；判别空壳要看 `primaryColor` 是否为预设真值（`#ffecebe9`/`#ff333333`），带背景的是 `MD3·墨墟`/`MD3·琴女`（`background.jpg`）。

- **听书按章节定时的额度守卫（10112 起）**：额度只在「引擎自然读完本章且本章真的推出过音频」时消耗——判定必须走顶层纯函数 `decideChapterConsumption(syncView, timeChapter, chapterAdvanced)`（10115 抽出，便于直接断言行为），**不得退回无条件递减**；`chapterAdvancedAloud()` 必须保持 `open`，书源音频由 `SourceAudioReadAloudService` 按播放进度重写（10115 修复「永不消耗」回归，10116 抽成纯函数 `audioChapterAdvanced()`）；**任何「额度已扣才发现无法继续」的失败路径都必须给 `stopReadAloudOnInvalidPosition` 传 `refundChapter = true, toast = true`**，子类有自有推进基准的必须重写 `onChapterConsumed()` 复位（10116）；跨章推进失败必须 `timeChapter++` 归还并重新派发 `READ_ALOUD_DS_CHAPTER`，失败点不得再写裸 `stopSelf()`。回归锁 `ReadAloudChapterTimerGuardTest`。⚠️ 语义事实：档位把**当前章算第 1 章**，已由文案「含本章」明示，别再把「听完 5 章」实现成「再读 5 整章」。
- **实时缓存的「快速重试」必须保持可选且默认关闭（10114 确立）**：`AppConfig.ttsCacheFastRetry` 默认 **false**——关闭时必须是原来那套保守行为（只有从派发起算的单段超时、无干涸判据）；开启后单段超时固定 `FAST_RETRY_SEGMENT_TIMEOUT_SECONDS = 30` + 队列干涸 `REALTIME_DRY_STALL_GRACE_SECONDS = 15` 判定引擎挂死并走 `handleSpeakError(retryWithReinit = true)`。三条不可改回的点：① `armRealtimeDryWatchdog` 入口必须有 `if (!AppConfig.ttsCacheFastRetry) return`；② `realtimeDryIndex` 去重必须保留（同一单元重复武装会因 `retryParagraphKey` 二次命中而**直接跳段**）；③ 撤销点 `playWavFile()` / `pauseReadAloud()` / `resetWavPlayback()` 缺一不可；④ 看门狗必须用 `AppConfig.ttsRealtimeSegmentTimeoutSeconds`，不得改回直接读 `ttsCacheSegmentTimeoutSeconds`。回归锁 `ReadAloudRealtimeDryWatchdogTest` 6 项。
  - ⚠️ 上游真相（排查同类卡顿时先用）：卡住往往是**语音引擎自己崩了**——`logcat -b crash` 里 `org.Multi_TTS` 的 `Fatal signal 6 (SIGABRT) … Abort message: 'Pointer tag for … was truncated'`，backtrace 落在 `libBDSpeechDecoder_V1.so`（百度 SDK tagged-pointer 缺陷），崩溃时刻与「派发后无声」完全对齐；legado 只能缩短恢复时间，根治靠换引擎。
  - ⚠️ 日志通道（2026-11-01 修正旧误判）：**`AppLog` 会以 tag `Legado` 写 logcat**（`I/Legado (pid): AppLog …`），release 包也能 `adb logcat -d | grep Legado` 读到，不必等用户开「记录日志」。
- **脚本引擎的配置入口只有 `TtsEngineManageActivity` 的「脚本选项」，音色入口只有「选择音色」（`TtsEngineStore.selectVoice`，只对**已启用**引擎生效），不要搬进引擎选择弹窗（那里行点击=选中、长按=进详情页）。**内置百度引擎已于 10119 按作者要求移除**（`RETIRED_ENGINE_IDS` 会清理存量副本与遗留单选键），不要恢复；新增内置脚本只加 `assets/defaultData/tts/*.js` 并登记进 `DEFAULT_SCRIPT_ASSETS`。
- **禁止缓存 `viewTreeObserver` 局部引用后自行移除监听（10101 确立，主线程崩溃级）**：`View.getViewTreeObserver()` 在 View **尚未 attach** 时返回的是 View 内部的临时 `mFloatingTreeObserver`；attach 时 `View.dispatchAttachedToWindow` 调 `info.mTreeObserver.merge(mFloatingTreeObserver)` 把监听**搬进真实 observer 并 `kill()` 掉这份临时对象**（`mAlive=false`）。监听仍会被派发，但回调里对**缓存的旧引用**调 `removeOnGlobalLayoutListener` / `removeOnPreDrawListener` 会先过 `checkIsAlive()` → `IllegalStateException: This ViewTreeObserver is not alive`，主线程 FATAL。
  - 正确写法三选一：① 每次从 View 现取（`view.viewTreeObserver.add/remove`）；② 移除前查 `isAlive()`（本仓库范例 `AnimationExtensions.doAfterFirstDraw`）；③ 直接用 androidx `doOnLayout` / `doOnPreDraw`（`OneShotPreDrawListener` 内部已含 `isAlive()` 回退，且 detach 时自动移除）。
  - ⚠️ **在 `onCreate` / `onViewCreated` 里注册布局回调是最高危场景**：此时控件必然未 attach。10024 的「朗读面板定位就绪才显示」重放时踩中此坑，10095~10100 期间「听书状态重进阅读页」必崩。
  - 回归锁：`ReadAloudPanelPositionGuardTest.panelsArePositionedBeforeRevealed` 含**反向断言**，禁止该函数体内再出现 `viewTreeObserver` / `removeOnGlobalLayoutListener`。

- **蓝牙按键映射与音量键：6 项与上游的有意差异不得合回上游形态**（台账 `docs/朗读引擎-上游对齐差异台账.md`）：① 音量键翻页闸门 `volumeKeyPage`/`volumeKeyPageOnPlay` 必须保留（上游已删），`handleVolumeKey` 里 `ACTION_PREVIOUS_PAGE`/`ACTION_NEXT_PAGE` **必须走 `handleKeyPage(direction, longPress)`**，否则长按连翻十几页并丢掉 600ms 防抖；② 音量键朗读态默认动作是翻页不是 `ACTION_NONE`（否则开了闸门的用户按键无反应）；③ 播放暂停键未朗读时走 `MediaButtonReceiver.readAloud()`，不得直调 `ui.toggleReadAloud()`（会绕过 `readAloudByMediaButton`/`mediaButtonOnExit`/`MEDIA_BUTTON`）；④ `readerInstanceRef` 只在 `onStart` 设置、**只在真 onDestroy 清**（`onStop` 里清会让熄屏蓝牙翻页失效）；⑤ 书源音频的预测字幕**只在绑定成功后**才替换 `mapping`（上游先替换后 bind，本服务 `sessionChapterCanStartWithoutText = true`，返回 null 后永不重试⇒整章预测静默失效）；⑥ 预测位置发布门槛必须走 `shouldPublishTextPosition(...)` 并同时管页码与 TTS 位置事件。回归锁 `BluetoothKeyMappingGuardTest` / `SourceAudioChapterTimerGuardTest` / `PredictedTextPositionTest`。
- **蓝牙按键映射入口在阅读菜单**（`R.id.menu_bluetooth_key_mapping`），**不在**朗读设置里；朗读设置 Basic 组只有「蓝牙朗读切段间隔」一行。`mediaButtonPerNext` 已按上游删除（常量、读点、pref 行、文案全删），不要复活。
- **阅读排版预设按数组下标寻址**：`DefaultData.readConfigs` 由 `ReadBookConfig.getConfig(index)` 直接取用，`readStyleSelect` 是 **Int**。**只允许在数组末尾追加**，改名安全但要同步 `BuiltinPresetAssetTest.READ_PRESET_HEAD`；插入/重排会让存量用户当前排版整体位移。唯一比较预设**名字**的地方是 `ReadBookConfig.kt` 的 `isOldFormatConfigList`（旧格式迁移），改名经实跑验证不影响其布尔结果（下标 0 先命中 `||` 短路）。
- **语言裁剪边界**：`resConfigs "zh"` **会裁掉同语言 region 变体**（`zh-rHK`/`zh-rTW` 与繁体、其他语言一样被裁，只保留精确 `zh`）。产物实测 `locales: '--_--' 'zh'`、`unzip` 中 HK/TW 计数为 0，故 `values-zh-rHK|rTW` 是**不进 APK 的死资源**（已于 10038 删除），不存在"HK/TW 回退到简体或英文"的情形。详见 §6 的语言裁剪边界注。
- **备份打包清单的两类路径（10044 确立、10045 补强，改动前必读）**：给 `ZipUtils.zipFile` 的路径分两类——「本次流程自己创建/校验的」可直接传，「依赖用户配置才存在的」必须先 `exists()` 过滤。⚠️ 但**过滤时点**同样关键：由本次流程**稍后**才创建的目录（如 `covers`，`prepareCustomCoverBackup()` 才建）**不能放进 `backgroundAssetDirNames` 交给存在性判定**，否则会被提前跳过 → 数据静默漏备份（10045 修）。正确做法：先让创建者建好目录并补齐内容，**再按"目录里实际有什么"判定**（`coverDirShouldBeZipped(File)` 判 `listFiles()` 非空）。⚠️ **判据必须锚定"最终要被打包的那个对象的状态"，不能锚定"本次流程做了什么动作"**——`prepareCustomCoverBackup()` 对**已在该目录内**的封面会跳过拷贝，用"本次拷了几个"判定会把最常见的场景误判为空（10045 首版实现即犯此错，被实机回归抓到）。
- **备份范围决策：`null` 只表示「全选」，不得再兼作「中止」（10098 确立，改动前必读）**：「默认备份内容」的全选语义由 `BackupTargetConfig.selectedTargetsOrNull()` 承载——**全选返回 `null`**（不下发清单，让日后新增目标自动包含）。10073~10097 的 UI 收口写成 `selectBackupTargets() ?: return@launch`，把「全选」当「用户取消」⇒ **默认状态（未动过该设置＝全选）点「备份」毫无反应**，无进度框/无 toast/无文件。
  - **唯一判据** `decideBackupScope(selection)` → `Proceed(targets)`（`targets` 可为 null）/ `Abort`，两语义**互斥**。**任何新增备份入口都必须走它**，不得自行判空或内联 `isAllSelected`。
  - ⚠️ 它**刻意放在 `BackupConfig.kt` 顶层**而非 `BackupTargetConfig` 内：后者初始化读 `appCtx`，JVM 单测求值即 `ClassNotFoundException: android.app.ActivityThread`——放进去就没有能证明**行为**的测试。回归锁 `BackupScopeDecisionTest`（3 项，**直接调真实纯函数**）。
  - ⚠️ **自动备份侧（`Backup.kt`）把 null 直传 `Backup.backup` 是正确的**（null＝全打包），缺陷只在 UI 收口处，不要"顺手统一"。
  - ⚠️ 教训：`BackupTargetConfigTest` 那套**源码字面量断言拦不住这一类缺陷**——当时 `return null` 还在、文本全"正确"，坏掉的是**语义**。涉及"同一值两种含义"的改动必须配真行为断言。
- **⚠️ 已知继承缺陷（2026-09-15 审查登记，作者决定不修，后续审查勿重复上报）**：以下两项是**上游自带**缺陷（上游 `v3.26.091403` 仍未修），**刻意与上游保持一致**以降低同步成本：
  1. **`exportWebDav(uri,…)` 三处调用点未接异常**（`ExportBookService.kt` 的 `exportPdf`/`exportEpub`/`save2Drive` 裸调用）。10043 把该重载改为抛异常契约，同文件 TXT-ZIP 与 `uploadExportToWebDav` 已接住，这三处没有 → 异常冒泡到导出循环 `catch (e: Throwable)` → 本地文件其实已写好却被计入 `failedExports`（**不崩溃**）。⚠️ 修它需给 `exportPdf`/`exportEpub`（返回 `Unit`）改签名并调整调用点消费链，**非"3 行"改动**。
  2. **恢复回滚不含数据库**：`RestoreJournal.buildSnapshotTargets` 不登记 `legado.db`，而 `Restore.kt` 的 `restoreDbData`（SK 10037 引入）事务真实提交 → DB 段之后的步骤失败或进程被杀时，`rollbackNow()` 只还原配置文件、**DB 保持备份内容**（prefs 与 DB 错位）。⚠️ **两条看似显然的修法均已被证伪，勿照做**：① **把 DB 段挪到最后会破坏 `repairLocalCoverPaths`**（其读 `bookDao.all` 回写，必须在 `restoreBackgroundAssets` 之后、且在 DB 恢复后），会让新恢复的书**从未被修复封面路径**且无报错；② **把 DB 纳入快照不可行**：`appDb` 是顶层 `val … by lazy`，全库无 close/reopen 入口。若日后要修，可行方向是把 `RestoreJournal.begin` 下移到 `restoreDbData` **之后**（不动步骤顺序），但须先核查其状态机与 `App.kt` 的 `recoverIfNeeded` 假设。
- **⚠️ 脆弱点（登记）**：`ThemeConfig.kt` 有 `putPrefInt(PreferKey.uiLayoutAlpha, …)` **绕过 `AppConfig.uiLayoutAlpha` 的 setter 直写 pref**。当前无害的唯一原因是 `getPrefInt(PreferKey.uiLayoutAlpha)` 全库 **0 个读取点**；**若日后新增对该 pref 原始值的读取，即成为透明度锁的真实逃逸路径**。

### 设置默认值

每个设置的界面默认值与实际读取默认值必须一致：

- 界面默认值在 `app\src\main\res\xml\pref_config_*.xml` 的 `android:defaultValue`。
- 实际默认值在 `AppConfig.kt` 及各调用点的 `getPrefBoolean`、`getPrefInt`、`getPrefString`。`getPrefBoolean(key)` 不带默认参数时默认是 `false`。
- 修改任意设置默认值时，全库搜索该 key 的所有读取点，逐一核对类型和值；界面显示与实际行为不一致属于缺陷，不能接受“默认分支行为等价”作为理由。
- 背景图这类文件型默认值不能写成某台设备的绝对路径。必须把素材随 APK 提供，并由统一主题初始化在 `applyDayNightInit()` 前复制到应用私有目录，再为尚未配置的日间/夜间 key 写入该稳定路径。`backgroundImage` / `backgroundImageNight` 缺失表示从未配置；空字符串表示用户明确移除背景，后续启动不得覆盖。
- `uiLayoutAlpha` 的值表示“全局界面透明度”：`0` 为不透明、`100` 为全透明。数值到物理表面 alpha 的换算只能在 `UiCorner.uiLayoutSurfaceAlpha()` 中发生；普通 UI、底栏玻璃外壳和液态玻璃内容均复用该入口，业务页面不得再自行反向计算。

### 异步 UI 与局部模糊

- 只处理真实浮层表面或明确声明的背景层，禁止扫描控件树猜测目标；找不到可靠目标时应暴露问题，不能扩大为宿主 Activity 全屏模糊或纯色兜底。
- 几何、着色、描边与模糊底图必须由同一表面模型和同一裁剪路径管理。每个浮层实例使用独立背景副本，不能混用可变 Drawable 或叠加互相冲突的形状背景。
- 取图必须在目标和宿主 attach、且几何连续两帧稳定后进行。`PixelCopy` 源矩形必须使用源 Window 坐标并严格相交裁剪；不能用强制最小 1 像素矩形掩盖坐标错误。
- 首次可见前完成背景安装。关闭、换目标、重新显示和尺寸变化要使旧回调失效并释放旧位图；样式更新只更新样式，不应取消同一目标仍有效的取图，回调安装时使用最新样式。
- 禁止 `RenderEffect` 作用于宿主 `decorView`，以及 `setBackgroundBlurRadius` / `FLAG_BLUR_BEHIND` 等整窗模糊路径。若要改变浮层外壳几何，先分离外壳、背景层、内容层并完成模拟器全路径验证。

### 分层调试

- 常规问题先用模拟器 ADB、`uiautomator2`、截图和 logcat。
- 只有常规证据不足，且明确怀疑时序、线程、Window/Surface 合成或运行时调用链时，才升级到 Perfetto、Winscope 或 Frida。
- 高级证据必须围绕同一次复现采集：记录开始时间、操作、结束时间；将 UI 层级、时间线、Window/Surface 与调用证据对齐，明确观察结果、排除项、根因和结构性修复。
- 工具入口为 `tools\android-dev`，输出写入已忽略的 `test-records\android-dev`，不得提交 trace、截图、临时二进制或虚拟环境。雷电 Android 14 不支持的 WindowManager 时间序列 tracing 必须如实标为快照降级模式。
- Frida 仅能连接 `127.0.0.1:5555`，默认只读；方法跟踪须限定包、类、方法和最长 30 秒，不修改参数、字段或返回值。脚本错误、`Java is not defined` 或初始化缺失均为失败；结束后卸载脚本并移除模拟器临时 server。

理想环境操作：
uiautomator2 / ADB
        │
        ▼
──────── AI ────────
 │       │        │
 │       │        └── Perfetto
 │       │            看时间/线程/帧
 │       │
 │       └────────── Winscope trace
 │                    看 Window/Surface
 │
 └────────────────── Frida / AI Debug Probe
                      看真实运行时对象和调用链

## 5. 发布与版本控制

- 发布前重新执行第 3 节的 APK 验证（aapt badging + apksigner verify）。
- ⚠️ 迁移后：下述第 3、4 条里的「migrate 仓库 / 只读上游 CCSSNE / 指定代理端口 31180/31181 与 github.com 代理 10808」是旧机的单向推送环境，本机不复存在。本机检出即 `skxingyu/legado-sk` 目标仓库本身（git init + remote 后直接推 main），推送前先用 `git remote -v` 与代理环境实测确认通道，不要照搬旧机代理参数。
- 推送代码到 `skxingyu/legado-sk` 的 main：若走 gh CLI 直连可先 `gh api user` 确认可用；git 直连不通时用 gh token + 显式 URL（`$token = gh auth token`；目标仓库若 shallow，先 `git fetch --unshallow`）。具体直连命令写入 `companion\项目文档.md`（缺失时按实况重建）。
- 用 gh CLI 分步发布，避免大文件上传中断：先 `gh release create "<tag>" --title "..." --notes-file "<CHANGELOG路径>"`（pre/Beta 版加 `--prerelease`），再 `gh release upload "<tag>" "<APK路径>"`；上传大文件前如走代理受阻，按实测 `unset HTTPS_PROXY HTTP_PROXY; export GODEBUG=http2client=0` 处理。
  - ⚠️ **本机实测（2026-09-30，10076 发布踩到）：`gh release upload` 传 36 MB APK 会报 `HTTP 408: Upload body timed out due to inactivity`**，**重试同样失败**，且失败时**不会**留下半截资产（0 资产）。根因是本机环境变量里有 `https_proxy=http://127.0.0.1:10808`（另有 `NO_PROXY` 白名单，但**不含 github**）。
  - **解法（已验证一次成功）**：上传前**清空代理变量**再直连 —— `$env:HTTPS_PROXY=''; $env:HTTP_PROXY=''; $env:https_proxy=''; $env:http_proxy=''`，可再带 `$env:GODEBUG='http2client=0'`。
  - ⚠️ **注意 `git push` 与 `gh release create` 不受影响**（它们在本机走代理是通的），只有 **`gh release upload` 的大文件体**会被卡住。故不要因为 push 成功就以为上传也会成功。
  - 排查手法：`Invoke-WebRequest https://uploads.github.com` 返回 200 **不代表**大文件上传能过（小请求经代理没问题）；应直接看 `gh release view <tag> --json assets` 是否为空。
- tag 格式 `v3.26.<MMddHH>-<versionCode>`（如 `v3.26.082220-10018`）；发布后用 GitHub MCP `get_release_by_tag` 或网页复核 tag、目标提交、资产大小、中文排版与 Latest/prerelease 状态。
- **发布类型默认 Pre-release**：除非作者明说「发布正式版/Latest」，一律以 `--prerelease` 发布为预览版（不顶替当前 Latest）；正式/转正需作者另行指示才发布非 Pre。此前 10030/10033 等即按此惯例发布 Pre。

### Git

- 提交前检查 `git status`、`git diff`、`git log`。只暂存本次需要的文件，不提交 APK、构建日志、trace 或临时文件。
- 提交信息简洁且准确，遵循现有仓库风格。
- 每个独立修改在完成代码审查、且准备开始正式 APK 编译前，必须先创建一个只包含该已确认修改的 Git 提交；正式编译、安装和回归通过后，再提交版本基线与验证记录。发生回归时只允许从这些明确提交边界回退，禁止猜测性撤销工作区文件。

## 6. 当前交付基线

> **本节只保留「最近交付 + 一句历史指针」**，不再累积逐版流水。
> 逐版净增量、每个开关/红线由哪版引入 → 查 `companion\发布版更新记录.md`（**版本事实唯一权威**）；
> 一屏版本全貌 → 查 `companion\项目文档.md` §2.1「版本索引」；原始发布文案 → `companion\发布版更新原文-releasenotes.md` 或 GitHub Releases。
> ⚠️ **每次交付只在下面追加一条，并把最老的挤出去**（保持 ≤3 条）。历史版本**不要往这里写**——本文件是每次会话都要整份读入的运行手册，累积流水会挤占注意力。

> ⚠️ **默认分支 = `main`（2026-09-23 作者指示，长期规则）**
>
> **所有代码改动、编译、验证、产物收集一律以 `main` 为基底**，不需要每次向作者确认。其他分支（含 `feat/cleanup-cloud-backup`、`fix/review-r2`、`main-v2` 等）**默认一律不管**，除非作者在本次对话中**明确点名**某个分支。
>
> - **`feat/cleanup-cloud-backup`（10066–10072：按备份清理支持云端 + 备份/恢复共用锁）是试验分支**，作者尚未决定是否合并（仍有一些小问题）。**默认忽略它**：不要检查它、不要合并它、不要基于它编译、不要把它记为「当前交付」。
> - ⚠️ **它的 versionCode 区间（10066–10072）不作为递增基线**，因为该分支未合并回 main。
> - 若作者明确点名该分支并要求在其上工作，那是**本次对话的一次性例外**，做完仍回到「默认 main」。
>
> **下一次交付 versionCode 从 `10121` 递增**。

---

- ✅ **10120（`3.26.110914c`）—— 当前交付，未发布**（系统 TTS 无回调不再永久静默）：
  - 分支 **main**。**无 DB 迁移**（版本仍 117）。单提交 `127b7176`。
  - **起因（作者，2026-11-09）**：平板朗读「读一点就断掉」—— 声音停、通知栏仍在、朗读没退出（silent pause），怀疑 10119 回归或 MultiTTS 问题，要求在平板上排查。
  - **根因（证据确认）**：断在系统 TTS 引擎侧。MultiTTS（`org.nobody.multittt` 1.8.3）当天 `logcat -b crash` 崩 5 次（`libBDSpeechDecoder_V1.so`，`Abort message: 'Pointer tag … was truncated'`）；其 `/sdcard/Android/data/org.nobody.multittt/files/log/daily_*.log` 记有 `qttsAudioGet: status=1, err=0`（讯飞系 `msctts_*`）与 `bdotts` 的 `Socket closed`。legado 侧 `TTSReadAloudService` 只有两个缺口：`initTts()` 无初始化超时、朗读中无「无回调」看门狗 —— 引擎崩溃/请求丢失时 `UtteranceProgressListener` 三个回调都不来，也不走 `handleSpeakError`，于是服务与通知在、永久无声。
  - **修法**（`app/src/main/java/io/legado/app/service/TTSReadAloudService.kt`）：新增 `TTS_INIT_TIMEOUT_MS = 15_000L`（`onTtsInit` 超时 → 重建一次；再超时 `reportInitFailure()` + toast + `pauseReadAloud(false)`）与 `TTS_STALL_TIMEOUT_MS = 45_000L` / `TTS_STALL_POLL_MS = 5_000L`（轮询 `lastTtsCallbackAt`，`onStart`/`onDone`/`onRangeStart` 更新；超时走 `handleSpeakError("tts no callback", retryWithReinit = true)` → 重试本段，第二次失败跳段）。看门狗在 `clearTTS`/`playStop`/`pauseReadAloud`/`handleSpeakError` 撤销，`ttsWavMode` 不启用。
  - **平板实测（`HA1KAPWG`，10119→10120，`firstInstallTime` 仍 `2026-10-30 14:00:50`）**：15:01:58 起朗读 → 15:02:09 `am force-stop org.nobody.multittt` → **15:02:48 日志 `[朗读] 45000ms 无引擎回调，按引擎无响应重试本段`** → `stop/shutdown failed: not bound to TTS engine` → `initTts` 重新绑定成功 → 继续朗读（15:04 仍在读）。即这条断流路径确实没有任何引擎回调，修前会永久静默。
  - **模拟器回归（`emulator-5554`，10119→10120，`firstInstallTime` 仍 `2026-10-15 18:55:12`）**：正常朗读 60s+ 位置持续推进（pos 255→712），`无引擎回调` 0 次（无假阳性），`crash` 无本应用 FATAL。
  - **产物**：正式版 36,214,983 B ＋ 共存版 36,215,009 B；已在 `release/`（固定名 + 两个版本名），10119 旧包暂留。
  - **真机手感与根因**：MultiTTS 崩溃本身未修（百度离线 SDK 缺陷，改不动）；断流后最多静默 45s 自动恢复。

- ✅ **10119（`3.26.110816c`）—— 历史交付，未发布**（移除内置百度脚本引擎 + 修复朗读引擎弹窗点选卡顿）：
  - 分支 **main**。**无 DB 迁移**（版本仍 117）。提交链 `acefb84f`（移除两条百度脚本 + 清理残留）→ `89b5fe1d`（弹窗卡顿修复 + 先启用提示）。
  - **起因（作者，2026-11-08）**：百度短文本合成免费额度虽足够日常使用，但作者认为后续定价偏高，要求**删掉两条内置百度引擎**；同时发现「切换朗读引擎时明显卡顿」，要求在模拟器上排查。作者明确「选择发音人功能保留」。
  - **卡顿根因**（实测定位，非猜测）：`SpeakEngineDialog.upTts()` 把与行无关的三次判定（`ReadAloudEngines.byId` / `TtsEngineStore.scriptEngineForSelection` / `GSON.fromJsonObject`）放在 `sysTtsViews.forEach` **内部**，12 行即 12 次 `TtsEngineStore.engines()` 全量重建；而 `engines()` 每次都要 Gson 解析 `ttsEngineV2SettingsJson`（已存引擎连脚本一起存，模拟器上 575 KB）并对每个引擎做 3 次 Room 查询（`voices(id)` / `ttsEngineRuntimeDao.get(id)` / `ttsVoiceDao.lastUpdatedAt(id)`），≈36 次查询。`refreshScriptEngineRows()` 另在每行 footer 里各调一次，形成 Σk 次重建。实测 `Davey! duration=1388ms/1553ms`、`Janky 40%`。
  - **修法**：① 三次判定提到 forEach 外，循环内只比较 tag；② `refreshScriptEngineRows()` 改为一次 `engines().associateBy { it.id }` 快照 + 新增 `bindScriptEngineRow(row, engine)`；③ `addScriptEngineRow` 签名改为接收 `TtsEngineSetting`，footer 是**懒构建**（`RecyclerAdapter.addFooterView` 只存 builder），必须在 builder 里用创建时的快照绑定——**首版把刷新挪到 forEach 之后一次性调用，导致内置脚本行全部消失**（`scriptEngineRows` 此时仍为空，`if (isEmpty) return` 直接跳过）。
  - **移除引擎**：删除 `assets/defaultData/tts/baidu_online_tts.js`、`baidu_fanyi_tts.js`，`DEFAULT_SCRIPT_ASSETS` 去掉两项；`TtsEngineStore` 新增 `RETIRED_ENGINE_IDS`，`savedEngines()` 读取时过滤并**一次性回写**（无需标志位，幂等），同时清掉遗留单选键 `PreferKey.ttsEngine`（无读取点，但残留 `script:baidu_fanyi_tts` 会误导排查）。`httpTTSHelp.md` 删掉两条推荐，保留 tsn+tok 手写示例。
  - **顺带修**：`选择音色` 对**禁用**引擎原先点了没反应（`selectVoice` 只处理 `enabled` 引擎，失败只写 AppLog），改为提示「请先启用「%1$s」」（新增 `tts_engine_v2_enable_first`）。
  - **验证（雷电 `emulator-5554`，10118→10119 覆盖，`firstInstallTime` 仍 `2026-10-15 18:55:12`）**：引擎列表已无两条百度，内置脚本行完整；点选行 **90th percentile 1350ms → 27ms**，无 Davey 超时；「选择音色」弹窗开启 90th 34ms、选中 7ms；启用阶跃星辰后选音色，pref 写入 `active_voice_id: qinqienvsheng` 成功；pref 内 `baidu_online_tts`/`baidu_fanyi_tts` 引用已清零。
  - **真机平板 `HA1KAPWG` 2026-11-08 覆盖 10118→10119**：`versionCode=10119` / `3.26.110816c`，`firstInstallTime` 仍 `2026-10-30 14:00:50`（升级保数据），启动后 `MainActivity` 为焦点，`crash` buffer 无本应用 FATAL。
  - **未验证**：`选择音色` 长列表（84 项）的滚动性能与真机手感。
  - **产物**：正式版 36,213,188 B ＋ 共存版 36,213,244 B；已在 `release/`（固定名 + 两个版本名），旧包按作者指示全部清理（10111/10112/10113/10117/10118 均未发布，已删）。**未发布 Release**。

- ✅ **10117（`3.26.110311c`）—— 历史交付，未发布**（朗读引擎对齐上游：蓝牙按键映射 + 书源音频按字幕时长预测段内位置）：
  - 分支 **main**。**无 DB 迁移**（版本仍 117）。提交链 `78173037`（P2 书源音频）→ `17f6a213`（P1 引擎侧 + 删 mediaButtonPerNext）→ `025741c2`（映射弹窗 + 回归锁）→ `d1129a20`（差异台账 + 红线）→ `99ee5474`（映射到的键一律视为已消费）→ `f09b2369`（切段间隔设置行接上点击与摘要）。
  - **起因（作者，2026-11-03）**：要求把朗读引擎对齐上游「阅读」——**只对齐引擎，UI 用 SK 自己的**。先做方案（`test-records/朗读引擎对齐上游-方案/方案.md`，含三份独立对抗式审查报告同目录），作者裁决 D1~D5 后开工。
  - **范围核定**：SK 朗读引擎是上游的**超集**，上游独有的引擎能力只有两项 —— ① 蓝牙按键映射（上游 `8a549792`+`bd0521de`+`ae15087b`+`659f4483`）；② 书源音频按字幕时长预测段内位置（上游 `bdc01c7a`）。`help/tts`、引擎插件、TTS 实体/DAO、`TtsCacheService`、`TTS.kt` 与上游基本一致（仅多 SK 的 `TtsCacheJanitor`/`TtsCacheSweep`）。
  - **① 书源音频预测（`78173037`）**：`AudioTextMapping` 新增 `withEstimatedTiming(durationMs)` / `paragraphFractionAt(timeMs, durationMs)`；`SourceAudioReadAloudService` 在 `onPlaybackStateChanged` 的 **`Player.STATE_READY`** 分支（`upReadAloudLoading(false)` 之后）用播放时长给字幕生成估计时间轴（**只在 `bindMappingToLayout` 返回非 null 后才替换 `mapping`**），`syncTextPosition` 去掉 `if (paragraphUnchanged && !syncView) return`，改为按 `fraction → offset → predictedPage` 发布，门槛抽成 `shouldPublishTextPosition(...)` 同时管页码与 TTS 位置事件。
  - **② 蓝牙按键映射（`17f6a213`+`025741c2`）**：`BluetoothReadAloudSkip`（400ms 间隔排空队列）、`BluetoothKeyMapping`（7 键 × 阅读/朗读双槽 + `dispatch`）、`BluetoothKeyMappingDialog`（阅读菜单「蓝牙按键映射」入口，布局套 `NestedScrollView` + `vw_bg` 真实表面）、`MediaButtonReceiver.handleReaderMediaKey`（前台阅读页 → 熄屏时 `readerInstance()`）、`BaseReadAloudService` 媒体会话四个回调先问映射、`ReadBookActivity` 的 `onKeyDown`/`onKeyUp` 统一入口（`handleBluetoothMediaKey` / `handleVolumeKey` / `isVolumeKeyConsumed`）。`PreferKey.mediaButtonPerNext` **按上游删除**（常量/4 处读点/pref 行/2 条文案全删，不做迁移）。
  - ⚠️ **6+1 项与上游有意不同**（台账 `docs/朗读引擎-上游对齐差异台账.md`，详见 §4 红线）：保留音量键闸门、音量键朗读态默认翻页、TOGGLE 走 `MediaButtonReceiver.readAloud`、预测只在绑定成功后生效、发布门槛共用、备份含 14 个映射键、映射到的键一律视为已消费。
  - **实机回归暴露并修掉的真缺陷（`f09b2369`）**：「蓝牙朗读切段间隔」是普通 `Preference`，上游把点击处理与摘要刷新放在 `ReadAloudConfigDialog`，初版只换了 pref 行与分组白名单 ⇒ 实机上是**点了没反应的死行**且 summary 永远是静态文案。补 `showBluetoothReadAloudSkipIntervalDialog()` + `upBluetoothReadAloudSkipIntervalSummary()` + 变更监听刷新，回归锁升到 4 项。
  - **验证**：全量单测 **347 项 / 10 失败**（＝10116 基线 337/10 ＋ 新增 10 项全绿；10 项仍为既有 `CacheTaskStoreTest` 9 项 + `ReadBookConfigTest.sanitize_clampsUnsafeLineSpacing`）；两处变异证伪已做（`shouldPublishTextPosition` 去掉 `predictedPage != pageIndex`、音量键 `ACTION_PREVIOUS_PAGE`→`ACTION_NONE` 均 RED）。`assembleAppRelease`+`assembleAppSk2` 成功，两包 `10117` / `3.26.110311c` / `aapt` 包名正确 / `apksigner` 退出码 0。
  - **雷电模拟器回归（`emulator-5554`，覆盖 10116→10117，`firstInstallTime` 未变）**：菜单「蓝牙按键映射」在 帮助 之后、弹窗双槽/9 个动作可选并落盘；音量加/减翻页且不触发系统音量；`MEDIA_PREVIOUS`（阅读式=无操作）静默消费；把上一曲映射成下一页后按键真的翻页；朗读中 `MEDIA_NEXT`→ 映射为下一章后章号 30→31、位置回到章首；朗读设置 基础 组「蓝牙朗读切段间隔」显示 `400ms`、点开输入框可改、确定后摘要刷新；全程 `crash` buffer 无 FATAL。截图在 `test-records/朗读引擎对齐-回归/`（37 张）。
  - **真机平板 `HA1KAPWG` 2026-11-03 覆盖 10114→10117**：`versionCode=10117` / `3.26.110311c`，`firstInstallTime` 仍 `2026-10-30 14:00:50`（＝升级保数据），启动后书架完整、`MainActivity` 为焦点应用、`crash` 无 FATAL。蓝牙按键与音量键的实机手感回归由作者在真机完成。
  - **未验证项**：书源音频「按字幕时长预测段内位置」的真机行为（需要带字幕的书源音频书，模拟器无素材；仅静态 + 单测覆盖）；熄屏 `readerInstance()` 路径未单独构造复现。
  - **产物**：正式版 36,204,291 B ＋ 共存版 36,204,430 B；已在 `release/`（固定名 + 两个版本名）。作者 2026-11-03 决定**暂不发布**（等真机试过），并清理掉 10114/10115/10116 三个未发布包；10111/10112/10113（同样未发布）暂留。

## 7. 当前机器环境与配套文档（2026-09-04 迁移后）

> 迁移后的电脑为全新环境：无 D 盘，原先 `D:\code\...`、`D:\OneDrive\桌面\Ai\legado-sk\`、`F:\leidian\LDPlayer14`、`F:\down\雷电模拟器14...` 均已失效或弃用。本节统一登记本机事实；若某条与实际不符，先核实再改，勿让 AGENTS 出现悬空路径。

### 7.1 环境快照（已逐项核对）

| 项 | 值 |
|---|---|
| 代码/构建唯一目录 | `C:\code\ai-code\legado-sk`（git 仓库，remote = `skxingyu/legado-sk`，main 分支） |
| JDK 17 | `C:\Users\skxingyu\AndroidDev\jdk-17.0.2` |
| Android SDK | `C:\Users\skxingyu\AndroidDev\android-sdk`（platforms 34/36；build-tools 34.0.0/36.0.0；cmdline-tools latest） |
| Gradle | 项目 wrapper `gradlew.bat`（gradle-8.14.4，dist 下载到 `C:\Users\skxingyu\.gradle\wrapper\dists`）；备用 `C:\Users\skxingyu\AndroidDev\gradle-9.7.1` |
| Gradle user home | 默认 `C:\Users\skxingyu\.gradle`（不显式设置） |
| 系统 adb | `C:\Users\skxingyu\AndroidDev\android-sdk\platform-tools\adb.exe` |
| 雷电模拟器（C 盘） | `C:\download\down\cloud-down\雷电模拟器14纯净绿色版+狐狸+LSP+微霸\LDPlayer14`（实例 `leidian0`=index 0） |
| sdkmanager | `cmdline-tools\latest\bin\sdkmanager.bat`（已装 android-36 / build-tools 36.0.0） |

shell 选择：默认 Git Bash（POSIX）；原生 Windows 工具 / `.ps1` / 需 PowerShell 场景改用 Pwsh。

### 7.2 配套工作文档（从外部工作目录收敛进仓库）

原散落在 `D:\OneDrive\桌面\Ai\legado-sk\` 的配套文档，迁移后统一收进 **`C:\code\ai-code\legado-sk\companion\`**（含 backup、release 也在 gitignored 位置）。该目录已在 `.gitignore` 忽略，含机器信息，绝不推送公开仓库。

**现有 companion 工作文档**（新会话先读 §0；此处为目录概览，避免歧义）：
- `companion\项目文档.md` —— 项目概览 + **§2.1 版本索引**（一屏全貌 + 版本定性；逐版细节在下一份）。
- `companion\发布版更新记录.md` —— **版本事实的唯一权威来源**：§0 防改错速查 / §1 逐版净增量 / §2 功能→版本反查表。
- `companion\发布版更新原文-releasenotes.md` —— GitHub Releases 逐版作者原文全文（备份至 10037，可 grep）。
- `companion\发布说明-<versionCode>.md` —— 逐版 GitHub Release 发布说明草稿（本地文件名对应版本号，如 `发布说明-10086.md`）；发布前照此写 `gh release create --notes-file`。⚠️ 资产名用 ASCII 后缀 `_sk2-coexist`。
- `companion\基底重植方法论与10036记录.md` —— **基底重植方法论**（新枝重植怎么做、通用移植原则）+ **⭐ 重植完整性核查方法**（§2，下次重植必跑）+ 10036 那次重植的定性。⚠️ **不是待执行方案**，其中的逐项移植清单已删除（行号已过期）。

**已确认不存在、且不再计划创建的配套文档**（历史上有引用，2026-09-29 清理时核对：这些文件从未存在过，**不要按旧引用去找**）：
- `companion\编译注意事项与排错手册.md` —— 内容已由本文件 §3「本机环境与正式命令」+「长命令和构建失败」覆盖，**不另建**。
- `companion\代码审查报告-第 N 轮.md` —— 各轮审查结论已并入 `发布版更新记录.md` §0 与本文件 §4「功能红线」，**不另建**。

**确实缺失、需要时可重建的本机产物**（不属于文档，丢了随时可再生成）：
- `release\legado_sk_3.26.090212c_10026_arm64-v8a.apk` —— 旧产物，当前检出没有；需要时从 GitHub Release 重新下载。
- `backup\` —— 真机数据备份，本机尚无。

**已归档（2026-09-10 文档精简，源码见 `test-records/doc-cleanup/archived/`）**：`新上游基底重做清单.md`（自述使命完成，独有信息已迁入 `项目文档.md` §3 与 `发布版更新记录.md`）、`移植方案-上游三项增量.md` / `移植方案-换源弹出卡片.md`（结论已并入 `发布版更新记录.md` 10031/10029 节）、`docs/ui-design-spec.md`（与 §4 重复的陈旧分叉，且唯一差异的"工作模式分级"经作者裁决已废止）。

**用途约定**：AGENTS.md 本身随公开仓库分发（其中 §2/§3/§7 为机器专属运行信息）；凡机器/本机信息应只写进 AGENTS 检出副本与 `companion\`，不要新增进公开可读的 README/docs。本机 `~/.dsh/AGENTS.md` 为本机级总则，与仓库 AGENTS.md 并行。

**子代理中间产物**（见 §1.5）：探查报告、审查结论、运行时的 UI dump / 日志一律写入 `test-records\<任务名>\`（同样被 `.gitignore` 忽略），**不提交、不推送**；交付完成后可清理临时 dump，只保留方案与审查结论备查。

### 7.3 仓库检出状态与首启清单

- 当前目录已是完整 git 仓库（2026-09-05 `git init` 并对齐 `origin/main` 历史，gh auth 直连推送成功）；提交时勿把机器路径/`companion\`、`release\`、`build_logs\` 误提交。
- 首次正式编译前：确认 SDK36/build-tools 36 已装（已装）、`android-36` platform 存在；首次 `gradlew.bat` 会自动下载 gradle-8.14.4（联网）。
- android-dev 高级调试工具链依赖 `.android-dev-venv\`（uiautomator2/frida/adbutils）与 `tools\android-dev\bin\frida-server-17.17.0-...`，本机未就绪；仅当需要 Perfetto/Winscope/Frida 分层调试时再重建，不影响常规编译/模拟器回归。
