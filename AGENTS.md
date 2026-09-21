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
5. **机器环境/路径** → 本文件 §7「当前机器环境与配套文档」§7.1 环境快照、§7.2 配套文档与缺失项。
6. **动手前决定用几个子代理** → 读 §1.5「子代理编排」。大范围审计/排查**默认拆并行**，串行单代理是已知失败模式；边界（只读、写域切分、主代理收口）见该节。
7. **改朗读链路前必读** `docs/朗读链路.md`（朗读状态所有权契约，10023 架构的唯一细节来源）；**改造缓存/评论任务域前必读** `docs/重构下载架构.md`（缓存域唯一架构契约）。这两份是本仓库唯一描述内部契约的设计文档。
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

| 设备 | 型号 | 序列号 |
|---|---|---|
| 手机 | 华为 MAR-AL00 | `9HQDU19903003356` |
| 平板 | 联想 TB-9707F | `HA1KAPWG` |

- 所有 `adb` 命令必须显式带 `-s <serial>`；执行前确认目标设备，禁止裸 `adb`。
- 真机安装统一 `adb -s <serial> install -r legado-sk-arm64-v8a.apk`（同 debug 签名，覆盖升级保数据）；最终验证由用户真机手动完成。
- 换签名迁移数据走 run-as tar 打包流程：导出必须用 Python subprocess 二进制流（git bash `>` 重定向会 CRLF 污染 tar）；`pm uninstall -k` 不可行（数据绑定签名）。备份与导出在仓库根的 `backup\`（被 .gitignore 忽略）。
- 真机问题优先依据用户描述、代码和用户提供的日志排查。
- ⚠️ 迁移后本机（2026-09-04）`adb devices` 为空：真机并未接入，回归以雷电模拟器为准；真机安装/验证仅当用户已接上设备并明确指示时进行。

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
- 编译前必读 §7 的《编译注意事项与排错手册》（迁移后已并入仓库，见 §7）；若尚未创建，按 §7 指引补充后再编译。

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

> 历史：10004–10006 曾以 `sk2` 共存版随正式版双发，10007 起停发并写明「不再提供 sk2」。**2026-09-21 作者决定恢复**，本日起每次正式编译都**必须同时**产出共存版，文档口径已同步更正。

- 变体 = `app` flavor + `sk2` buildType（**buildType，不是新 flavor**），包名 **`io.legado.app.sk2`**，应用名同「阅读SK」；与正式版 `io.legado.app.c`（`app`+`release`）、阅读C 三者可同时安装。
- 产物在 `app\build\outputs\apk\app\sk2`；⚠️ **注意 `sk2` 是 `debug` 系 buildType（`initWith debug`，继承 debug 源集），产物目录就是 `app\sk2` 本身**（debug 的 `debug` 源集只被 `debug` 变体使用，不影响此路径）。
- Gradle 输出名同正式版（`legado_sk_<versionName>_<versionCode>.apk`，无 `_arm64-v8a` 后缀），**收进 `release/` 时按约定补后缀并加 `_sk2`**。
- ⚠️ **共存版与正式版共用同一 `VERSION_CODE` / `VERSION_NAME`，不另加版本后缀**（`sk2` 刻意**没有** `versionNameSuffix`）。两条产物的版本事实必须逐字一致，便于回溯「同一个 10058」。
- ⚠️ **两者同用 SDK debug 签名**：改的是包名不是签名，因此**不能互相覆盖安装**（这正是共存的前提）；也**不能覆盖安装阅读C**（签名不同，需走 §2 的数据迁移流程）。
- ⚠️ **共存版是独立应用、独立数据**：包名不同 → `filesDir` / `getExternalFilesDir(null)`（缓存、书籍、主题、pref、DB）全部隔离，**不共享书架与进度**。它是"并行再装一份"，不是"共用数据"；要同步数据走应用内备份导出/导入（WebDAV 或本地 zip）。私有目录之外的**用户可见路径共用**（如 `/sdcard/Download/yuedu` 导出目录、WebDAV 目录名），互导时注意覆盖。
- ⚠️ **无需为共存改任何源码**：隔离由 Android 平台按 `applicationId` 保证（不同包名 → 不同 uid / 数据沙箱 / `FileProvider` authority；manifest 中 `authorities` 全部是 `${applicationId}` 占位，`AppConst.authority` 取 `BuildConfig.APPLICATION_ID`）。共存版与正式版除包名外**无任何行为差异**（10059 起内置书源与其授权守卫已整体移除，见 §6）。
- 共存版**不发布 GitHub Release 资产、不参与更新检查**，只作为本地交付物收进 `release/`。

编译成功后必须把新 APK 收进仓库根已忽略的交付目录：
1. 覆盖 `C:\code\ai-code\legado-sk\release\legado-sk-arm64-v8a.apk`（「当前交付 APK」，固定名；`/release` 已被 .gitignore 忽略）。
2. 按版本命名同存于 `C:\code\ai-code\legado-sk\release\`：`legado_sk_<versionName>c_<versionCode>_arm64-v8a.apk`。
3. 共存版同存于 `C:\code\ai-code\legado-sk\release\`：`legado_sk_<versionName>c_<versionCode>_arm64-v8a_sk2.apk`。

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
- **原版共享偏好 key**：`BookCover.kt` 的 `legadoCoverRuleConfig` 是原版遗留 key，不能改名。
- **品牌与更新**：不做交流群（QQ 入口全删）；更新检查与仓库链接全部指向 `skxingyu/legado-sk`（`UpdateManager.GITHUB_API`、关于页 README 直连 `raw.githubusercontent.com/skxingyu/legado-sk/main/README.md`）；「更新设置」只存在于关于页，无启动自动检查。
- **内置书源与其授权守卫：10059 起永久移除，不要恢复**（2026-09-21 作者指示）。不得再引入 `defaultData/bookSources.json`、`DefaultData.builtinBookSources` / `seedBuiltinBookSourcesOnce()`、`LocalConfig.builtinBookSourceSeeded`、`JsExtensions.matchApp()` / `getAppName()` / `getAppPackageName()`、`AppInfo.packageName` / `appName`。回归锁 `BuiltinSourceRemovedTest`（已双向证伪）会在恢复时失败。⚠️ 通用接口 `getAppVersionName()` / `getAppVersionCode()` / `getAppVariant()` 与平台 API `appCtx.packageName` **不受影响，勿顺手删**。存量装机已播种的那条书源**保留不动**（无法区分系统播种与用户自建，主动删会误删用户数据）。
- **内置主题预设的可见性（10060 起播种）**：主题管理页列的是 `themePackages/{day,night}/` 下的**目录**（`loadLocal()` 的 `listFiles()`），**不读 `ThemeConfig.configList`**。10054 的 `MD3·墨墟`/`MD3·琴女` 预设原本只作为 `configList` 的**资产来源**存在、从未物化成目录，故 10060 之前**没有任何入口能选到**；10060 起由 `ThemePackageManager.seedBuiltinPresetsOnce()` 在**首次进入主题管理页**时落成普通主题包（可应用/可编辑/可删除）。⚠️ 三个不可改回的点：① 判据必须是 `LocalConfig.builtinThemePresetSeeded` **一次性标记**，改成「目录是否存在」会让用户删掉的预设复活；② 落包前必须 `resolvePresetBackgrounds` 解掉 `@asset:` 前缀，否则背景静默丢失；③ 播种时机是**进主题页**而非 App 启动。⚠️ **不要相信「`themeConfig.json` 存在会遮蔽主题预设」**——该遮蔽只影响读 `configList` 的资产合并，与主题管理页无关（唯一消费者 `ThemeListDialog` 是死代码）；2026-09-21 曾据此误判「共存版开过主题页导致预设消失」，**已证伪**。
- **内置主题预设改名后必须统一「已物化」判据（10063 确立；10062 因此翻车）**：落包入口有**两个**——`seedBuiltinPresetsOnce` 与 `ensureLocalAppliedTheme`——而主题管理页**纯目录扫描、不去重**。预设改名后旧名目录仍留在存量设备上，**只堵一个入口就会同主题并列两条**（10062 只堵了播种：`ensureLocalAppliedTheme` 用默认兜底名「白」查不到旧名目录「黑猫慢生活」→ 落出 `day/白` 空壳，日/夜各 4 条）。
  - ⚠️ **判据必须唯一**：`ThemePackageManager.findMaterializedPreset(isNightTheme, name)`——**连同旧名一起查，命中即返回该条目**；**任何新增落包入口都必须复用它**，不允许各自内联「只查新名」的目录检查。回归锁 `everyMaterializationEntryPointSharesLegacyAwarePredicate` 会失败。
  - ⚠️ **日后再改预设名时，必须把旧名补进 `presetLegacyDirNames`**，否则重复条目重现。
  - **作者选择方案 A「不动存量」**：旧名目录存在即跳过，**既不新建也不改名**；存量设备继续显示旧名，只有全新安装才显示新名。
  - ⚠️ **`白`/`黑` 预设 `backgroundImgPath` 本来就是 `None`（纯色预设），`bg=None` 不是空壳判据**；判别空壳要看 `primaryColor` 是否为预设真值（`#ffecebe9`/`#ff333333`），带背景的是 `MD3·墨墟`/`MD3·琴女`（`background.jpg`）。

- **阅读排版预设按数组下标寻址**：`DefaultData.readConfigs` 由 `ReadBookConfig.getConfig(index)` 直接取用，`readStyleSelect` 是 **Int**。**只允许在数组末尾追加**，改名安全但要同步 `BuiltinPresetAssetTest.READ_PRESET_HEAD`；插入/重排会让存量用户当前排版整体位移。唯一比较预设**名字**的地方是 `ReadBookConfig.kt` 的 `isOldFormatConfigList`（旧格式迁移），改名经实跑验证不影响其布尔结果（下标 0 先命中 `||` 短路）。
- **语言裁剪边界**：`resConfigs "zh"` **会裁掉同语言 region 变体**（`zh-rHK`/`zh-rTW` 与繁体、其他语言一样被裁，只保留精确 `zh`）。产物实测 `locales: '--_--' 'zh'`、`unzip` 中 HK/TW 计数为 0，故 `values-zh-rHK|rTW` 是**不进 APK 的死资源**（已于 10038 删除），不存在"HK/TW 回退到简体或英文"的情形。详见 §6 的语言裁剪边界注。
- **备份打包清单的两类路径（10044 确立、10045 补强，改动前必读）**：给 `ZipUtils.zipFile` 的路径分两类——「本次流程自己创建/校验的」可直接传，「依赖用户配置才存在的」必须先 `exists()` 过滤。⚠️ 但**过滤时点**同样关键：由本次流程**稍后**才创建的目录（如 `covers`，`prepareCustomCoverBackup()` 才建）**不能放进 `backgroundAssetDirNames` 交给存在性判定**，否则会被提前跳过 → 数据静默漏备份（10045 修）。正确做法：先让创建者建好目录并补齐内容，**再按"目录里实际有什么"判定**（`coverDirShouldBeZipped(File)` 判 `listFiles()` 非空）。⚠️ **判据必须锚定"最终要被打包的那个对象的状态"，不能锚定"本次流程做了什么动作"**——`prepareCustomCoverBackup()` 对**已在该目录内**的封面会跳过拷贝，用"本次拷了几个"判定会把最常见的场景误判为空（10045 首版实现即犯此错，被实机回归抓到）。
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
- tag 格式 `v3.26.<MMddHH>-<versionCode>`（如 `v3.26.082220-10018`）；发布后用 GitHub MCP `get_release_by_tag` 或网页复核 tag、目标提交、资产大小、中文排版与 Latest/prerelease 状态。
- **发布类型默认 Pre-release**：除非作者明说「发布正式版/Latest」，一律以 `--prerelease` 发布为预览版（不顶替当前 Latest）；正式/转正需作者另行指示才发布非 Pre。此前 10030/10033 等即按此惯例发布 Pre。

