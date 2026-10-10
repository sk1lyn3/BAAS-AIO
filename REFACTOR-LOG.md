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
