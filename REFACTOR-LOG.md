# BAAS AIO · Jetpack Compose UI 重构日志

分支：`compose-ui`（基于 `2c088c3`） · 依据：`/workspace/baas/UI-AUDIT.md`

## 1. 目标
- 把全部原生 View 手写界面（无 XML、`removeAllViews` 重建、主线程轮询）迁移到 **Jetpack Compose + Material3**。
- **行为零回退**：功能清单、TaskService action、AIDL、Shizuku 用户服务、Chaquopy 调用、`PreviewIdleActivity` 独立 `taskAffinity`、debug 入口、包名、配置/数据格式全部保持不变。
- 补齐审计指出的问题：深色模式、无障碍、状态保存、i18n、insets/刘海屏、加载/空/错误状态。

## 2. 架构（前 → 后）
| 方面 | 重构前 | 重构后 |
|---|---|---|
| Activity | Home/Profile/Config/Schedule/Log/Preview 六个 Activity，Home 继承 PreviewActivity（上帝类） | 单 Activity `HomeActivity` + Navigation Compose（home / profiles / config / schedule / log）；`PreviewActivity` 仅保留为全屏沉浸预览 |
| 预览/IPC | 写在 PreviewActivity 里 | 抽出 `ui/preview/PreviewController`（生命周期观察者，持有 Shizuku 绑定、SurfaceView、触摸转发），Compose 通过 `AndroidView` 承载 |
| 状态 | Activity 字段 + Bundle，Handler 每秒轮询 | `HomeViewModel`/`ConfigViewModel`/`ScheduleEditViewModel` + `StateFlow`，`SavedStateHandle` 抗进程死亡 |
| 数据 | UI 直接读写 JSON、按显示文字判断状态 | `ui/data/Models.kt` 数据类与枚举（`TaskState`、`TaskSnapshot`、`FailureRecord`…）；`ProfileRepository` 在 `Dispatchers.IO` 上包装 `ProfileStore`/`ManagedMode` |
| 刷新 | 主线程轮询 | `Runtime` 把 TaskRuntime/ManagedMode 回调桥接成 Flow，`FileObserver` 监听配置目录变化 |
| 主题 | `UiTheme` 硬编码颜色/尺寸 | `ui/theme`：Material3 浅/深色、可选动态取色（品牌蓝 #008FD3 兜底）、Spacing/Typography/Shapes tokens、`ExtendedColors` 语义色 |
| 文案 | 硬编码中文 | `res/values/strings.xml`（约 330 条，简体中文默认） |
| 提示 | Toast/自绘浮层 | `NoticeBus`（SharedFlow）+ `NoticeHost`（Material3 Snackbar），`UiNotice` 保留为兼容入口 |

包结构：`ui/theme`、`ui/common`（卡片、折叠卡、分段按钮、对话框、Stepper/Choice 输入）、`ui/data`、`ui/preview`、`ui/home`、`ui/profile`、`ui/config`、`ui/schedule`、`ui/log`。

## 3. 各页面变化
- **首页 5 个 Tab**（首页/托管/调度/日志/设置）：竖屏底部 `NavigationBar`，横屏 `NavigationRail` + 左侧预览、右侧内容；Tab 选择、筛选、排序存 `SavedStateHandle`。
- **首页**：当前配置、运行状态、下次运行、任务列表（按下次执行/优先级排序，沿用 `TaskOrder`）。
- **托管**：开始/停止、配置勾选（附体力/信用点/青辉石读数与无障碍描述）、失败记录折叠区、失败详情对话框（画面预览、保存图片、复制文本、重试）。
- **调度**：筛选（全部/已启用/已禁用）、全部启用/禁用、立即刷新、全部延后；**拖动排序**保留，并为每行提供“上移/下移”无障碍自定义操作作为替代。
- **调度编辑**（原 ScheduleActivity）：执行顺序、间隔（含常用预设与人类可读提示）、下次执行（北京时间，日期+时间选择器）、每日重置（北京时间显示、UTC 存储，与原逻辑一致）、禁用时段、前/后置任务多选、“执行参数”跳转到对应配置分组。
- **配置**（原 ConfigActivity）：复用 `SettingsPanel`；从调度跳转时自动展开并滚动到目标分组；手动任务面板在任务运行、未保存、Shizuku 未就绪时直接置灰并给出原因。
- **配置管理**（原 ProfileActivity）：新建/重命名/复制/导出/删除、导入 JSON/ZIP（SAF），运行中锁定。
- **日志**：`LazyColumn` 逐行渲染、级别筛选、搜索、自动跟随（手动上滑自动暂停，出现“跳到最新”）、复制全部、全屏页。
- **预览**：`AndroidView` 包裹原生 SurfaceView，触摸映射逻辑原样移植；接管/继续、启动/关闭游戏、隐藏虚拟屏、展开全屏。
- **设置**：配置面板、配置管理入口、应用/电池设置、**外观**（跟随系统/浅色/深色、动态取色）、debug 诊断入口（仅 DEBUG）、关于。