### Git

- 提交前检查 `git status`、`git diff`、`git log`。只暂存本次需要的文件，不提交 APK、构建日志、trace 或临时文件。
- 提交信息简洁且准确，遵循现有仓库风格。
- 每个独立修改在完成代码审查、且准备开始正式 APK 编译前，必须先创建一个只包含该已确认修改的 Git 提交；正式编译、安装和回归通过后，再提交版本基线与验证记录。发生回归时只允许从这些明确提交边界回退，禁止猜测性撤销工作区文件。

## 6. 当前交付基线

仅保留最近交付状态，下一次覆盖安装必须在此基础上递增：

- ✅ **10063（`3.26.092111c`）已构建并通过模拟器验证（2026-09-21）——当前交付（内置预设改名 + 重复条目修复）**：
  - **性质**：修缺陷（10061/10062 引入的重复主题条目）+ 预设改名。**无 DB 迁移**。⚠️ **未发布 Release**（作者要求先自行测试）。
  - **改动一：日/夜默认预设更名为「白」/「黑」**（`themeConfig.json` index 0/1，原「黑猫慢生活」/「黯夜」）。
  - **改动二：统一「内置预设是否已物化」判据**。主题管理页列的是 `themePackages/{day,night}/` 下的**目录**且**不去重**，而旧名目录已由 `ensureLocalAppliedTheme` 落在存量设备上 → 落包入口不认旧名就会同主题并列两条。
    - ⚠️ **10062 的失败教训（必读）**：旧名 guard 只加在 `seedBuiltinPresetsOnce`，**漏了真正落刀的 `ensureLocalAppliedTheme`**。后者取 `getThemeConfig` 的默认兜底名「白」去查目录，存量设备上只有旧名「黑猫慢生活」→ `readPackage` 落空 → 落出 `day/白` **空壳**（配色取自 pref 默认值而非预设资产），与旧名目录并列。实测日间 4 条、夜间 4 条。
    - **修法**：抽出唯一判据 `findMaterializedPreset(isNightTheme, name): Entry?`（连同旧名一起查，**命中即返回该条目**），`seedBuiltinPresetsOnce` 与 `ensureLocalAppliedTheme` **共用**；后者命中时直接返回既有包，不再落新壳。
    - ⚠️ **两个不可改回的点**：① 判据必须唯一，**任何新增的落包入口都必须复用 `findMaterializedPreset`**；② 日后**再改预设名时必须把旧名补进 `presetLegacyDirNames`**，否则重复条目重现。
    - **作者选择：方案 A「不动存量」**——旧名目录存在即跳过，**既不新建也不改名**；存量设备继续显示旧名，只有全新安装才显示「白」/「黑」。
  - **验证（雷电模拟器 emulator-5554，`io.legado.app.sk2`，受控实验）**：
    - **存量路径**：手工构造「只有旧名目录（`黑猫慢生活`/`黯夜`）+ 播种标记缺失」的纯净前置态 → 进主题管理页后**只多出 `MD3·墨墟`/`MD3·琴女`**，**`白`/`黑` 一个都没产生**；日/夜各 **3 张卡**，两次进出**条目数稳定**。
    - **全新安装路径**：`pm clear` 后进主题页 → 日 `MD3·琴女`/`MD3·墨墟`/`白`、夜 `…/黑`，各 **3 张卡**。`白`/`黑` 的 `primaryColor` 为预设真值（`#ffecebe9`/`#ff333333`），**不是** pref 默认值的空壳。
    - ⚠️ 注：`白`/`黑` 预设的 `backgroundImgPath` **本来就是 `None`**（纯色预设），`bg=None` 属正常，不是缺陷判据；区分"空壳"要看 `primaryColor` 是否等于预设值。真正带背景的是 `MD3·墨墟`/`MD3·琴女`（`background.jpg`）。
    - `logcat -b crash` 0 条；`io.legado.app.c` / `io.legado.app.sk2` / 阅读C（`io.legado.app.yuedu.a.release`）**三者并存**、`dataDir` 各自独立。
  - **回归锁**：`BuiltinPresetAssetTest.everyMaterializationEntryPointSharesLegacyAwarePredicate`（两个落包入口都必须引用共享判据）+ `materializedPredicateActuallyChecksLegacyDir`（判据必须真查旧名）。**已双向证伪**：摘掉 `ensureLocalAppliedTheme` 的 guard 即失败 `everyMaterializationEntryPointSharesLegacyAwarePredicate`。⚠️ 此前那版只断言「映射存在」的测试**拦不住这个 bug**，这正是它逃过 10062 验证的原因。
  - **验证**：全量单测 **186 项 / 10 失败**（10 项＝既有已知失败 `CacheTaskStoreTest` ×9 + `ReadBookConfigTest.sanitize_clampsUnsafeLineSpacing`，**无新增失败**）。
  - **产物** 两条（同版本号、同签名、仅包名不同）：正式版 `release/legado_sk_3.26.092111c_10063_arm64-v8a.apk`（36,165,002 字节）＋共存版 `release/legado_sk_3.26.092111c_10063_arm64-v8a_sk2.apk`（44,522,126 字节）；aapt 均为 `10063` / `3.26.092111c` / 阅读SK / arm64-v8a / locales `'zh'`，apksigner exit 0（证书 SHA-256 `79fef578…`）。`release/legado-sk-arm64-v8a.apk`（固定名）已更新为 10063 正式版。**已装机验证**。
  - **下一次交付 versionCode 从 `10064` 递增。**

- ✅ **10060（`3.26.092114c`）已构建并通过模拟器验证（2026-09-21）——历史交付（内置主题预设播种 + 排版预设「猫黄」更名）**：
  - **性质**：功能版（补一个**从未接通**的入口）+ 改名。**无 DB 迁移**。
  - **改动一：内置主题预设播种**。修的是一项**自 10054 起就存在的入口缺失**（不是回归）：主题管理页列的是 `themePackages/{day,night}/` 下的**目录**，而 10054 的 `MD3·墨墟`/`MD3·琴女` 预设只作为 `configList` 的**资产来源**存在、**从未物化成目录** → 4 套预设**在任何界面都不可见、无法应用**；唯一读 `configList` 的 `ThemeListDialog` 是**死代码**。
    - **修法**：新增 `ThemePackageManager.seedBuiltinPresetsOnce(context)`，在 `ThemeManageActivity.onActivityCreated` 中 `ensureLocalAppliedTheme` **之前**调用，把 `DefaultData.themeConfigs` 逐条**复用既有落包链路**（`saveConfig` → 拷资产 → 写 `theme.json` → `addConfig`）落成普通主题包 → **可应用、可编辑、可删除，零新增 UI**。
    - **配套**：`ThemeConfig.Config.resolvePresetBackgrounds` 由 `private` 提为 `internal`（`@asset:` → 可读绝对路径，`copyAssetsIntoPackage` 的前提）；`LocalConfig.builtinThemePresetSeeded` 一次性标记。
    - **验证**：清数据全新装 → 主题页日/夜各 **3 张卡**；「应用」生效（`durThemeNameNight=MD3·琴女`）；「编辑」打开编辑器且值预填（主色调 `#706B66`）；「删除本地」后**重进页面不复活**；`logcat -b crash` 0 条。**存量升级**：装 10059 进主题页只有 1 条 → 覆盖装 10060 → 自动补种为 3 条。**冷启动进主页不播种**（`themePackages/` 不存在），只有进主题页才落包。
    - **回归锁**：`BuiltinPresetAssetTest` 新增 `everyPresetSeedsToDistinctDir`（落点重复会**静默少一项**）与 `presetBackgroundPathsAreResolvable`（`backgroundImgPath` 只能是 `@asset:` 或绝对路径），两项均**已双向证伪**（注入同名冲突→前者失败 `expected:<6> but was:<5>`；注入相对路径→后者失败）。
  - **改动二：排版预设「猫黄」更名「黄」**（独立提交 `3617673f`）：仅改 `readConfig.json` 下标 3 的 `name`，**数组顺序不动**（下标寻址，`readStyleSelect` 是 Int）；同步 `BuiltinPresetAssetTest.READ_PRESET_HEAD`。唯一的名字比较点 `ReadBookConfig.isOldFormatConfigList` 已**实跑验证**改名不影响其布尔结果。
  - **⚠️ 更正 10054 的一条历史结论**：旧记「`themeConfig.json` 存在会完全遮蔽预设、导致看不到」**与主题管理页无关**（该页不读 `configList`）。2026-09-21 排查一度据此误判「共存版因开过主题页而遮蔽预设」，**已证伪**——正式版与共存版表现完全一致，预设是从来就没有入口。
  - **验证**：全量单测 **182 项 / 10 失败**（10 项＝既有已知失败 `CacheTaskStoreTest` ×9 + `ReadBookConfigTest.sanitize_clampsUnsafeLineSpacing`，**无新增失败**）。
  - **产物** 两条（同版本号、同签名、仅包名不同）：正式版 `release/legado_sk_3.26.092114c_10060_arm64-v8a.apk`（36,164,681 字节）＋共存版 `release/legado_sk_3.26.092114c_10060_arm64-v8a_sk2.apk`（44,521,721 字节）；aapt 均为 `10060` / `3.26.092114c` / 阅读SK / arm64-v8a / locales `'zh'`。`release/legado-sk-arm64-v8a.apk`（固定名）已更新为 10060 正式版。
  - **未发布 Release**（作者未指示；按 §5 若发布则默认 Pre）。**下一次交付 versionCode 从 `10061` 递增。**

