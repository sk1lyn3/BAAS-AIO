# BAAS AIO 安卓 App · Compose 界面（compose-ui-v3）维护交接

给接手维护的 agent 看。用户最终确认的版本是 **`compose-ui-v3` 分支**（代码最后一次改动 `4cf808f`，之后只追加了交接文档提交）。给用户看的对比文档是 `CHANGES-COMPARISON.md`。

## 1. 仓库与分支

- 仓库：`/workspace/baas/compose-refactor`；完整历史 bundle：`baas-aio-compose-ui-v3.bundle`（`git clone baas-aio-compose-ui-v3.bundle -b compose-ui-v3`）。
- 布局：`app/src/main/java/moe/baas/aio/`（全部 Kotlin，一个包）、`app/src/main/aidl`（IPC）、`app/src/main/python`（Chaquopy 任务脚本）、`app/src/main/cpp`（OCR JNI）、`app/src/debug`（调试入口与探针）、`app/src/test`（Robolectric 测试）、`docs/`（本交接文档）。

| 分支 | 说明 | 建议 |
|---|---|---|
| `main` / `origin/dev` / `newpkg/*` | 上游，UI 基线 `509771e` | 保留 |
| **`compose-ui-v3`** | **最终版** | 保留，作为后续开发基础 |
| `compose-ui` | v1：Material3 + 单 Activity 重设计，已被用户否决 | 可删 |
| `compose-ui-v2` | v2：以还原原版为先 | 可删（已被 v2.1/v3 包含） |
| `compose-ui-v2.1` | v2.1：修 Tab 闪烁 + 滚动条 | 可删（v3 基于它） |

删除前确认 bundle 已备份。不要 force-push 或改写 compose-ui-v3 历史。

## 2. 构建

依赖（都在 box 上）：
- `source /workspace/baas/deps/env.sh`：JDK17（`$HOME/jdk`）、Android SDK（`$HOME/sdk`）、`OCR_PREBUILD=/workspace/baas/deps/ocr-prebuild`（gitee `baas_-cpp_prebuild` 的 `android-arm64-v8a` 分支）、`BUILD_PYTHON`（cpython 3.10）。
- BAAS 源码 `/workspace/baas/deps/baas-src` **必须是 git 仓库**，并且 `config/` 已 `git add -f` 提交（它被 .gitignore 忽略；否则 `prepareBaasCore` 报 `KeyError: core/config/default_config.py`）。

```bash
cd /workspace/baas/compose-refactor
source /workspace/baas/deps/env.sh
P="-PbaasSource=/workspace/baas/deps/baas-src -PocrPrebuild=$OCR_PREBUILD -PbuildPython=$BUILD_PYTHON"
./gradlew --no-daemon --max-workers=2 :app:assembleDebug $P        # → app/build/outputs/apk/debug/app-debug.apk（约 204 MB）
./gradlew --no-daemon --max-workers=2 :app:testDebugUnitTest $P    # 37 个用例，约 2–5 分钟
```

- 内存紧：`gradle.properties` 已设 `-Xmx1536m`、单 worker、无 daemon，别改大并行。测试 JVM 堆 1024m。
- **APK 干净打包**：`app/build.gradle.kts` 末尾的 `freshPackageDebug/Release` 任务会在打包**之前**删掉旧 APK 和 `intermediates/incremental/packageDebug`。不要删这段：增量打包会在 APK 里留下约 75 MB 空洞（曾经膨胀到 279 MB）。也不要改成在打包任务的 doFirst 里删（试过，会打出只有 3 个条目的坏包）。
- 每次打包 `baas-source.zip`、`ocr-resources.zip` 都会重新生成，这是正常的。
- grep 用 `timeout`，`rg` 在这个树上曾经很慢。
- git 作者：`git config user.name foreskiller; git config user.email sk1lyn3e@gmail.com`。

## 3. 测试

| 测试类 | 内容 |
|---|---|
| `ShotTest` | 主页 5 个 Tab × 浅/深 × 竖/横，以及配置、日志、配置文件、调度编辑页的展示截图；主题切换 |
| `IdleShotTest` / `DialogShotTest` / `PreviewBarTest` | 等待页、弹窗前后对比、全屏预览顶栏方案对比 |
| `MotionTest` | 手动时钟逐帧截图（动效 GIF 素材） |
| `ResourcesTest`（Render/Transition） | 资源行宽度/缩字号、切 Tab 不闪"—"、滚轮只在开关打开时播放、结束不跳 |
| `ScheduleEchoTest` | 调度编辑"立即"马上回显 |
| `StateTransitionTest` | 托管开→关恢复启动按钮；任务各状态序列；探测开始/结束 |
| `LandscapeLayoutTest` | 873×393/915×412/800×360/851×393 横屏，预览窗格宽度占 0.40–0.55；主题覆盖只带 uiMode |