## 4. 行为保留清单（对应审计功能清单）
- [x] TaskService 启停与所有 action、AIDL `ISocketBroker`、Shizuku 用户服务参数（`SocketBroker.serviceArgs`）未改
- [x] `PreviewActivity` 类名、横屏、exported、Intent extra `gamePackage` / `verify` 保留（debug MainActivity 仍可用）
- [x] `PreviewIdleActivity` 及 `taskAffinity=moe.baas.aio.virtual` 未改
- [x] `HomeActivity` 仍为 launcher、`singleTask`、`configChanges=orientation|screenSize`（旋转不重绑服务、不重建 Surface）
- [x] `LogActivity` / `ProfileActivity` 类名保留为跳转垫片（debug TaskActivity 入口可用），转发到 Compose 路由
- [x] ConfigActivity/ScheduleActivity（非导出、无外部引用）删除，功能由路由承接
- [x] 配置、event.json、托管偏好、失败记录、导入导出格式不变（全部经由原 `ProfileStore`/`ManagedMode`）
- [x] 每日重置 北京时间⇄UTC 换算、间隔 -1=一次性、配置分组映射等细节逐条对照移植
- [x] 通知颜色保持品牌蓝

## 5. 小巧思
1. **未保存离开保护**：配置页和调度编辑页返回时若有改动，弹“继续编辑/放弃修改”。
2. **先禁用、后解释**：手动任务按钮在不可用时直接置灰，并显示具体原因（运行中/未保存/Shizuku/服务器不支持），不再点了才弹错误。
3. **深链到分组**：调度里的“执行参数”直接展开并滚动到对应配置分组。
4. **日志智能跟随**：上滑查看历史时暂停自动滚动，一键“跳到最新”。
5. **拖拽 + 无障碍双通道排序**：TalkBack 用户可用“上移/下移”操作。
6. **间隔可读提示**：输入 3600 立刻显示“当前：1 小时”；常用预设一键填入。
7. **深色模式下的品牌蓝**：深色主题使用对比度 ≥4.5 的浅蓝，日志区始终使用专用深色底。
8. **资源读数的读屏描述**：例如“体力 120，扫描于 10-10 22:31”。
9. **配置变更自动刷新**：FileObserver 监听配置目录，后台任务修改调度后界面自动更新；编辑中的草稿不会被覆盖（`reloadIfClean`）。
10. **进程死亡恢复**：Tab、筛选、配置草稿、展开状态、手动任务选项、调度编辑草稿都能恢复。

## 6. 已解决的审计问题（摘要）
- 架构：上帝 Activity、逻辑与 UI 混杂、无 ViewModel → 第 2 节架构
- 主线程轮询/`removeAllViews` 重建 → Flow + Compose 增量重组
- 硬编码颜色/尺寸/文案 → theme tokens + strings.xml
- 无深色模式 → Material3 浅/深色 + 动态取色
- 无障碍：缺 contentDescription、触控目标过小 → 图标均有描述，交互元素最小 48dp，文字使用 sp，可随系统缩放
- 旋转/进程死亡丢状态 → SavedStateHandle
- insets/刘海屏 → `enableEdgeToEdge` + `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`，横屏隐藏状态栏
- 缺少加载/空/错误状态 → `EmptyState`、加载占位、错误对话框/Snackbar