- ✅ **10059（`3.26.092108c`）已构建并通过模拟器验证（2026-09-21）——历史交付（移除内置书源与其作者授权守卫）**：
  - **性质**：删除型改动（无新功能、无 DB 迁移、无需升级迁移）。作者指示：内置书源用的人变多，**不再内置**；同时移除「只有包名 `io.legado.app.c` + 应用名 `阅读SK` 才放行正文」的授权判定。
  - **改动范围（全为删除，`app/build.gradle` 仅随此前 sk2 改动，与本次无关）**：
    - 删除资产 `app/src/main/assets/defaultData/bookSources.json`（番茄书源 115,027 字节，含 `fqAuthOk` 守卫与 `FQ_AUTH_DENIED` 文案）。
    - `DefaultData.kt`：删 `builtinBookSources` 读取点、`seedBuiltinBookSourcesOnce()`、`builtinBookSourcesToSeed()`，以及 `upVersion()` 里的播种调用；顺带删两个因此变成未使用的 import。
    - `LocalConfig.kt`：删 `builtinBookSourceSeeded` 一次性标记（**该 pref 键残留在存量设备上无副作用**，不再被读写）。
    - `JsExtensions.kt`：删 `matchApp()` / `getAppName()` / `getAppPackageName()`——**这三个 JS 接口只为该守卫而加，全库无其它调用点**；`getAppVersionName()` / `getAppVersionCode()` / `getAppVariant()` 是通用接口，**保留**。
    - `AppConst.kt`：`AppInfo` 删 `packageName` / `appName` 字段及其赋值（同样只为守卫而加）。⚠️ `appCtx.packageName` 是平台 API，与本次无关，**不要一并删**。
    - 测试：删 `BuiltinSourceGuardTest`（6 项）与 `DefaultDataSeedTest`（3 项）——它们锁定的契约已不存在；新增 `BuiltinSourceRemovedTest`（3 项）**反向锁定本次移除**，防止资产与播种链路被无意恢复。
  - ⚠️ **存量装机已播种的那条书源刻意保留不动**（作者确认）：播种是一次性语义，升级后库里的「番茄小说 (SK特供)」不会自动消失，用户可自行长按删除。**不做主动清理**——因为无法区分「系统播种的」与「用户自己导入的同 URL 书源」，主动删会误删用户数据。
  - **验证**：
    - 单测全量 **180 项 / 10 失败**（10 项=既有已知失败 `CacheTaskStoreTest` ×9 + `ReadBookConfigTest.sanitize_clampsUnsafeLineSpacing`；10058 时为 186 项，差额 6+3−3 即删旧测试、加新测试）。`BuiltinSourceRemovedTest` 3/3 通过。
    - ⚠️ **新回归锁已双向证伪**：把 `bookSources.json` 临时放回 → `builtinBookSourceAssetIsGone` **失败**（断言在第 35 行），确认它不是恒真测试。
    - 产物级验证：两个 APK 内 `assets/defaultData/` 已无 `bookSources.json`（`unzip -l` 计数 0）；`classes*.dex` 内 `matchApp` / `fqAuthOk` / `FQ_AUTH_DENIED` / `SK特供` 字符串**全部为 0**。
    - 模拟器（emulator-5554）：10058 → 10059 覆盖安装（`versionCode=10059` / `versionName=3.26.092108c`）成功；启动进 `MainActivity`、`logcat -b crash` **0 条**、无 FATAL；书架数据保留。
    - ⚠️ **真·全新装机验证（关键证据，且踩过一次坑）**：必须用 `pm clear io.legado.app.sk2` **清空数据**后再启动，日志无任何「内置书源播种」行、DB 内 `book_sources` 计数 **0**。⚠️ 若只做覆盖安装就去看列表，会看到旧的「番茄小说 (SK特供)」而**误判为仍在播种**（首次验证即因此误判，实际是上次安装遗留的历史数据）。
  - **产物** 两条（同版本号、同签名、仅包名不同）：
    - 正式版 `release/legado_sk_3.26.092108c_10059_arm64-v8a.apk`（36,164,230 字节，sha256 `1f4815b7ce2a32ad7f7c8ce01a94ba4d42f7209ab8ccf07e048a5fea855c5813`），aapt：`io.legado.app.c` / 10059 / `3.26.092108c` / 阅读SK / arm64-v8a / locales `'zh'`。
    - 共存版 `release/legado_sk_3.26.092108c_10059_arm64-v8a_sk2.apk`（44,520,545 字节，sha256 `6ca9b30726c8665011662172a9da118d53cbe46dcf0f9ac0e1ced025f7a33edf`），aapt：`io.legado.app.sk2` / 10059 / `3.26.092108c` / 阅读SK / arm64-v8a / locales `'zh'`。
    - 两者 apksigner 均 exit 0，证书 SHA-256 同为 `79fef578…`。`release/legado-sk-arm64-v8a.apk`（固定名「当前交付 APK」）已更新为 10059 正式版。
  - **未发布 Release**（作者未指示；按 §5 若发布则默认 Pre）。**下一次交付 versionCode 从 `10060` 递增。**
  - 🧪 **去守卫版书源（供共存版做基础测试用）**：`test-records/fanqie_noguard.json`（114,162 字节，gitignore 内）。
    - **来源与生成**：从 `test-records/bookSources.json.10058.bak`（10058 原版内置书源备份）机械剥离授权守卫——`ruleContent.content` 去掉 `if (!fqAuthOk.call(this)) { FQ_AUTH_DENIED; } else {` 外壳（保留首位 `@js:`，正文逐字未动），`jsLib` 去掉 `// ===== 作者授权校验` 起的整段（`FQ_SK_PKG`/`FQ_SK_NAME`/`FQ_SK_GITHUB`/`fqAuthOk`/`FQ_AUTH_DENIED`，424 字符）。备注与分组同步改写。
    - **校验**：JSON 合法；6 条 `@js` 规则（`jsLib`/`ruleContent.content`/`ruleSearch.bookList`/`ruleExplore.bookList`/`ruleToc.chapterList`/`searchUrl`）全部通过 node `new Function()` 语法校验；`fqAuthOk`/`FQ_AUTH_DENIED`/`matchApp`/`io.legado.app.c` 残留计数均为 **0**。
    - **导入结论（雷电模拟器 emulator-5554，2026-09-21）**：导入 `io.legado.app.sk2` 成功（DB `book_sources` 计数 1）；搜索「wenzhang」返回真实书单（含封面/作者/标签）；进书籍详情正常；**进阅读页正文正常渲染**（第 1 章真实正文，**不是**未授权引导文案）→ **守卫已确实失效，正文逻辑完整可用**；`logcat -b crash` 0 条、无 FATAL。
    - ⚠️ **导入前置条件（踩坑）**：书源「本地导入」走的是 `MANAGE_EXTERNAL_STORAGE`（所有文件访问权限）判定（`Permissions.kt` / `Request.kt:109` 判 `Environment.isExternalStorageManager()`），**`pm grant` 授不了该权限**，必须在设置页手动开「授予管理所有文件的权限」；只授 `READ/WRITE_EXTERNAL_STORAGE` 仍会弹「阅读需要访问存储卡权限」。
    - ⚠️ **该文件不随包分发、不提交**（在 gitignore 的 `test-records/`）。正式版与共存版的**新版仍不含任何内置书源**——这份只是手工导入到模拟器做测试用的。

- ✅ **10058（`3.26.091959c`）已发布 Pre-release `v3.26.091959-10058`（2026-09-19）——历史交付（全项目审查修复合集 + 阅读页设置白字真修）**：
  - **性质**：审查修复版（无新功能、无 DB 迁移）。分支 `fix/review-r2`（自 10055 源码基线 `def356d3` 拉出、修复完成并验证后由作者指示合并发布）合入 main。改动链：`bac324fc`（P0）→ `cc220d4f`（P2-1 初版，已被取代）→ `dde3d687`/`a12895f4`/`c48d183f`/`f4444a2d`（P2-2~5）→ `cac80a7a`（P3）→ `6a119fc5`（白字返工）→ `ced0baad`（白字真因）。10056/10057 为分支中间构建，**无 Release**。逐项细节以 `companion/发布版更新记录.md` 10058 条目为准。
  - **P0（数据安全）**：`BookUpsert.savePlain` 与 `BookInfoViewModel.loadChapter` 对已存在 `bookUrl` 的行做 REPLACE/裸 insert，在 `foreign_keys=ON` 下隐式 DELETE 触发 `chapters` 等关联表 **CASCADE 清空**（离线已缓存书不可读）。改 `has() ? update : insert` 分流。→ 红线已入 §0（发布版更新记录）与本文件 §4 功能红线同源理解；回归锁 `BookUpsertWritePathTest`。
  - **P2/P3**：`ReadBookActivity` 声明 `uiMode` configChanges + `onConfigurationChanged` 补发 `UP_CONFIG [1,2,5]`（弹窗切日夜就地重绘）；合并重复书籍多 donor 一轮清干净+计数对齐；主页加架走 `upsertByIdentity`；换源 `SOURCE_CHANGED` 载荷=最终落库 bookUrl + migrateFrom 搬运收口；页脚朗读 `check` 崩溃改「日志+重展面板」；音源朗读语速 `coerceIn(0.5,3.0)`；朗读启动令牌复查；`shareConfig` 兜底 `getConfig(0)`；AI 头标记时机（**headers.isBlank() 分支刻意不置位**，防锁死 10039 播种）；预设 JSON 去 `transparentNavBar:false`；`applyConfig` 过滤 `@asset:` 残留；`VolumeGain.labelFor` 移 UI 层；删 `join_qq_channel`/`gzGzh` 死代码。
  - **白字真修（用户报告；10056/10057 两版未解）**：阅读页「更多设置」亮色白底白字。**真因**：`isBottomBackground` 行文字亮度按原始 `ThemeStore.bottomBackground` 判定，当前主题存 `#B6B6B6`（亮度 **0.468**<0.5）→ 误判深色背景发白字，而行卡片实际绘制 `UiCorner.surfaceColor(themeSurfaceCardColor)` 为浅色。修复：`Preference.bindView`/`NameListPreference` 统一按 `PreferenceItemStyle.itemSurfaceColor`（与绘制同源）。⚠️ **取证经过**：真机 dumpsys + `app_themes.xml` 实值 + AppCompat `updateAppConfiguration` 字节码排除 uiMode 方向（首版 `6a119fc5` 为误诊，保留但其目标改为"弹窗重建"）。回归锁 `PreferenceRowTextColorSourceTest`（双向证伪）。⚠️ **同理改法（登记）**：DetailSeekBar/SelectActionBar/ToastUtils 等按 `bottomBackground` 判文字是**自洽的**（画的就是它自己），不要顺手改。
  - **验证**：全量单测 **186 项 / 10 失败**（既有已知失败）；模拟器 10057→10058 覆盖安装，同一主题同一亮色模式实测「更多设置」文字全部清晰、暗色不受影响（截图 `test-records/theme-bug/verify-10058-*`）；真机复验由作者完成。
  - **产物** `release/legado_sk_3.26.091959c_10058_arm64-v8a.apk`（36,196,703 字节，sha256 `b1d72611386e0f74a9ef68f6a641c9dccd3a46209163675c4161e6b7a3801cc2`），aapt（io.legado.app.c / 10058 / 3.26.091959c / 阅读SK / arm64-v8a / locales `'zh'`）+ apksigner（exit 0，证书 SHA-256 `79fef578…`）通过。发布说明 `companion/发布说明-10058.md`。**已发布 Pre-release `v3.26.091959-10058`（作者指示发布；按 §5 默认 Pre）**，tag 指向发布时远端 main HEAD（docs 提交）。
  - ⚠️ **同日作者要求删除 10055 的 Release 与 tag**（见下条）。
  - 🆕 **共存版 `sk2` 已恢复（2026-09-21 作者指示）**：`app/build.gradle` 新增 `sk2` buildType，并用 10058 的同一 `VERSION_CODE`/`VERSION_NAME` 补编了共存版 APK `release/legado_sk_3.26.091959c_10058_arm64-v8a_sk2.apk`（44,559,607 字节，sha256 `50898310de3c8700520ec2545acb2238b6f23ce13aa17b069d31336d96023d06`；aapt：`io.legado.app.sk2` / 10058 / `3.26.091959c` / 阅读SK / arm64-v8a / locales `'zh'`；apksigner exit 0，证书 SHA-256 `79fef578…`，与正式版同签名）。**这不是新版本、无 Release**，只是把「同一个 10058」补出共存变体；**本日起每次正式编译都要同时产出共存版**（见 §3「共存版 sk2」）。
    - ⚠️ **实测踩坑（已修，勿改回）**：`sk2` 用 `initWith debug` 会**继承 debug 的 `versionNameSuffix 'debug'`**，首版编译得到的 `versionName` 是 `3.26.091959cdebug`（与正式版不一致、且污染更新检查的版本比较）。必须在 `sk2` 块内显式 `versionNameSuffix ''`。同类陷阱对任何 `initWith debug` 的新 buildType 都成立。
    - **共存验证（雷电模拟器 emulator-5554，2026-09-21）**：`io.legado.app.c`（10058）与 `io.legado.app.sk2` **并存安装成功**（同机另有上游 `io.legado.app.yuedu.a.release`／阅读C，三者同时在场）；`dataDir` 分别为 `/data/user/0/io.legado.app.c` 与 `/data/user/0/io.legado.app.sk2`（数据隔离成立）；共存版冷启动进入 `MainActivity`、`logcat -b crash` **0 条**；随后正式版仍可正常启动。
  - **下一次交付 versionCode 从 `10059` 递增。**

