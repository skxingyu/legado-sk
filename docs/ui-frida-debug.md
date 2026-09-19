# Frida UI 调试窗口速查（雷电模拟器 / 阅读SK）

> 用途：给模拟器里运行的 App 注入带悬浮窗的可调节调试面板（如"背景板下移 offset"）。
> ⚠️ **本机工具链当前未就绪**，以下为就绪后的用法；事实基准以 `tools/android-dev/` 源码为准。
>
> **环境（与仓库现存脚本一致）**：
> - frida 客户端版本以 `frida_probe.py` 实际运行的 `frida.__version__` 为准（打印在输出 JSON 的 `frida` 字段）。
> - frida-server 由 `run-frida-probe.ps1` 自动 push 到模拟器 `/data/local/tmp/legadoc-frida-server`，以 **root** 启动（`su -c`、`nohup ... -D`），**监听 27042**。
> - 传输：`run-frida-probe.ps1` 负责 `adb connect` + 推 server + 端口占用校验，然后调 `frida_probe.py`；后者执行 `adb forward tcp:<port> tcp:27042`（**目标端固定 27042**，`--port` 默认同为 27042）。
> - Java 桥来自 `frida_tools\bridges\java.js`（见 `frida_probe.py` 的加载方式），用 `const Java = bridge;` 绑定。
> - 依赖 `.android-dev-venv\Scripts\python.exe` 与 `tools/android-dev/bin/frida-server-17.17.0-android-x86_64`（模拟器内核须为 `x86_64`）。**这两项在本机当前检出中均不存在**（`bin/` 被 `.gitignore` 忽略），需先重建。
> - ⚠️ 历史文档曾引用驱动脚本 `ui_drop_ball_inject.py` 与 `.android-dev-venv`，**前者在仓库及全部 git 历史中均不存在**，勿再按该名称查找。

## 注入脚本要点

1. **Java wrapper 陷阱（实测）**
   - 实例方法/字段的属性访问返回 `undefined` → 一律 `Java.use(Cls).method.call(instance, ...)`。
   - 静态成员（`Color.WHITE`、`FrameLayout.LayoutParams.MATCH_PARENT`、`ActivityThread.currentActivityThread()`）全不可用 → 常量硬编码：`MATCH_PARENT=-1`、`WRAP_CONTENT=-2`、`Gravity.CENTER=0x11`、颜色转 signed int（`#D920262E → -652204498`）；拿 Activity 用 `Java.choose` 而非静态方法。
   - JS string 传 String 参数（中文）失败 → 包 `JString.$new(txt)`；字号用 `setTextSize(0, px)`（COMPLEX_UNIT_PX），否则 14sp 会被密度放大 3.5×。
   - Java null 就是 JS `null`，用 `v === null` 判断。

2. **LayoutParams 字段写失效**：`lp.gravity/height/topMargin` 赋值是静默 no-op → 边距用 `MarginLayoutParams.setMargins.call(lp, l,t,r,b)` 后 `View.setLayoutParams.call(v, lp)`；改高度/平移优先 **translationY** 或 **整体 marginTop**。
   - **坑**：`setPadding(top)` 只影响内容起点，字幕滚动到中/后段时屏幕上无任何变化（本次"没效果"根因）。要让"文字区整体下移"必须平移滚动容器本身（marginTop）。

3. **悬浮窗**
   - 挂到 `decor.findViewById(android.R.id.content)`（ContentFrameLayout）；每次注入前先移除 content 的 idx≥1 子视图（残留面板不随会话结束消失，且按钮会指向已卸载 JS → 点击崩溃）。
   - 按钮用 clickable TextView（Button 有 48dp 最小高度）；按钮识别用 `View.setTag(Integer)` + 回调里 `Integer.intValue(getTag())`——**别用 frida wrapper 引用 `===` 比较**（不一定成立）。
   - 点击回调 = `Java.registerClass` 实现 `View$OnClickListener`，全局只注册一次、实例可复用。
   - 面板文本必须含「Frida 补丁已注入并生效」，生命周期=会话生命周期（无失效时间）。

4. **崩溃规避**：onCreate hook 里立即建复杂 UI 曾 SIGABRT → `Handler.postDelayed(runnable, 300)` 再挂面板；所有 UI 操作 `Java.scheduleOnMainThread`。

5. **会话规则**：同一进程只允许一个 frida 会话可靠用 Java 桥（第二会话 `Java.choose` 空转/树不全）→ 调试/查询脚本要么独占、要么一次做完 detach。

6. **CheckJNI 跨帧 jobject 崩溃（2026-08-20 实测，必坑）**
   - 症状：注入后一切正常（悬浮窗已上屏），一旦 hook 触发（如拖动开始）立即 **SIGABRT 闪退**。logcat 报：
     `JNI DETECTED ERROR IN APPLICATION: JNI ERROR (app bug): jobject is an invalid JNI transition frame reference: 0x... (use of invalid jobject)` + `CallObjectMethod` / CheckJNI 栈。
   - 根因：LDPlayer 是 userdebug 构建（`ro.debuggable=1`），**CheckJNI 强制开启**；frida 桥把**跨 JNI 调用帧持有的 jobject**（hook 的 `this`、Java.choose 找的实例、一个回调里 new/查出的 View 存到 JS 全局变量、再在另一个回调/定时器里调用方法）喂给 ART，CheckJNI 直接 abort——JS 侧 catch 不到，进程当场死。
   - 判定：**与 frida 版本无关**（16.7.19 与 17.17.0 均复现过），桥每次从 JS 进 Java 都是一个独立 JNI frame；对象跨 frame 复用的正确姿势是 **`Java.retain(obj)` 后存 JS 全局**。
   - 规避清单：
     - hook 里要把 `this` 留下来稍后用 → `stored = Java.retain(this)`。
     - `Java.choose` 要留实例 → `firstMatch = Java.retain(instance)`（frida_probe.py 已这么做）。
     - 面板/覆盖层 View 与 contentFrame 等 JS 全局 → 创建/找到后立即 `Java.retain()`。
     - 同一回调内部“查出→立刻用”的对象无需 retain。
   - 触发陷阱：`Java.registerClass` 注册的 Runnable/Listener 由 Java 侧回调进 JS，等于换了一个 JNI frame；里面调用任何“早先存下的”对象方法都必须已 retain。

## 验证方法

- RPC `exports` + 驱动自检：`set_offset → get_state` 读回，确认读写对称。
- 交互链路必须 **adb `input tap` 真实点击**按钮绝对坐标（`View.getLocationOnScreen(int[2])`，别用 getLeft 相对坐标）→ 读回状态变化。
- 字幕区旋转动画导致 `uiautomator dump` idle 失败 → 用 frida RPC/视图读取代替。
- 落源码前先复位残留 padding/margin（布局原始值 0）再设 offset。