## 7. 已知风险 / 需真机验证
- **预览与触摸**：SurfaceView 在 `AndroidView` 中的创建/销毁时机、横竖屏切换时的复用、触摸坐标映射（letterbox）。
- **Shizuku 授权流程**：首次授权、拒绝、服务死亡、超时提示。
- **全屏 PreviewActivity**：`gamePackage` 启动游戏、接管/继续任务。
- **拖动排序** 手感与保存；时间/日期选择器的北京时间换算（建议对比旧版保存出的 event.json）。
- **SAF 导入导出**、失败画面保存到相册。
- **动态取色**（Android 12+）下的对比度；大字体（200%）下的布局。
- 通知点击回到 HomeActivity 的行为；从 debug 入口打开 LogActivity/ProfileActivity 垫片。
- 未执行 instrumented/UI 测试；lint 未全量运行（见第 8 节）。

## 8. 构建说明
```bash
source /workspace/baas/deps/env.sh   # JDK17、ANDROID_HOME、OCR_PREBUILD、BUILD_PYTHON
./gradlew --no-daemon --max-workers=2 :app:assembleDebug \
  -PbaasSource=/workspace/baas/deps/baas-src \
  -PocrPrebuild=$OCR_PREBUILD -PbuildPython=$BUILD_PYTHON
./gradlew --no-daemon --max-workers=2 :app:testDebugUnitTest  # 同样带上 -P 参数
```
- `baasSource` 需为 git 仓库（`config/` 被 `.gitignore` 忽略，需 `git add -f` 提交，否则 `prepareBaasCore` 报 `KeyError: core/config/default_config.py`）。
- `ocrPrebuild` 为 gitee `baas_-cpp_prebuild` 的 `android-arm64-v8a` 分支克隆。
- 内存紧张时保持 `org.gradle.jvmargs=-Xmx1536m`。

---

# v2（compose-ui-v2 分支）：以还原度为先，重新来过

## 1. v1 的问题（如实说明）
- **重新设计越界**：v1 自己加了"外观"设置、额外的确认弹窗和预设，改了导航结构（单 Activity + Navigation Compose），视觉也换成了 Material3 默认风格，和原版对不上。
- **跳转时闪一下别的页面**：ProfileActivity / LogActivity 改成了跳回 HomeActivity 的中转页，NavHost 会先把起始页（设置）渲染一帧，再导航到目标页，所以点"配置文件管理"会先闪一下设置页。
- 间距、字号、圆角、按钮阴影、开关尺寸、表格样式都跟原版不一样，信息层级也被改了。

## 2. v2 的做法
- 原版（2c088c3）就是规格。先完整读一遍原版 View 代码，写成 `ORIGINAL-UI-SPEC.md`（每个控件的 dp/sp/颜色、点击后打开什么、返回栈怎么走），再照着它逐个页面实现。
- **Activity 结构和原版完全一样**：竖屏下 Profile/Config/Schedule/Log 仍然是独立 Activity（没有中转页，所以不可能闪屏）；横屏下仍是 HomeActivity 右侧的 PanePage 栈（`PaneHost`）。返回键、onActivityResult、PreviewIdleActivity 的 taskAffinity 都没动。
- 用 Compose 基础组件（`Ui.kt` / `Inputs.kt`）一一复刻 UiTheme：`BText`（保留 TextView 的 4/6dp 内边距和 includeFontPadding）、`BButton`（平台 Material 按钮的 inset、阴影、按下效果）、`BCard`、`CompactSwitch`、`Segments`、`Chevron`、`BInput`、`NumberField`、`ChoicePopup`。
- 弹窗类（AlertDialog、日期/时间选择、编辑文本/商店/槽位、关于、失败详情）**继续用原版的平台 View 实现**，保证一模一样。
- 2 秒轮询节奏和原版相同（通过 `LiveTask.tick` 触发重组），刷新时机、"· 已保存"提示什么时候消失这类细节也和原版一致。
- 没有新增设置项，没有外观入口，默认浅色界面和原版一致（暂不提供深色模式，避免影响浅色外观）。