- ✅ **10055（`3.26.091956c`）曾发布 Pre-release `v3.26.091956-10055`（2026-09-19；**Release+tag 已按作者要求整套删除**，代码保留 main、修复内容并入 10058）——修日夜间切换黑白混杂**：
  - **性质**：修回归缺陷（**上游继承**，作者要求修复）。修复提交 `ed640fde`，仅改 `ThemeConfig.kt` 的 `syncSystemNightMode`（+15/−10）。
  - **症状**：「我的」页（及所有依赖 `values-night` 资源的界面）切换日/夜后黑白混杂且**永不恢复**——卡片背景与部分文字变对了、另一部分文字/搜索条停在旧配色，滚动会让更多卡片背景变对但文字依旧错乱。模拟器 10049（Android 14）实机复现并留存截图（`test-records/theme-bug/`）。
  - **根因（上游 `0f42491b`「统一系统启动画面并修复夜间首帧」引入，随 10036 新基底进入）**：API 31+ 把夜间模式改为 `UiModeManager.setApplicationNightMode`（**系统异步应用**），但 `applyDayNight` 的 `postEvent(RECREATE)` → `recreate()` 仍**同步立即执行**——重建抢在系统覆盖落地之前完成，新 Activity 按旧 uiMode 配置解析全部 `values-night` 资源（卡片 `background_card`、文字 `primaryText`/`tv_text_summary`、搜索条 `bg_searchview` 拿到日间值，而 ThemeStore 驱动的根背景已是夜间）。覆盖随后落地时，MainActivity 在 manifest 声明了 `uiMode` configChanges → 只走 `onConfigurationChanged`（`BaseActivity` 仅刷系统栏），**没有任何重载视图的机会** → 混色永久停留。上游 legadoC 同版本同样存在此缺陷。
  - **修法**：`syncSystemNightMode` **恢复同步写入 AppCompat 本地夜间覆盖**（`AppCompatDelegate.setDefaultNightMode(YES/NO)`），再叠加 `setApplicationNightMode`（保留其启动画面收益）。AppCompat 1.7.1 语义（字节码核实 `updateAppConfiguration`）：对声明 `uiMode` configChanges 的 Activity **就地更新 Resources 配置不重建**（`Resources.updateConfiguration`+flush），未声明的（阅读页等）**立即 recreate**；无论哪种，之后 RECREATE 驱动的 `recreate()` 重建的 Activity 在 attach 时即按目标模式解析资源 → 重建结果必然正确。ⓘ 重建后再触发 `setDefaultNightMode` 对配置已一致的界面是 no-op，无双重建。
  - ⚠️ **AUTO 语义**：`AppConfig.isNightTheme` 在 AUTO 下取 `Resources.getSystem().configuration`（**系统全局配置，不含应用级覆盖**）＝`setApplicationNightMode(AUTO)` 清除覆盖后的系统真实模式，故 `setDefaultNightMode` 的快照不会用错旧覆盖的残留值。`versionNameSortKey` 对 MMddHH 是**纯数值比较**（`UpdateManager.kt:162`），10054 的 `091955`（HH=55 非法小时）封住了当天合法小时值，本版取 `091956` 保持数值单调；versionCode 仍是主判定键。
  - **验证**：模拟器覆盖安装（10049→10055）后作者简单测试通过；平板 TB-9707F 覆盖安装（10054→10055）成功（`versionCode=10055` 校验一致），真机复验由作者完成。**已发布 Pre-release `v3.26.091956-10055`（作者要求发布；按 §5 默认 Pre）**：tag 指向 `dae461c4`（= 发布时远端 main HEAD），发布说明 `companion/发布说明-10055.md`，远端资产 sha256 `1427dec6…` 与本地 APK 逐字节一致，`isPrerelease=true` / `isDraft=false`。
  - **产物** `release/legado_sk_3.26.091956c_10055_arm64-v8a.apk`（36,196,582 字节，sha256 `1427dec6b12271d12f681f2e361bb7387c65212bd8c4eacb8695d149c3049121`），aapt（io.legado.app.c / 10055 / 3.26.091956c / 阅读SK / arm64-v8a / locales `'zh'`）+ apksigner（exit 0，证书 SHA-256 `79fef578…`）通过。⚠️ 本版 Gradle 输出名**无 `_arm64-v8a` 后缀**（同 10045），收进 `release/` 时按约定补后缀。
  - ~~**下一次交付 versionCode 从 `10056` 递增。**~~（已被 10056~10058 覆盖，当前从 10059 递增。）

- ✅ **10054（`3.26.091955c`）已发布 Pre-release `v3.26.091955-10054`（2026-09-18）——书架同书去重 + 内置三套预设主题/排版**：
  - **性质**：功能版（两项独立功能）。改动链：`9655ee5e`（统一入库入口，按书名+作者+媒体类型收敛）→ `0aa0e21e`（换源与入库路径收口到统一入口）→ `5008b91a`（书架手动「合并重复书籍」入口）→ `c219e259`（内置墨墟/琴女主题与娑娜排版三套预设）→ `88666e18`（修正娑娜排版页眉内边距与提示位）。
  - ⚠️ **10052 / 10053 无独立交付**：10052 是去重开发的中间版（无 Release 记录），10053 是预设的首个构建（被 10054 取代）。查去重改动从 `9655ee5e` 起看。
  - **功能一：书架同书去重**。背景：同一本书（书名、作者一致）换个书源加入会被当成两本不同书并存。
    - **同一性判据 = `name` + `author` + `mediaType`**（书名 + 作者 + 媒体类型）。手动「合并重复书籍」的作用域与此完全一致。
    - ⚠️ **`bookUrl` 仍是主键，且同时充当缓存目录地址**（`getFolderNameNoCache()` = `name.take(9) + md5(bookUrl)`）——**合并是复用旧记录**（保留 keeper 的 `bookUrl`），只更新书源与章节，**不新建书**，因此进度/书签/阅读记录都留在原记录上。
    - ⚠️ **keeper 选择用 `readRecentBooks.lastRead`，不能用 `durChapterTime`**——后者被 `BookInfoViewModel.topBook()` 与一个 `System.currentTimeMillis()` 字段默认值污染。keeper = **最近打开阅读的那一条**。
    - **本地书籍不参与**合并（按文件走，不与网络书互认）；**作者为空的书照常合并**。
    - ⚠️ **`group` 是位掩码，合并时必须取并集**（`keep.group or src.group`），不能直接覆盖。
    - 新文件：`help/book/BookMergeRules.kt`、`help/book/BookUpsert.kt`、`help/book/ChapterLocator.kt`。⚠️ **`ChapterLocator.kt` 是为可测性做的纯 JVM 抽取**——`BookMergeRules` 若直接调 `BookHelp.getDurChapter` 会在 JVM 单测里 `NoClassDefFoundError: Could not initialize class BookHelp`（其初始化需要 `appCtx`）。`ChapterLocator.regexC` 是**逐字复制的原实现**，不是简化版，勿"顺手优化"。
    - ⚠️ **DAO 只加了 `@Query`，`AppDatabase.version = 117` 未变，无需迁移**（刻意为之）。
  - **功能二：内置三套预设**。`MD3·墨墟`（日/夜）、`MD3·琴女`（日/夜）为**主题**预设；`娑娜`为**阅读排版**预设。
    - ⚠️⚠️ **阅读排版预设只能追加在数组末尾**：`ReadConfig` 内置样式按**数组下标**寻址（`ReadBookConfig.getConfig(index)` → `DefaultData.readConfigs[normalizedIndex]`），**插入到中间会让所有存量用户当前排版发生位移**。回归锁 `BuiltinPresetAssetTest.readPresetIsAppendedAtEnd`（验证过：把 `娑娜` 移到 index 0 即失败）。
    - ⚠️ **主题预设背景图不能直接写 assets 路径**：`backgroundImgPath` 必须是**可读的绝对路径**（`isReadableThemeFile` 要求 `File.isFile`）。为此在 `ThemeConfig.kt` 引入 `@asset:` 前缀约定 + `Config.resolvePresetBackgrounds()`，在 `configList` 里解析前缀并把 asset 复制到 `filesDir/defaultData/` 后回写绝对路径。未加前缀的值原样透传。
    - **预设匹配键 = `themeName` + `isNightTheme`**（`addConfig`/`addConfigs` 一致）；MD3 包会按同一 `themeName` 拆成日/夜两条。
    - **素材取自真机导入产物**（非手算），保证预设与手动导入结果一致。例：墨墟日间 `accentColor #E6FFFF`、夜间 `#000000`（源里带 alpha 0，`md3ColorToHex` 会丢 alpha）；`backgroundImgBlur` 被 `coerceIn(0,25)` 从 97 钳到 25。
    - 命名：MD3 manifest 无 `name`，会退化成通用名 `MD3主题`，故重命名为 `MD3·墨墟`。
    - ⚠️ **预设可见性（10060 已更正）**：`ThemeConfig.configList = getConfigs() ?: DefaultData.themeConfigs` —— 有 `filesDir/themeConfig.json` 时预设**不进 `configList`**。⚠️ 但该遮蔽**只影响读 `configList` 的资产合并**（`getDayTheme`/`getNightTheme`），**主题管理页从不读 `configList`**（它列 `themePackages/` 目录），故**与「预设能否被选到」无关**。10060 之前预设**没有任何入口**，10060 起由 `seedBuiltinPresetsOnce()` 播种落包使其可见。
    - **不覆盖**：导航图标与封面相册按设计忽略；预设**不改变当前已应用的主题/排版**，只追加到列表末尾。
  - **验证**：`BuiltinPresetAssetTest` 6/6 通过（`--rerun-tasks` 实跑）。⚠️ **资产类测试必须 `--rerun-tasks`**——曾出现 Gradle 报 BUILD SUCCESSFUL 却未真正执行（注入的错误仍在）的**假绿**。回归锁已双向证伪：改坏主题预设图片名 → `themePresetAssetRefsExist` 失败（第 47 行）；移动 `娑娜` 到 index 0 → `readPresetIsAppendedAtEnd` 失败（第 69 行）。全量单测 **181 项 / 10 失败**（10 项＝既有已知失败：`CacheTaskStoreTest` ×9 + `ReadBookConfigTest.sanitize_clampsUnsafeLineSpacing`）。
  - **实机回归（作者手动完成）**：换源不重复建书、书架「合并重复书籍」正常；三套预设显示效果确认；**娑娜排版修正后的效果已实测通过**。10054 已覆盖安装到手机与平板（TB-9707F），版本号校验一致。
  - ⚠️ **10054 APK 的资产与当前 HEAD 已逐字节核对（零差异）**：`readConfig.json`（11176 字节）与 `themeConfig.json`（2722 字节）在 APK 内与源码完全一致，**娑娜修正值 `headerPaddingBottom=10 / headerPaddingTop=10 / tipHeaderLeft=1 / tipHeaderMiddle=0` 已在包内**。**不要再重新编译 10055**——10054 就是最终版。
  - **产物** `release/legado_sk_3.26.091955c_10054_arm64-v8a.apk`（36,196,537 字节，sha256 `50c8b571189add35556ef4d61bfe1d8b1bedf044b1515bda1a5312454448b827`），aapt（io.legado.app.c / 10054 / 3.26.091955c / 阅读SK / arm64-v8a / locales `'zh'`）+ apksigner(exit 0) 通过。发布说明 `companion/发布说明-10054.md`。**已发布 Pre-release `v3.26.091955-10054`（作者要求发布；按 §5 默认 Pre）**：tag 指向 `7e891a91`（= 发布时远端 main HEAD，docs 提交），远端资产 sha256 `50c8b571…` 与本地 APK **逐字节一致**，`isPrerelease=true` / `isDraft=false`。**下一次交付 versionCode 从 `10055` 递增**。