- 截图输出目录：系统属性 `shot.dir`，默认 `/workspace/baas/out/v3-shots/latest`；改目录用 `./gradlew -DshotDir=/tmp/x ...`。
- 重新生成展示截图：跑 `--tests moe.baas.aio.ShotTest`（以及 Idle/Dialog/PreviewBar）。
- 像素回归做法：改代码前后各输出一次到不同目录，用 PIL `ImageChops.difference` 比较（清理那一轮 121 个文件全部 0 差异）。
- 测试之间有残留（无限动画、协程），偶尔会出现 `AppNotIdleException` 或 `UncaughtExceptionsBeforeTest`。单独重跑通过即可；`StateTransitionTest` 已改成手动时钟来规避。

## 4. 界面代码地图

| 文件 | 职责 |
|---|---|
| `HomeActivity.kt` | 主界面：Root（横竖屏布局）、预览面板、5 个 Tab 的数据准备（renderPage）、轮询、资源读数缓存、托管页 |
| `HomeV3.kt` | v3 视觉组件：状态主卡、光带、状态标签、悬浮底栏、资源胶囊/滚轮 |
| `Ui.kt` | 调色板（`Ui.night` 决定深浅色的颜色 getter）、BText/BButton/BCard/开关/分段等基础组件、`softShadow`、`roundedMask` |
| `Inputs.kt` / `ChoiceMenu.kt` / `BaasInput.kt` / `NumberInput.kt` | 输入框、下拉框、数字输入 |
| `Scrollbar.kt` | 仿原版 ScrollView 的细滚动条 |
| `Appearance.kt` | 主题模式（跟随系统/浅/深）、`apply`（attachBaseContext 里调用）、即时切换 + 淡出 |
| `UiTheme.kt` | 窗口/状态栏样式、insets、原生弹窗 `dialog` + `styleDialog` |
| `PreviewActivity.kt` / `PreviewUi.kt` | 预览基类（Shizuku、Surface、触摸转发、状态文字）；全屏预览顶栏方案 B |
| `PreviewIdleActivity.kt` | 虚拟屏占位页（独立 taskAffinity） |
| `PanePage.kt` | 横屏右侧页面栈（PaneHost/PanePage/PaneActivity） |
| `ProfileActivity` / `ConfigActivity` / `ConfigForm` / `SettingsPanel` | 配置文件管理、配置分组与字段表单、设置面板 |
| `ScheduleActivity` / `ScheduleTable` | 调度编辑、调度表（拖拽排序） |
| `LogActivity` / `LogViews` | 日志页与日志列表 |
| `ManualTaskPanel` | 手动任务按钮面板 |
| `LiveTask.kt` | 把 TaskRuntime 快照/日志桥接成 Compose 状态；`tick` 由 2 秒轮询递增 |
| `UiNotice.kt` / `AboutDialog.kt` | 提示浮层、关于 |

非 UI：`TaskService`、`TaskRuntime`（含 TaskGate）、`ManagedMode`（+Receiver）、`SocketBroker`、`CompanionBridge`、`ShizukuAccess`、`ProfileStore`、`ConfigCatalog`、`TaskOrder`、`TaskNotifications`、`FailureFrames`、`OcrService/OcrSession/NativeOcr/WorkflowOcr`、`ManualTasks`。

## 5. 状态模型与刷新规则

- `TaskRuntime.state / isActive / isProbeRunning` 和 `ManagedMode.enabled / phase / nextWakeAt / ...` 的 getter 内部都会先读一个 Compose 快照 `revision`。`publish`、探测开始/结束、托管 prefs 的每次写入（OnSharedPreferenceChangeListener）都会递增它。**所以在 Composable 里直接读这些值就能自动重组，不需要手动加触发器。**新加的单例状态也要照这个做。
- `previewAvailable` / `previewGameRunning` / `shizuku` 是 Activity 里的 `mutableStateOf`。`launchPreviewGame` 会先乐观地返回 `gameRunning=true`，之后靠 `refreshPreviewState()` 纠正；触发时机：2 秒轮询、任务状态回调、点停止托管、onResume。
- 启用规则与 509771e 一致（行号指 509771e 的 HomeActivity.kt）：
  - 启动游戏 `!gameRunning && !isActive && !Managed.enabled && runnable`（:804）
  - 关闭显示 `!isActive && previewAvailable`（:805）
  - 运行任务 `!isActive && !Managed.enabled && runnable && !probe`（:828）
  - 停止任务 `isActive || Managed.enabled`（:829）
  - 启动托管 `!Managed.enabled && !isActive && !probe`（:852）
  - 停止托管 `Managed.enabled`（:853）
  - 重试 `Managed.enabled && !isActive`（:857）
- 完整审计表见 `docs/REFACTOR-LOG.md` 的"状态审计"一节。