## 3. 各页面还原对照（Robolectric + Roborazzi 截图，原版 vs v2）
截图在 `/workspace/baas/out/compare/side-*.png`（左原版｜中 v2｜右差异图，红色是像素差 >40 的地方）。

| 页面 | 竖屏差异 | 横屏差异 | 结论 |
|---|---|---|---|
| 主页 | 1.2% | 3.4% | 一致，差异是字体抗锯齿和 1px 偏移 |
| 托管 | 0.9% | 3.6% | 一致 |
| 调度 | 1.6% | 3.6% | 一致（修正了表格外框：原版的 1dp 描边其实被子视图盖住了，看不到） |
| 日志 | 2.3% | 7.1% | 一致，差异主要来自日志时间戳不同、长单词换行点略有不同 |
| 设置 | 1.4% | 3.2% | 一致 |
| 配置文件（ProfileActivity） | 0.4% | — | 一致 |
| 日志（LogActivity） | 1.0% | — | 一致 |

**截图没覆盖到的**：ConfigActivity / ScheduleActivity 单独页面（需要传入配置 ID）、各种弹窗、拖拽排序过程、全屏 PreviewActivity、真机上的 Shizuku 已连接 / 正在预览状态。

## 4. 新增的动画（只加了这些，外观不变）
- 底部 Tab 切换：内容区淡入淡出
- 设置分组展开/收起：高度动画 + 箭头旋转
- 调度表拖拽排序：被拖的那行半透明跟手，其他行平滑让位，拖到边缘自动滚动
- 开关拨动、按钮按下的过渡

## 5. 已知差异
- Compose 的滚动容器没有原版 ScrollView 那种会自动淡出的细滚动条。
- 文本的换行和抗锯齿由 Compose 排版，个别长英文单词的断行位置可能差一点。
- APK 比 v1 大一些（约 279MB，v1 约 207MB），多出来的主要是 Compose 运行时，以及打包时压缩方式不同。

## 6. 测试中修掉的 bug
- `PanePage.createView` 里的 `Content()` 被解析成了 `ComposeView.Content`，导致无限递归、栈溢出（截图测试打开 ProfileActivity 时发现）。已改成 `this@PanePage.Content()`。

## 7. 真机测试清单
1. 竖屏点"配置文件管理"、"日志全屏"、调度的齿轮、设置里的分组：都应直接打开目标页，不闪屏；返回键回到原来的 Tab，滚动位置保持（调度、日志）。
2. 横屏：右侧打开页面栈，返回键逐层退出。
3. 新建、重命名、复制、导出、删除、导入 ZIP/JSON 配置。
4. 设置页：修改字段、保存/撤销修改、离开时提示保存；ManualTaskPanel 的按钮在任务运行时变灰。
5. 调度：启用/禁用、全部启用、立即刷新、一键推迟、筛选、按时间排序、长按拖拽排序（拖完下次轮询后"已保存"消失，与原版一致）。
6. 托管：开启/停止、勾选配置、失败历史展开、详情弹窗、导出图片。
7. 预览：Shizuku 未安装/未授权/已就绪各状态，展开预览、全屏接管按钮，启动/关闭游戏。
8. 日志：搜索、级别筛选、自动滚动开关、文本可选中。

## 8. 构建
```
./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest -PbaasSource=... -PocrPrebuild=... -PbuildPython=...
```
截图测试：`app/src/test/java/moe/baas/aio/ShotTest.kt`（输出目录由 `shot.dir` 系统属性指定）。

---

# v2.1：修复切换底部 Tab 时内容区闪烁，并打磨细节