- ✅ **10051（`3.26.091900c`）已发布 Pre-release `v3.26.091900-10051`（2026-09-18）——朗读音量增强 + 音质说明；修 10050 引入的「打开朗读面板必崩」**：
  - **性质**：功能版（音量增强）+ 自引入缺陷的修复版。改动链：`5ac5a818`（音量增强）→ `19e7bc25`（评论缓存默认关闭）→ `becd5955`（增益两处加固）→ `76c37b01`（音质说明）→ `cc7a7261`（修崩溃）。发布时已 rebase 到远端 main（含远端 README 提交 `df907548`），提交哈希见 `44cab79c` 等（rebase 后重写），**源码与已交付 APK 同源已核对（零差异）**。
  - **功能：朗读音量增强（播放端数字增益）**。背景：部分在线 TTS 音源默认合成音量偏小，手机音量调到头仍不够。
    - ⚠️ **实现选型的根本约束**：`ExoPlayer.setVolume()` 内部被 media3 的 `Util.constrainValue(volume, 0f, 1f)` 钳在 [0,1]（字节码确认），**无法放大**；且 `ExoPlayer.Builder` **没有 `setAudioSink`**。只能在**解码后的 PCM** 上做乘法——自定义 `AudioProcessor`，经 `DefaultRenderersFactory.buildAudioSink` 覆写注入。新增 `help/exoplayer/VolumeGainAudioProcessor.kt` + `VolumeGainRenderersFactory.kt`；接线 3 处（`ExoPlayerHelper.createHttpExoPlayer`、`HttpReadAloudService`、`TTSReadAloudService`）。
    - **取值语义**：设置 key `ttsVolumeGain`，**增强百分比**，`0` = 不增强（1.0x），上限 `400` = 5.0x。**最小位刻意不衰减**（因子恒 ≥ 1）——作者定下的产品语义，**不要**改成"0 表示静音或允许负值衰减"。默认 `AppConfig.defaultVolumeGain = 0`。
    - ⚠️⚠️ **缓冲区读写纪律（10048 的「无声」事故根因，绝不可改回）**：`queueInput` 必须**直接用外层 `ByteBuffer` 的 `getShort/putShort`（或 `getFloat/putFloat`）**逐样本读写，末尾对本方法内 `replaceOutputBuffer(...)` 的返回值调**一次** `flip()`。若改用 `asShortBuffer()`/`asFloatBuffer()` **视图**写入：视图 position 前进而**外层 position 仍为 0**，末尾 `flip()` 把 `limit` 置 0 → **输出 0 字节 → 完全无声**。回归锁 `VolumeGainAudioProcessorTest.emitsAllInputBytesAmplified`（把写法退回视图版会让 2 个用例失败）。
    - ⚠️ **不支持编码不得抛异常**：`DefaultAudioSink.configure()` 会把 `UnhandledAudioFormatException` **包装成 `AudioSink.ConfigurationException` 抛出**（不是优雅旁路），整段音频配置失败。故 `onConfigure` 遇非 16bit/float PCM 时返回 `AudioFormat.NOT_SET` 让本处理器**整体跳过**。
    - ⚠️ **增益读取移出音频线程**：`@Volatile var VolumeGain.currentFactor`，由 `AppConfig.ttsVolumeGain` 的 getter/setter 统一 `refresh()`。
    - **生效时机**：`isActive()` 只在 `configure()`/`flush()` 重建，播放中改设置要等下一段音频入队；朗读逐句合成天然满足。
    - **不覆盖**：系统 TTS 直出（`speak()`，App 拿不到 PCM）；`Exo2MediaPlayer`（视频）与 `AudioBlockPlayer` 刻意未接线。
  - 🔴 **10050 引入的必崩缺陷（10051 修复）——`TextView.setText(Int)` 语义坑，务必记住**：
    - 症状：**打开朗读面板即闪退**。栈：`Resources$NotFoundException: String resource ID #0x0` → `TextView.setText(TextView.java:6748)` → `ReadAloudDialog.upVolumeGainText`。
    - 根因：10050 让"无提示"档返回资源 id `0` 并交给 `setText(...)`。但 **`TextView.setText(Int)` 的参数是「字符串资源 id」而非文本**，`0` 无效 → 抛异常。
    - ⚠️ **触发条件是默认态**（增益 `0` → `qualityCostFor` 返回 `NONE`），**任何用户首次打开朗读面板必崩**。
    - ⚠️ **通用坑**：给 `TextView.setText` 传 `Int` **永远是资源 id**。要"清空"用 `text = ""`（CharSequence）或 `setText(null)`。**"0 表示无"是 `setImageResource`/`setBackgroundResource` 的语义，跨控件迁移会直接崩溃。**
    - 回归锁：`ReadAloudVolumeGainHintTest`（**源码级静态断言**，因 `TextView` 是 Android 类、JVM 单测覆盖不到）；**已验证把缺陷写回即令其失败**。
  - **UI 说明（10050）**：标题「音量增强（会影响音质）」+ `iv_volume_gain_help`（复用 `ic_help`，**48dp 触控目标**，`padding=15dp` 保持 18dp 视觉尺寸）点击弹完整说明 + 滑条右侧 `tv_volume_gain_hint` 按档位提示。⚠️ **刻意的分层**：`VolumeGain.qualityCostFor(percent)` 只判定等级（`NONE`/`MILD`/`DISTORTION`，分界 `DISTORTION_FACTOR = 2f`），**文案映射留在 UI 层**——`help/exoplayer` 包**不依赖 `R`**，勿把资源 id 引进去。
  - **附带修复（10049）**：6 项「缓存评论」相关设置默认值 `true` → `false`（`syncCacheReview`、`cacheReviewReplies`、`cacheReviewAvatars`、`cacheReviewImages`、`compressReviewAvatars`、`compressReviewImages`），并同步 `pref_config_read.xml`。⚠️ 两点易误解：① `syncCacheReview` 是**总开关**，`reviewEnabled` 只是挂在**同一次缓存请求**上的附加项，**关掉不阻断章节正文下载**；② **默认值改动只对新装机生效**，存量 pref 已写入 `true`，需手动关。
  - **验证**：音量增益相关单测全绿（含崩溃回归锁）。**真机实测（平板 TB-9707F，10050 → 10051 覆盖升级）走了完整崩溃路径**——书架进阅读页 → 点屏呼出菜单 → **长按「朗读」**进面板：面板正常打开、显示「音量增强（会影响音质）」+「不增强」；拖到 1.9X → 「音量提高，音质会受影响」；4.1X → 「过高可能失真」；点 **?** 图标正常弹出说明；**全程 `FATAL` 计数 0**。
  - ⚠️ **操作侧坑（实测）**：`ThemeSeekBar` **不响应 `adb input swipe` 的绝对坐标拖动**（拖不动），验证滑条请用「−/+」按钮或真实触摸。
  - **产物** `release/legado_sk_3.26.091900c_10051_arm64-v8a.apk`（31,016,331 字节，sha256 `66c8318d175a954ce9e765834f0630c537204644c738894afed472063f40f91b`），aapt（io.legado.app.c / 10051 / 3.26.091900c / 阅读SK / arm64-v8a / locales `'zh'`）+ apksigner(exit 0) 通过。发布说明 `companion/发布说明-10051.md`。**已发布 Pre-release `v3.26.091900-10051`（作者要求发布；按 §5 默认 Pre）**：tag 指向 `44cab79c`（= 发布时远端 main HEAD），资产 sha256 与本地一致，`isPrerelease=true` / `isDraft=false`。下一次交付 versionCode 从 `10052` 递增。
  - ⚠️ **10046/10047 无对应 git 提交**（音量增强开发期中间打包），查改动请从 `5ac5a818` 起看。