## 6. 不要动的接口和约定

- AIDL：`ISocketBroker.aidl`、`IOcrService.aidl`；Shizuku 用户服务参数 `SocketBroker.serviceArgs`。
- 给 Python/Companion 调用的方法：`ManagedMode` 的 `@JvmStatic`（selectedPaths、sessionToken、profileRecovery、saveRecovery、recoveryNotice、setExecuting、deviceReady……）、`TaskGate`（waitForNext、checkpoint、queue 等）、`CompanionBridge`（replyJson、brokerInfo、probeState）。在 Kotlin 里看着没人用，**是 Python 侧在调用**，别当死代码删。
- Manifest：Activity 类名、`HomeActivity` 的 `singleTask` + `configChanges="orientation|screenSize"`、`PreviewActivity` 横屏 + exported + extra `gamePackage`/`verify`、`PreviewIdleActivity` 的 `taskAffinity=moe.baas.aio.virtual`、`OcrService` 的 `:ocr` 进程。
- TaskService 的 action 名、Intent extra（`task`、`profileId`）。
- 数据格式：profile 目录（profile.json/config.json/event.json/switch.json）、SharedPreferences `managed`、`appearance/mode`、task-state.json。
- debug 源集里的入口（ProfileProbe、RecognitionProbe、WorkflowTarget 等）。

## 7. 踩过的坑

1. **主题不能用 `createConfigurationContext`**：它会把启动时的整份 Configuration（包括方向）锁住。HomeActivity 旋转时不重建，结果横屏还按竖屏排版，预览铺满全屏。现在用 `applyOverrideConfiguration`，只覆盖 uiMode 的夜间位。
2. **Composable 里读普通单例字段不会重组**：原版靠 2 秒轮询设 isEnabled，Compose 版必须让状态可观察（见第 5 节）。"托管开→关后启动游戏一直灰"就是这个原因。
3. **无参 @Composable 会被跳过**：`Fields()` 这类函数要读刷新计数（renders），不然 JSONObject 改了也不重绘（调度"立即"不回显）。
4. **切换动画里滑出的页面会被重建**：如果数据放在 `remember` 里，就会闪一下空值"—"。需要在过渡里保持的数据要提升到 Activity 级缓存。
5. **同一个数值别在两个渲染器之间切换**：滚轮动画和静态文字字宽、行框不同，切换瞬间会跳。
6. **basicMarquee 的边缘渐隐会盖住首字**：前面加比渐隐区宽的空隙，再整体左移补偿。
7. **SurfaceView 不受 Compose 裁剪**：圆角要在四角画背景色来遮（`roundedMask`）。
8. **LaunchedEffect 做淡入会闪**：它在第一帧不透明画完之后才执行。
9. **Robolectric 的局限**：截图看不出 graphicsLayer 的透明度；`configurationChange` 不会改变窗口尺寸，手动主题下还会重建 Activity，所以横屏测试直接以横屏启动。
10. **增量打包的 APK 会膨胀**（见第 2 节）。
11. `PanePage.createView` 里的 `Content()` 会被解析成 `ComposeView.Content` 并无限递归，要写成 `this@PanePage.Content()`。

## 8. 约定

- 提交作者 foreskiller，提交信息用英文、按逻辑拆分；REFACTOR-LOG.md 用简体中文记录每轮变更（根因 → 修复 → 测试 → 待真机确认）。
- **界面文案目前直接写在代码里**（简体中文），和原版一致，**没有 strings.xml**（只有 v1 分支抽取过）。若要做 i18n，抽取时要保持文字逐字不变。
- 颜色只用 `Ui.*` getter（自动适配深浅色），不要硬编码；原生弹窗用 `UiTheme.dialog` + `styleDialog`。
- 改界面前后跑 ShotTest 并做像素比对；行为改动先对照 509771e 和 `docs/ORIGINAL-UI-SPEC.md`。
- 交付给用户：APK 切成 30 MB 分块传到 `C:\Users\lenovo\`，在 PowerShell 用 FileStream 拼接，核对 SHA256 后删除分块。

## 9. 文档

（在仓库里，REFACTOR-LOG.md 和 ORIGINAL-UI-SPEC.md 位于仓库根目录，`docs/` 下只放交接文档、UI-AUDIT 和图片；交接包里三份都放在 `docs/`。）

- `docs/ORIGINAL-UI-SPEC.md`：原版 UI 规格（dp/sp/颜色/跳转）。
- `docs/REFACTOR-LOG.md`：完整重构日志（开头有阅读指南，标出了已作废的章节）。
- `docs/UI-AUDIT.md`：重构前的 UI 审计报告（基于 2c088c3）。
- `CHANGES-COMPARISON.md`（`.html`）：给用户看的原版与 v3 对比。