## 根本原因
1. **淡入动画写法有问题**：`ContentHost` 用 `LaunchedEffect(page) { fade.snapTo(0f); fade.animateTo(1f) }` 给新页面做淡入。但 LaunchedEffect 要等新页面已经按完全不透明画出第一帧之后才执行，所以实际过程是：新页面完整显示一帧 → 突然变透明（露出背景）→ 再淡入。这就是"闪一下"。
2. **滚动位置晚一帧才恢复**：恢复滚动的 `scrollTo(pendingScroll)` 也是在第一帧之后才执行。切到调度页或回到原位置时，第一帧还是旧的滚动位置，下一帧才跳过去。
3. 日志区每次重新进入时先显示顶部，下一帧才滚到底部。

## 修复
- 去掉淡入，改成和原版 `removeAllViews/addView` 一样的瞬间切换。
- 每次重新渲染时，用目标位置新建 `ScrollState(previousScroll)`，第一帧就在正确位置；`keepScroll` 时沿用原来的实例。
- 日志开着"自动滚动"时，滚动状态直接从底部开始。
- 预览 SurfaceView 本来就在 Tab 内容区之外，切换 Tab 不会重新挂载，不受影响。
- 检查了其他页面的跳转：竖屏各页仍是独立 Activity（没有中转页），横屏 PaneRoot 是瞬间压栈/出栈，都没有中间帧。

## 细节打磨
- **补上原版的细滚动条**（`Scrollbar.kt`）：参数从原版截图里实测——4dp 宽、方角、黑色 31% 透明度、贴在可滚动区域右边缘。行为照搬 View.awakenScrollBars：刚出现时显示 1.2 秒，每次滚动后显示 0.3 秒，然后用 250ms 淡出。
- 已加到首页各 Tab、配置设置、调度编辑、配置文件页、日志区和下拉选择框。

## 验证说明
- 我试过用 Robolectric 写一个"切换后第一帧等于最终画面"的自动测试。但把修复前的代码放回去跑，它也照样通过——软件渲染时 graphicsLayer 的透明度不会体现在截图里，测不出这个问题。所以这个测试没有保留，切换是否还闪需要在真机上确认。

---

# v3（compose-ui-v3 分支，基于 v2.1）：「Halo Sky」视觉与动效

## 设计思路
- **结构不变**：信息架构、Tab 结构、页面跳转、Activity/PanePage 结构、各种操作流程、启用/禁用规则都沿用 v2.1，只改外观和动效，确保基本逻辑不会出问题。
- **视觉语言**：参考蔚蓝档案的清爽天空风格。浅色模式是"天空渐变底 + 白色卡片 + 品牌蓝渐变"；深色模式是"深海蓝底 + 深色卡片 + 更亮的品牌蓝"。点缀用细线光环和斜线，全部用 drawBehind 绘制一次，不用模糊效果，Android 9 也能流畅运行。
- **配色只定义一套**：`Ui` 里每个颜色都是一个 getter，根据 `Ui.night` 返回浅色或深色值；View 端的 `UiTheme`（原生对话框）也读同一套。切换系统深色模式时 Activity 会重建，所以不需要额外的 CompositionLocal，也不会出现两边颜色对不上。
- **动画都很短**（150–300ms 或弹簧），只用位移、缩放、透明度这些开销小的属性。