- ✅ **10045（`3.26.091512c`）已构建并通过模拟器回归（2026-09-15）——修 10044 引入的「自定义封面漏备份」+ 全项目审查产出**：
  - **性质**：全项目审查后的修复版（4 个只读子代理并行 + 主代理逐条复核 + 上游对照；报告在 gitignore 的 `test-records/review-r1-*/`）。**审查确认当前 main 自洽可编译、7 条功能红线 0 回归**。
  - **必修回归**：**10044 把 `covers` 的入包判定与其创建顺序搞反**——10044 把「无条件加入 + `ZipUtils` 静默跳过」改成 `if (dir.exists())` 过滤，但 `covers` 是 `prepareCustomCoverBackup()` 在**该判定之后**才创建的 → 目录不存在时被跳过、封面随即被拷进去 → **备份包缺 covers，换机恢复后封面全丢且本机无异常**。10043 及更早不受影响。
  - **修法**：`"covers"` 移出 `Backup.backgroundAssetDirNames`（**`Restore.kt` 的同名副本不动**，其依赖该目录名做路径重映射），改由 `prepareCustomCoverBackup()` 先建目录 + 补齐外部封面，再按**目录实际内容**判定（顶层 `coverDirShouldBeZipped(File)` 判 `listFiles()` 非空）。
  - ⚠️⚠️ **判据必须锚定"最终要打包的对象的状态"，不能锚定"本次流程做了什么动作"**：封面经「选择本地图片」设置后本身就落在 `covers` 内，`prepareCustomCoverBackup()` 对这类路径会**跳过拷贝**（无需复制到自身）→ 用"本次拷了几个"判定会把**最常见场景**误判为空。**首版实现即犯此错，被实机回归抓到**；这与 §4 红线段落同源。
  - **顺带修复（SK 独有，10039 引入）**：`fillOpenCodeSessionHeadersIfNeeded` 原用 `copy(headers = headers)` **整条替换**（判据仅「不含 X-Session-Id」），会冲掉用户自改的请求头；改为 `AiBuiltinDefaults.mergeMissingHeaders()` **按行合并、键名大小写不敏感、只补缺失**，并把 `putPrefBoolean(aiOpenCodeSessionHeadersFilled)` 移到 `persistAiProviders` **之后**（原顺序下 persist 抛异常会留下不可自愈的「标记已置位但头没写入」装机）。**判据不变**（只在 `aiLlmBuiltinHeadersFilled` 已置位的早退分支执行，天然只服务存量装机）。
  - **清理**：删 `ReviewSnapshotCapture.kt` 中被注释掉的代码残骸（10037 重放遗留）。
  - **作者裁决（记此以免重复上报）**：① **不跟随 `upstream/own` 的评论分页抓取**（`733d1632`，试用体验不佳）——该分支相关议题全部作废；② `exportWebDav` 三处未接异常、恢复回滚不含 DB **只登记不修**（上游继承缺陷，见 §4 登记段）。
  - **验证**：单测 4 项 covers 用例；**实测把判据退回 `exists()` 即令「空目录不得入包」失败、把 `"covers"` 加回清单即令清单断言失败**，确认能捕获两类回归。全量单测 **131 项 / 10 失败**（10 项＝既有已知失败）；`assembleAppRelease` BUILD SUCCESSFUL。
  - **实机回归（雷电模拟器，10044 → 10045 覆盖升级，签名一致保数据）**：① 无自定义封面 → 备份 18 条目、**无 `covers/` 空目录条目**、空表如常跳过且未致备份中止；② 设封面后 → 19 条目、**包内出现 `covers/4b3c7a86c14262f47611df96421f3c2b.png`（20000 字节）**；全程 `ZIP 源文件不存在` / `IllegalArgumentException` / `备份出错` / `FATAL` **计数均为 0**。
  - 产物 `release/legado_sk_3.26.091512c_10045_arm64-v8a.apk`（31,011,993 字节，sha256 `7c083b4e8e8592d9c39cb61c1ddb1fed38c04ec1ca0e12af3eb19f905d97c95f`），aapt（io.legado.app.c / 10045 / 3.26.091512c / 阅读SK / arm64-v8a / locales `'zh'`）+ apksigner(exit 0) 通过。⚠️ 本版 Gradle 输出名为 `legado_sk_<version>.apk`（**无 `_arm64-v8a` 后缀**），收进 `release/` 时按约定补后缀。
  - ✅ **已发布 Pre-release `v3.26.091512-10045`（2026-09-15，作者确认可用于发布）**：tag 指向 `784b8e69`（= 发布时 HEAD），资产 `legado_sk_3.26.091512c_10045_arm64-v8a.apk`（31,011,993 字节，sha256 `7c083b4e…`，与本地交付 APK 一致），`isPrerelease=true` / `isDraft=false`。发布说明 `companion/发布说明-10045.md`。**真机实测确认无问题后发布**（联想平板 TB-9707F）。下一次交付 versionCode 从 `10046` 递增。

- ✅ **10044（`3.26.091320c`）已发布 Pre-release `v3.26.091320-10044`（2026-09-13）——当前交付（修复 10043 引入的「备份必失败」）**：
  - **性质**：修回归缺陷，非功能开发。10043 同步上游时，把上游 `ZipUtils.zipFile` 的「源文件不存在静默跳过」改为 `require(srcFile.exists())`（**该变更本身正确，勿回退**），但 `Backup.kt` 仍按 `backupFileNames` 全量拼路径 —— 而 `writeListToJson` 对**空列表刻意不落盘**，于是任何一张空表都让整次备份以 `IllegalArgumentException: ZIP 源文件不存在` 中止。**新装机所有表皆空，必然复现**。
  - **修复**：`Backup.kt` 打包前按实际落盘结果过滤（新增顶层 `existingZipSources()`，独立于 `Backup` object 以便 JVM 单测覆盖）。同类隐患一并处理：`backgroundAssetDirNames` 目录、`themePackageFontDedupe.json` 清单（仅存在重复字体时写出）、`NavigationBarIconConfig.rootDir`（未预建目录）。`ZipUtils.kt` **未改动**。
  - ⚠️ **判据（写进 §「功能红线」同级原则）**：给 `ZipUtils` 的路径分两类——「本次流程自己创建/校验的」可直接传，「依赖用户配置才存在的」必须先 `exists()` 过滤。回调式清单（备份项目清单、可选 manifest）一律属后者。
  - **验证**：`BackupZipSourcesTest` 3 项通过（**已实测移除过滤即 3/3 失败**，确认能捕获）；全量单测 **123 项 / 113 通过 / 10 失败**（10 项＝既有已知失败）。
  - **实机回归（平板 TB-9707F，10043→10044 覆盖升级）**：触发条件仍在（`rssStar.json 列表为空`、`sourceSub.json 列表为空`），但 `ZIP 源文件不存在`/`IllegalArgumentException`/`备份出错` **计数均为 0**；`/sdcard/Download/yuedu/backup.zip`（8,463,794 字节）落盘，`testzip` 干净、28 个条目。
  - 产物 `release/legado_sk_3.26.091320c_10044_arm64-v8a.apk`（31,010,459 字节，sha256 `2df3addcbbefa8a32cad8a00a8bb454b41dda4138eab7f14abfe6ac5798f24d8`），aapt（io.legado.app.c / 10044 / 3.26.091320c / 阅读SK / arm64-v8a / locales `'zh'`）+ apksigner(exit 0) 通过。
- 10043（`3.26.091310c`，2026-09-13）**曾发布 Pre-release `v3.26.091310-10043`，已按作者要求整套删除（Release + tag，tag `v3.26.091310-10043` 已清理）；代码改动全部保留在 main 中**（同步上游 legadoC v3.26.091216；**该版备份功能被上游同版本缺陷打坏，已由 10044 修复**）：
  - **性质**：上游增量同步，非功能开发。上游基底不变（仍 `e3ee7b81` v3.26.090809），并入其 `e3ee7b81..v3.26.091216` 共 **40 提交 / 35 文件（+1864/−847）**。
  - ⚠️ **同步方法（可复用，务必照做）**：**先量冲突面再动手**——`comm -12 <(git diff --name-only <基底> HEAD|sort) <(git diff --name-only <基底> <上游tag>|sort)` 得**交集 9 个文件**＝真正需人工裁决者；**交集之外 26 个文件直接 `git checkout <tag> -- <file>`**（SK 完全未碰，零风险，且不会带回上游 `dependabot.yml`）。整树 merge/rebase 会波及大量 SK 定制，不要用。
  - ⚠️⚠️ **三方合并"无冲突"≠"无丢失"（本次最重要教训）**：上游**删除**的代码若与 SK 定制语义相关，会被 `git merge-file` 静默采纳而消失。本次即丢失 `MAX_EXPAND_ROUNDS = 40` 及其 2 处使用点（`expandRound()` 的 `stats==null` 重试分支与正常收口分支，两者**都不在冲突区域内**）。丢失后慢加载页面从「到顶提前收口出快照」退化为「重试到 60s 看门狗超时 → 快照失败」。**同步上游后必须对"上游删除项"单独核查一遍，不能只看冲突标记。**
  - ⚠️ **行尾差异会造成假性整文件冲突**：仓库工作区为 CRLF、上游对象为 LF，直接 `git merge-file` 会得到整文件 1 处冲突（假象）；先 `tr -d '\r'` 归一化再合并。
  - **四块上游增量**：① **章节状态与重试**（状态异常章节显示失败重试按钮；缺失状态的评论章节走普通 BODY→REVIEW 链路，`CacheCoordinator.statuslessChapters`）；② **离线评论快照**（弹窗直接展开、按实际绘制位置动态锚定评论栏 + 回写底部安全区、缓存管理复用正文清单 + 评论轻量索引 `ReviewSnapshotInventory.kt`、**评论资源总量门槛整体移除**而单资源超时 `RESOURCE_FETCH_TIMEOUT_MS` 保留）；③ **导出重构**（`ExportBookService.kt` 1133 行变更，TXT/ZIP 改**尽力导出 + 内置失败报告**，新增 `BookExportReport.kt`/`ExportFileWriter.kt`）；④ **AI 四项**（**内联思考块正则补第二捕获组，修 `No group 2` 崩溃**；关闭 Agent 保留普通 AI 对话；修普通 AI 对话系统提示词断裂；出厂 local-core 供应商**未采纳**）。
  - ⚠️ **`AppWebDav.exportWebDav(uri,…)` 抛出契约（10043 起，改调用方前必读）**：上游把「网络不可用静默 `return`」改为 **`check()`/`requireNotNull`/`IllegalStateException` 抛异常**，新增 `localAlreadySaved` 参数区分文案，`.zip` 按扩展名用 `application/zip`、URL 做 `Uri.encode`。调用点全在 `ExportBookService.kt`（已随上游一并更新：`uploadExportToWebDav()` 显式 catch 并返回失败文案；`.zip` 压缩包路径传 `localAlreadySaved=false`）。**新增调用点必须接住异常**，否则该路径会从"静默失败"变成"崩溃/整条导出失败"。注意 `exportWebDav(byteArray,…)` 重载**上游未改**（仍静默、`@Suppress("unused")`），勿以为两者行为一致。
  - ⚠️ **未采纳：上游出厂第二供应商 `local-core`**（`http://127.0.0.1:11434/v1`，本机 Ollama 端口）——作者决定剔除。因该决定，`AppConfig.kt` 的上游改动**全部不适用，SK 侧保持原样**（这是本次"冲突文件零改动"的原因，**不是漏改**）。上游是在 `ensureDefaultAiConfigIfNeeded()` 的**同一 if 分支**插入调用的，日后同步勿机械套用。
  - ⚠️ **`ReviewSnapshotCapture.kt` 的 SK 楼中楼强展必须保留**：上游把 `forceExpandRemaining()`（SK 两段式：`.reply-toggle` 结构定位 + `data-legado-force-expanded` 防往返标记 + 文本兜底）改回简单单轮点击循环 `forceExpandReplies()`，并删掉 `MAX_FORCE_EXPAND_ROUNDS`/`FORCE_EXPAND_STABLE_ROUNDS`。本次**以 SK 语义为主保留强展**，但**采纳了上游一条正确语义**：`parseForceExpandStats` 解析失败改 `fail(IllegalStateException(...))` 显式报错——SK 原注释称「看门狗已切走」，但该情形**实际不可达**（`destroyed` 已被上游 `if (destroyed) return@post` 拦下），真实可达的只有 JS 返回 null/解析异常，而把展开到一半的 DOM 冻结成"完整快照"存盘正是上游要消灭的静默错误（SK 此处照抄上游）。
  - **验证**：`assembleAppRelease` BUILD SUCCESSFUL；`testAppReleaseUnitTest` **120 项 / 110 通过 / 10 失败**（10 项＝既有已知失败：`CacheTaskStoreTest` 9 项 `LiveEventBusCore` JVM 静态初始化 + `ReadBookConfigTest` lineSpacing，与本次同步无关）；`ReviewSnapshotIntegrityTest` 4/4 通过（覆盖本次手工合并文件）。
  - **实机回归（雷电模拟器，10042→10043 覆盖升级）**：启动无崩溃；四个主 tab 切换正常；**长按搜索按钮直接进 AI 对话**（上游放开 AI 门禁生效）；`我的→书源管理` 显示 **「番茄小说 (SK特供)」在位 → SK 播种书源与全部数据升级后保留**；搜索「wo」番茄书源返回真实书单；点书→书籍详情→**阅读页正文正常渲染、零错误日志**（关键回归证据）。
  - 产物 `release/legado_sk_3.26.091310c_10043_arm64-v8a.apk`（31,010,083 字节，sha256 `81a9f6c14814f758408c6b2f5dd52945ba9499c62857c90fc7e8a26efe8e5163`），aapt（包名 io.legado.app.c / 10043 / 3.26.091310c / 阅读SK / arm64-v8a / locales `'zh'`）+ apksigner(exit 0) 通过。
  - 回归记录与截图：`test-records/upstream-091216/`（gitignore）。
