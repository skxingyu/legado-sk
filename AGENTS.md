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
```

编译成功后必须把新 APK 收进仓库根已忽略的交付目录：
1. 覆盖 `C:\code\ai-code\legado-sk\release\legado-sk-arm64-v8a.apk`（「当前交付 APK」，固定名；`/release` 已被 .gitignore 忽略）。
2. 按版本命名同存于 `C:\code\ai-code\legado-sk\release\`：`legado_sk_<versionName>c_<versionCode>_arm64-v8a.apk`。

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
- **语言裁剪边界**：`resConfigs "zh"` **会裁掉同语言 region 变体**（`zh-rHK`/`zh-rTW` 与繁体、其他语言一样被裁，只保留精确 `zh`）。产物实测 `locales: '--_--' 'zh'`、`unzip` 中 HK/TW 计数为 0，故 `values-zh-rHK|rTW` 是**不进 APK 的死资源**（已于 10038 删除），不存在"HK/TW 回退到简体或英文"的情形。详见 §6 的语言裁剪边界注。

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

- ✅ **10041（`3.26.091201c`）已装雷电模拟器（2026-09-12）——当前交付（内置书源加入作者授权校验，仅限阅读SK使用）**：
  - **背景**：10040 内置的番茄书源任何人拿到都能用，作者要求加「验证版本号与阅读名称」的机制——非作者发布版不得使用。
  - **实现（书源层面，不影响阅读器其他功能）**：
    - `AppConst.appInfo` 新增 `packageName` / `appName`（后者取 `getApplicationLabel`，即 manifest 经 `${app_name}` 占位符解析后的名称）。
    - `JsExtensions` 新增 `getAppPackageName()` / `getAppName()` / `matchApp(包名, 应用名)`——**内核不固定任何版本事实**，期望值由书源自行声明传入。
    - 内置书源 `jsLib` 末尾定义 `fqAuthOk()`（`this.java.matchApp('io.legado.app.c', '阅读SK')`）与 `FQ_AUTH_DENIED` 提示文案（含作者仓库地址）。
    - 守卫落在真正承载结果的入口：`ruleSearch.bookList` / `ruleExplore.bookList` 非授权时 `toast` + `result = []`；`ruleContent.content` 非授权时 `toast` + `throw`。`searchUrl` 只拼 URL、不产结果，故不插桩。
    - `bookSourceComment` 写明「仅限「阅读SK」使用，其他客户端无法使用」。
  - ⚠️ **两个写书源 JS 时必须避开的坑（本次都踩过并已修）**：
    1. **`@js:` 必须在规则字符串首位**——legado 只识别开头的 `@js:`，把它挤到第二行整段会退化为字面量，守卫静默失效。
    2. **JS 字符串里的换行必须写成转义序列 `\n`**——直接写入字面换行会截断字符串导致语法错误。用脚本改书源 JSON 时务必用原始字符串，并**用真实 JS 引擎（node）校验语法**，肉眼看不出来。
  - **测试**：`BuiltinSourceGuardTest` 5 项（直接校验资产文件本身、零 Android 依赖）：`@js:` 前缀完好、结果入口都调 `fqAuthOk`、jsLib 声明 SK 身份、文案含转义换行、备注声明范围。连 `DefaultDataSeedTest` 共 7 项通过。
  - ⚠️ **测试写法注意**：`DefaultData.builtinBookSources` 依赖 `appCtx.assets`，**在 JVM 单测里会 ClassNotFoundException**；校验资产内容请直接读 JSON 文件（Gradle 单测 CWD 为模块目录 `app/`）。
  - **实证（雷电模拟器）**：① `pm uninstall` 后全新安装 10041，日志 `内置书源播种：候选 1，已存在跳过 0，实际写入 1` ✅；② SK 版发现/搜索**正常放行**（拉到《我不是戏神》并成功入架）✅；③ 经阅读A 的 Web 服务 `saveBookSources` 导入后，非 SK 包名 `io.legado.app.yuedu.a.release` 下守卫按预期拦截 ✅（注意 `172.16.1.15:1122` 是**模拟器自身 wlan0 地址**，宿主机连不上）。
  - 产物 `release/legado_sk_3.26.091201c_10041_arm64-v8a.apk`（30,967,603 字节，sha256 `c27863ee…`），aapt（包名 io.legado.app.c / 10041 / 3.26.091201c / 阅读SK / arm64-v8a / locales 'zh'）+ apksigner(exit 0) 通过。
  - ⚠️ **已知遗留（未解决，非本次引入）**：书源播种按 `bookSourceUrl` 判重且为**一次性**，故**存量装机升级不会更新已存在的内置书源**——10040 及更早已播种的「无守卫」书源，升级到 10041 后**仍是旧版、守卫不生效**。新装与手动删除后重装的用户不受影响。若要覆盖存量，需先设计「区分用户改过 vs 原样未动」的判据，属独立议题。
- 10040（`3.26.091112c`，2026-09-11）出厂内置「番茄小说」书源，接通内置书源播种链路（**该 GitHub Release 已按作者要求删除，tag `v3.26.091112-10040` 一并清理；改动已并入 10041**）：
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
- 下一次交付 versionCode 从 `10042` 递增。

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