## 小巧思
1. **悬浮底栏**：一个渐变"药丸"用弹簧动画滑到选中的 Tab，选中的图标会轻微上浮、放大，切换时有轻触觉反馈。
2. **Tab 切换按方向滑动**：往右边的 Tab 切就向左滑，反之向右。数据在切换前已经同步准备好，两页都画在不透明背景上，所以不会出现空白或闪烁；每个 Tab 记住自己的滚动位置。
3. **状态主卡**：品牌渐变背景加光环纹理。状态标签前有呼吸脉冲点（运行中是绿色、暂停是黄色、失败是红色）。任务运行时，卡片底部有一条光带来回流动。
4. **状态文字切换动画**：状态从"待机"变成"执行中 · 竞技场"时，文字像里程表一样上下滚动；托管页的体力、信用点、青辉石数值也是这样滚动更新。
5. **资源显示成小胶囊**：图标加粗体数字，信息更醒目。
6. **按压回弹**：所有按钮、卡片、Tab 按下时会用弹簧轻微缩小再弹回。
7. **分段选择器**：选中项的渐变底块会滑动过去；日志级别改成彩色小标签，选中的带一个小圆点。
8. **开关**：渐变轨道，带阴影的白色圆点，有弹性动画和触觉反馈。
9. **拖拽排序**：长按开始时有触觉反馈，被拖的行会浮起（放大 3.5%、出现深阴影和蓝色描边），原位置留下淡淡的占位。
10. **"已保存"提示**：从原来的文字后缀改成一个弹出的绿色小标签，出现和消失的时机与原版一致。
11. **权限状态标签**：带颜色圆点的小标签（已授权是绿色，否则是红色）。
12. **预览区**：圆角加柔和阴影。SurfaceView 不受 Compose 裁剪影响，所以用"在四角画背景色"的方式做出圆角；未连接时显示光环占位图标；全屏预览的状态和接管按钮改成半透明悬浮小标签。
13. **全面屏和窗口背景**：状态栏、导航栏透明，天空渐变一直延伸到屏幕边缘；窗口背景色设成渐变顶部的颜色，打开或关闭页面时不会闪白。
14. **深色模式**：跟随系统，包括原生对话框（values-night 主题 + UiTheme 的颜色 getter）；状态栏图标颜色也会自动切换。

## 验证情况
- **截图检查过（Robolectric + Roborazzi）**：首页 5 个 Tab，覆盖浅色/深色和竖屏/横屏；配置文件、配置设置、调度编辑、日志这几个页面的浅色和深色。共做了 3 轮"截图 → 挑问题 → 修改"：
  - 第 1 轮：灰色的平台按钮和整体风格不搭；禁用的主按钮是一块灰；深色模式下主卡太亮。
  - 第 2 轮：横屏预览区的状态条是直角，压住了圆角；深色模式下日志区和页面背景分不清；修好了测试本身的问题（Compose 的快照通知要手动推送）。
  - 第 3 轮：调度页的摘要和"已保存"、权限状态改成标签样式；横屏状态条改成悬浮小标签。
- **动效**：`MotionTest` 用手动推进的时钟逐帧截图，合成了 GIF：`out/v3-showcase/motion-light.gif` 和 `motion-dark.gif`，包含底栏药丸滑动、分段选择器、开关、数字滚动、运行时的脉冲和光带。
- **需要在真机上确认**：
  - 低端机（Android 9）上阴影和动画的帧率
  - 触觉反馈的强度
  - SurfaceView 圆角遮罩在真实游戏画面上的效果
  - 横屏刘海屏下的边距
  - 深色模式下各个原生对话框
  - 拖拽排序的手感
  - 切换 Tab 时是否完全没有闪烁（Robolectric 截图测不出透明度问题，见 v2.1 的说明）

### v3 补充：换成真实的 BAAS Logo
- 之前：页眉"BAAS"字样左边是我自己画的光环图标（两个同心圆加一条竖线）；预览区未连接时的占位图标也是自己画的光环。
- 现在：两处都换成真实的 BAAS Logo。新增的 `res/drawable-nodpi/baas_logo_ui.png`（192px）是从原有 `res/drawable-nodpi/baas_logo.png`（880px）用 LANCZOS 缩小得到的；原图与 BAAS 源码里的 `baas/gui/assets/logo.png` 完全相同。用高质量过滤、等比缩放、圆形裁剪：页眉里 24dp（带轻阴影），预览占位 44dp。Logo 自带白色圆底，浅色和深色模式下都清楚。
- 状态主卡的光环纹理只是背景装饰，不是图标，保持不变。关于对话框本来就用应用图标（即真实 Logo），也没改。其他地方都没动。
- 后续调整（按用户反馈）：预览区占位恢复为原来的光环图标；页眉继续使用真实的 BAAS Logo。