- 10042（`3.26.091301c`，2026-09-13）授权校验收窄为只卡正文（已被 10043 取代，细节保留于下）：
  - **背景（10041 的体验回归，用户实测反馈）**：10041 把守卫同时插在搜索、发现、正文三处，导致：① **每次全书源搜书都弹一次授权 toast**；② 正文 `throw` 被阅读器当成下载失败——阅读页显示「获取正文失败」而非引导文案，并按重试次数**反复重试 + 反复弹提示**。
  - **改动（纯书源资产 + 测试，无 Kotlin 改动，内核与 10041 完全一致）**：
    - 移除 `ruleSearch.bookList` / `ruleExplore.bookList` 的守卫包装 → 搜索与发现对非授权客户端**完全放行**（能搜到、能入架）。
    - `ruleContent.content` 非授权分支由 `toast + throw` 改为**返回 `FQ_AUTH_DENIED` 文案**。
    - `bookSourceComment` → 「其他客户端能搜到书，但正文不展示」。
  - ⚠️ **核心判据（优先于一切技巧）**：**正文链路上 `throw` 不是"友好提示"，而是"下载失败"信号**。`CacheBook.downloadAwait` 的 `catch` 会把异常转成 `"获取正文失败\n${e.localizedMessage}"`，`onPostError` 再按 `READER_DOWNLOAD_MAX_ATTEMPTS`(=3) / `downloadChapterRetryCount` 反复重试。需要"软拒绝"时**返回文本**即可，不要抛异常。
  - **测试**：`BuiltinSourceGuardTest` 重写为 **6 项**：仅正文入口携带守卫（搜索/发现不得含 `fqAuthOk` / `FQ_AUTH_DENIED`）、非授权分支无 `throw`/`java.toast` 且返回文案、无规则调用 `java.toast`。连 `DefaultDataSeedTest` 共 9 项通过。
  - ⚠️ **断言必须限定在守卫分支内**：授权分支里的 `throw new Error('章节编号缺失:'+bu)` 是**正常**取正文失败，全量 `contains("throw")` 会误报（首版即踩）。用 `guardBranch()` 做括号配平只取 `if (!fqAuthOk...) {...}` 内部。
  - **实证（雷电模拟器）**：① `pm uninstall` 后全新安装 10042，日志 `内置书源播种：候选 1，已存在跳过 0，实际写入 1` ✅；② **SK 版**搜索「wo」拉到 5 本真实书籍，点开阅读**正文正常渲染**、零错误 ✅；③ **非 SK 版阅读A**（`io.legado.app.yuedu.a.release`）经 Web 服务 `saveBookSources` 导入后：搜索「nba」拉到多本真实书籍**无 toast 无报错**，点开阅读**正文区显示引导文案**（【本书源仅限「阅读SK」使用】+ GitHub 地址），**无错误弹窗、失败类日志 0 条**，10 秒后页面截图 sha256 不变（确认无重试覆盖）✅（注意 `172.16.1.15:1122` 是**模拟器自身 wlan0 地址**，宿主机连不上，需在模拟器内 `curl`）。
  - 产物 `release/legado_sk_3.26.091301c_10042_arm64-v8a.apk`（30,967,718 字节，sha256 `44af504cdd64ba5fcb766bc0e18d783522d0fbba7b75b28df050b696e93a3f55`），aapt（包名 io.legado.app.c / 10042 / 3.26.091301c / 阅读SK / arm64-v8a / locales 'zh'）+ apksigner(exit 0) 通过。
  - ⚠️ **已知遗留（与 10041 相同，作者已确认不再处理）**：书源播种按 `bookSourceUrl` 判重且为**一次性**，故**存量装机升级不会更新已存在的内置书源**——10040 播种的「无守卫」书源升级后仍是旧版。新装与手动删除后重装不受影响。
- 10041（`3.26.091201c`，2026-09-12）内置书源加入作者授权校验（**守卫范围已被 10042 收窄；该 GitHub Release 已按作者要求删除，tag `v3.26.091201-10041` 一并清理，改动已并入 10042**）：
  - **背景**：10040 内置的番茄书源任何人拿到都能用，作者要求加「验证版本号与阅读名称」的机制——非作者发布版不得使用。
  - **实现（书源层面，不影响阅读器其他功能）**：
    - `AppConst.appInfo` 新增 `packageName` / `appName`（后者取 `getApplicationLabel`，即 manifest 经 `${app_name}` 占位符解析后的名称）。
    - `JsExtensions` 新增 `getAppPackageName()` / `getAppName()` / `matchApp(包名, 应用名)`——**内核不固定任何版本事实**，期望值由书源自行声明传入。
    - 内置书源 `jsLib` 末尾定义 `fqAuthOk()`（`this.java.matchApp('io.legado.app.c', '阅读SK')`）与 `FQ_AUTH_DENIED` 提示文案（含作者仓库地址）。
    - 守卫最初落在 `ruleSearch.bookList` / `ruleExplore.bookList`（`toast` + `result = []`）与 `ruleContent.content`（`toast` + `throw`）；**该插入范围已在 10042 收窄为只卡正文**。`searchUrl` 只拼 URL、不产结果，故不插桩。
  - ⚠️ **两个写书源 JS 时必须避开的坑（本次都踩过并已修）**：
    1. **`@js:` 必须在规则字符串首位**——legado 只识别开头的 `@js:`，把它挤到第二行整段会退化为字面量，守卫静默失效。
    2. **JS 字符串里的换行必须写成转义序列 `\n`**——直接写入字面换行会截断字符串导致语法错误。用脚本改书源 JSON 时务必用原始字符串，并**用真实 JS 引擎（node）校验语法**，肉眼看不出来。
  - **测试**：`BuiltinSourceGuardTest` 5 项（10042 已重写为 6 项，见上）。
  - ⚠️ **测试写法注意**：`DefaultData.builtinBookSources` 依赖 `appCtx.assets`，**在 JVM 单测里会 ClassNotFoundException**；校验资产内容请直接读 JSON 文件（Gradle 单测 CWD 为模块目录 `app/`）。
  - 产物 `release/legado_sk_3.26.091201c_10041_arm64-v8a.apk`（30,967,603 字节，sha256 `c27863ee…`），aapt（包名 io.legado.app.c / 10041 / 3.26.091201c / 阅读SK / arm64-v8a / locales 'zh'）+ apksigner(exit 0) 通过。
- 10040（`3.26.091112c`，2026-09-11）出厂内置「番茄小说」书源，接通内置书源播种链路（**该 GitHub Release 已按作者要求删除，tag `v3.26.091112-10040` 一并清理；改动已并入 10042**）：
  - **背景**：作者要求把自己用的番茄书源作为 SK 版装机福利（默认就有、但可自行删除）。
  - **关键发现（原状态是坏的）**：`app/src/main/assets/defaultData/bookSources.json` 自基线提交 `544c1d1a` 引入起，**全库零个运行时读取点**——是彻头彻尾的死资源，历代版本从未真正种入任何书源。故本次不是"加个文件"，而是**先把播种链路接通**。
  - **实现**：`DefaultData.builtinBookSources` 读取该 asset；`seedBuiltinBookSourcesOnce()` 在 `upVersion()` 末尾执行。判重按 `bookSourceUrl`（书源表**主键**）跳过已存在项——因 `BookSourceDao.insert` 是 `OnConflictStrategy.REPLACE`，**不判重就会静默覆盖用户自建/改过的同名书源**。播种后调 `SourceHelp.adjustSortNumber()`（种子 `customOrder=0` 与存量必撞号，该方法只在重号/越界时才重排，幂等安全）。
  - **刻意不用版本号机制**：`migrateDefaultData` 的版本号每次提升都会重跑导入，用户删掉的书源会在下次升级**复活**。改用一次性布尔标记 `LocalConfig.builtinBookSourceSeeded`，置位后永不重播。这是"可删"语义的关键，后续若加内置书源**不要**改回版本号。
  - **种子内容**：番茄小说（`https://fanqienovel.com/`），分组 `SK特供`，备注「SK版阅读特供番茄书源，不保证一直能用。官方接口直连，可长按书源行删除。」；同时删除原有「消消乐听书」种子（需游客鉴权，开箱即用体验差）。`fanqienovel.com` 不在 `18PlusList.txt` 黑名单，不会被 `insertBookSource` 拦掉。
  - **实证（雷电模拟器）**：① 存量装机（10039 已手动导入同名同 URL 书源）升级后仍为 13 个、分组保持用户原值「番茄」→ **判重跳过、未覆盖 ✅**；② `pm clear` 全新装机后仅 1 个「番茄小说 (SK特供)」→ **出厂播种 ✅**；③ 行菜单删除后 `pm clear` 后续重启**未复活** → **一次性标记 ✅**；④ 发现页「男频·都市」拉回真实书单（《我不是戏神》等含封面作者）→ **书源真实可用 ✅**。
  - 产物 `release/legado_sk_3.26.091112c_10040_arm64-v8a.apk`（30,965,724 字节，sha256 `efb68787…`），aapt（包名 io.legado.app.c / 10040 / 3.26.091112c / 阅读SK / arm64-v8a / locales 'zh'）+ apksigner(exit 0) 通过；dex 内确认含 `seedBuiltinBookSourcesOnce`/`builtinBookSourcesToSeed`/`builtinBookSourceSeeded` 符号；`DefaultDataSeedTest` 3 项通过。
- 10039（`3.26.091101c`）内置 opencode-zen 会话请求头，修免费通道 400：
  - **症状**：内置 AI 供应商「问AI」开箱即用即失败。实测 Zen 免费通道对无会话头的请求直接返回 `400 MissingSessionID`，原文 `OpenCode's free tier can only be used in OpenCode`。
  - **根因（两层）**：① `app`/`oss` 两个 flavor 的 `AppPlugins` 都只 `init() = Unit`，谁也没注册 `AiBuiltinDefaults.Plugin` → `llmHeaders()` 恒为空串 → 出厂种入的供应商 `headers` 为空；② 存量安装还踩了**幂等标志**——早期版本已把 `aiLlmBuiltinHeadersFilled` 置位，`fillDefaultAiHeadersIfNeeded()` 直接早退，即便补上注册表也**永远补不到存量装机**（`830e094e`）。
  - **修复**：新增 `AiBuiltinDefaults.openCodeSessionId()`/`openCodeHeaders()`——会话 id 由 `AppConst.androidId` 经 SHA-256 派生（稳定、不可逆推、**逐设备不同**），避免多设备共用同一字面量 id 互相顶掉会话而放大限流与风控；`app` flavor 的 `AppPlugins` 注册出厂头（`user-agent: opencode/1.17.9` + `x-opencode-client` + `X-Session-Id`/`x-opencode-session`/`x-opencode-project`/`x-session-affinity`）。存量补齐走独立的 `fillOpenCodeSessionHeadersIfNeeded()`（新键 `aiOpenCodeSessionHeadersFilled`），只认「出厂供应商且仍无会话头」的目标，**用户自改过的请求头不动**。
  - **实证**：`Authorization: Bearer public` 由既有 `apiKey` 字段发送，无需重复填。实测最小必要集 = **会话头 + `user-agent`**（仅有 UA → 400；无任何头 → 400；仅会话头可通过会话门）；固定会话 id 连续 3 次请求均 200。**匿名额度按 IP 计**，高频连发会返回 `429 FreeUsageLimitError`（非头问题，换节点或稍后再试）。
  - 产物 `release/legado_sk_3.26.091101c_10039_arm64-v8a.apk`（30,935,132 字节），aapt(包名 io.legado.app.c/10039/3.26.091101c/阅读SK/arm64-v8a/locales 'zh') + apksigner(exit 0) 通过；模拟器 10038 覆盖升级成功、书架数据保留；`AiBuiltinDefaultsTest` 3 项通过，并逐一把修复标记比对确认已进 dex。
  - 附带实测：升级后正文长按菜单恢复为**完整多选项**（替换/书签/朗读/字典/问AI/搜索/插入），不再直接跳进问AI。
  - ⚠️ **重植遗留（10039 已修）**：`AiChapterPurifyHelperTest.kt` 曾引用不存在的 `AiChapterPurifyConfig.resolveRequestTemplate`（10036 重植上游删方法而测试留下），导致 `testAppReleaseUnitTest` **整体编译失败**（整包一起编，一个文件报错则全部单测跑不了）。该函数全库无调用点——净化已改用自己的 `requestTemplate`（唯一需要 `response_format=json_object` 的消费者，不再继承全局模板），故 10039 删除这 3 个验证废弃行为的用例，保留其余 3 个有效用例（`c14c566c`）。删除后单测恢复可编译：111 项运行。
  - ⚠️ **单测既有失败（10 项，非本次引入，未修）**：`CacheTaskStoreTest` 9 项因 `LiveEventBusCore` 在 **JVM 环境静态初始化失败**（Android 依赖，需 Robolectric/仪器化测试）；`ReadBookConfigTest.sanitize_clampsUnsafeLineSpacing` 断言 `expected:<10> but was:<0>`。两者均与本仓库 SK 改动无关。
- 10038（`3.26.091014c`，2026-09-10）重植审计修复（7 项缺陷 + 4 项清理）：**数据安全** `WebDav.existsChecked()` 补全三态语义（401/403/5xx 不再被当「明确不存在」而反向覆盖云端进度，`2a4ad571`）；**听书** HTTP TTS 倍速兜底位置修正（`1ab98ccd`）、朗读跟随翻页守卫改「类身份 + 真动画标志」（`44ea4b52`）、悬浮窗非法 bounds 早退残留陈旧避让区（`45c267df`）；**主题** `dialogAlpha/dialogBlur` 六处字面量漂移统一为 `DEFAULT_DIALOG_*`（`fac1a659`）、主题配置改 `themeName + isNightTheme` 双键匹配（`2335b4ba`）；**更新链** 关于页补回下载加速源设置与 `updateAcceleratorCustom` 可见性联动（`223e76cb`）；**清理** 删 6 个被 `resConfigs "zh"` 裁掉的 `values-zh-rHK|rTW` 死目录（`23b6cf4d`）、`MangaVH.isLastImage` 死形参（`6bcf3bfb`）、7 个零引用字符串（`ac130963`）、`MangaVH` 章末图片高度 `MATCH_PARENT`→`WRAP_CONTENT`（`502d81c6`）。产物 30,933,368 字节，aapt + apksigner 通过。
  - **方法论沉淀（重要）**：本次审计确立「**上游对照**」为强制否决步骤——大量"看似错配"实为**逐字继承自上游**（`autoReadSpeed` 10/46、`expandTextMenu` 死开关、`hideStatusBar` 三处默认值不一、`Restore.kt` 事务非原子等）。只有「上游有 A+B，SK 只改了 A 而 B 仍是上游值」才是重植缺陷。**未做上游对照即报缺陷会产生大量误报**。
  - **驳回的自身误判（记录以免重犯）**：① `ThemePackageManager` 的 `fontScale != 10` 排除**是正确的**——`10` 是字段声明默认值，GSON 经 Unsafe 不应用 Kotlin 默认值（`GsonExtensions.kt` 未注册 `KotlinValueInstantiator`），`1..16` 下界用于区分缺失填的 0；去掉会令「包内 fontScale=10」静默重置用户缩放；② 判断布局控件是否存在时**必须注意 XML id 是 snake_case 而 ViewBinding 才转驼峰**，用 camelCase 搜 `res/layout/` 会假阴性。
- 10037（`3.26.090900c`，2026-09-09）为补齐 10036 重植遗漏的 SK 定制版：**听书时点屏呼出普通主菜单**（`99669ee0`，长按「朗读」才进听书面板）；朗读路径断言改诊断提示（`3976f3be`）；服务侧悬浮窗 bounds/越界容错；换书竞态 F1/F2（`63132a6e`）、切书清朗读位置、目录加载失败保留旧目录（`f118ea9b`）、书源地址变更迁移书籍（`3d36b603`）、书签搜索 SQL 括号；数据安全：备份加密失败中止、恢复 DB 段事务化（`b2ce2f5b`）、迁移 `DROP INDEX IF EXISTS`、MobiFile fd 关闭；MD3 主题包导入（`c75e669e`）、无头标题复合迁移（`154d84dd`）、漫画章末图片自然高度。产物 30,934,923 字节，aapt + apksigner 通过。
- 10036（`3.26.090812c`，2026-09-08）为全新上游基底（legadoC v3.26.090809 `e3ee7b81`）重植首版，重植清单有遗漏，已由 10037 补齐。
- 10035（`3.26.090801c`，2026-09-08）为旧基底最后一交付（朗读引擎网络导入 `0bb36aac`），已在 git 历史重建中被新 main 取代；其改动已并入 10036 重植。

> ⚠️ **语言裁剪边界（2026-09-10 修正）**：`resConfigs "zh"` **会裁掉同语言 region 变体**（不只是其他语言）。产物实测 `aapt dump badging` → `locales: '--_--' 'zh'`，`unzip -l` 中 `zh-rHK|zh-rTW` 计数为 **0**。故 `values-zh-rHK` / `values-zh-rTW`（含 `app/src/{main,c,oss}` 共 6 个目录，约 2986 行）**完全不进 APK**，已于 10038 删除。**此前"缺失 HK/TW 字符串会回退到简体/英文"的说法不成立**——该 locale 整体不存在，`resConfigs` 会裁 region 变体。`companion/移植方案-v3.26.090809.md` 中相反表述已同步修正（commit `4de04287` 提交信息所称「含 HK/TW 修正」实为无效工作）。

> 重植方法论修正（10037 教训）：核对「SK 定制是否全部保留」必须以 `git diff <旧基底> <旧SK main>` 的全量内容比对为准（新增行 + 删除行双向核查），不能只依赖按功能分簇的素材清单——10036 即因分簇清单不全而漏植约十项。

> ⚠️ **重植上游后必须核查并删除 `.github/dependabot.yml`（2026-09-10 补充）**：SK 版**不使用** Dependabot 自动依赖升级（上游 `CCSSNE/legadoC` 仓库仍带该配置，根目录 `.github/dependabot.yml` 声明 gradle / npm / github-actions 三个生态）。因本仓库是独立仓库而非 fork，不继承上游配置，但**每次从上游合并/重植都会把该文件重新带入**——已因此删除过两次（`e080aa96`、`fe4d57a8`）。重植后执行 `git cat-file -e HEAD:.github/dependabot.yml` 确认不存在；若被带入则删除并单独提交（`chore: 删除 dependabot 配置，停用自动依赖升级 PR`）。
> **历史遗留 PR 处理**：2026-09-10 已将 Dependabot 积压的 8 个 PR（#61~#68）全部关闭并附说明。判断依据可复用：① `modules/web` **不参与 APK 构建**（`settings.gradle` 仅 `include ':app'` / `':modules:book'` / `':modules:rhino'`），故其 20 个 npm 升级 PR 无落地路径；② `gradle/libs.versions.toml` 的 kotlin/ksp/AGP/wrapper 属**已验证的构建工具链组合**（wrapper 8.14.4 + AGP 8.13.2），跨大版本升级会破坏 `assembleAppRelease`，不得自动合入；确需升级的依赖一律**手动评估后单独提交**，不引入机器人 PR。

每次交付后当场更新本节。历史发布信息从 Git、GitHub Release 或 `companion\项目文档.md` §2.1 版本索引 / `companion\发布版更新记录.md` 查询，不在本文件累积。

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
- `companion\移植方案-v3.26.090809.md` —— 10036 新基底重植总方案（§3 顺序 / §4 分簇 / §8-9 实施与补齐记录）；追溯"某项为何这样重植"时读它。
- `companion\移植方案素材-1~4-*.md` —— 重植分簇**分析报告全文备份**（含 git 取证与行号锚点，是上述方案 §4 的依据）；只读归档。

**当前仍缺失、待重建/迁移的配套文档**（AGENTS 多处引用但当前检出不存在，不要凭空假设其内容）：
- `companion\编译注意事项与排错手册.md` —— 每次编译前必读；缺失时按 §3「本机环境与正式命令」已含内容执行，重建后补回。
- `companion\代码审查报告-第三轮.md` —— §6 交付记录引用的审查报告，缺失。
- `release\legado_sk_3.26.090212c_10026_arm64-v8a.apk` —— §6 记录的 10026 产物，当前检出没有；需从 GitHub Release 重新下载。
- `backup\` —— 真机数据备份，本机尚无。

**已归档（2026-09-10 文档精简，源码见 `test-records/doc-cleanup/archived/`）**：`新上游基底重做清单.md`（自述使命完成，独有信息已迁入 `项目文档.md` §3 与 `发布版更新记录.md`）、`移植方案-上游三项增量.md` / `移植方案-换源弹出卡片.md`（结论已并入 `发布版更新记录.md` 10031/10029 节）、`docs/ui-design-spec.md`（与 §4 重复的陈旧分叉，且唯一差异的"工作模式分级"经作者裁决已废止）。

**用途约定**：AGENTS.md 本身随公开仓库分发（其中 §2/§3/§7 为机器专属运行信息）；凡机器/本机信息应只写进 AGENTS 检出副本与 `companion\`，不要新增进公开可读的 README/docs。本机 `~/.dsh/AGENTS.md` 为本机级总则，与仓库 AGENTS.md 并行。

**子代理中间产物**（见 §1.5）：探查报告、审查结论、运行时的 UI dump / 日志一律写入 `test-records\<任务名>\`（同样被 `.gitignore` 忽略），**不提交、不推送**；交付完成后可清理临时 dump，只保留方案与审查结论备查。

### 7.3 仓库检出状态与首启清单

- 当前目录已是完整 git 仓库（2026-09-05 `git init` 并对齐 `origin/main` 历史，gh auth 直连推送成功）；提交时勿把机器路径/`companion\`、`release\`、`build_logs\` 误提交。
- 首次正式编译前：确认 SDK36/build-tools 36 已装（已装）、`android-36` platform 存在；首次 `gradlew.bat` 会自动下载 gradle-8.14.4（联网）。
- android-dev 高级调试工具链依赖 `.android-dev-venv\`（uiautomator2/frida/adbutils）与 `tools\android-dev\bin\frida-server-17.17.0-...`，本机未就绪；仅当需要 Perfetto/Winscope/Frida 分层调试时再重建，不影响常规编译/模拟器回归。
