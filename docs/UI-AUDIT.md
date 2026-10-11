# BAAS AIO 安卓 UI 审计报告

- 审计对象：`baas-aio/app/src`（main + debug），commit 2c088c3（main 分支）
- 代码根：`app/src/main/java/moe/baas/aio/`（下文路径默认省略此前缀），debug 源集为 `app/src/debug/java/moe/baas/aio/`
- 规模：Kotlin/Java 共约 6300 行；UI 全部为**代码构建的原生 View**（无 XML 布局、无 Compose、无 AppCompat/Material 依赖）
- 严重度：🔴 高（影响正确性/可用性，重写必须解决）｜🟠 中（明显体验或维护问题）｜🟡 低（打磨项）
- 说明：行号基于 grep 静态扫描与抽读，未在真机运行；部分判断（如对比度）为估算。

---

## 0. 统计速览

| 指标 | 数量 | 说明 |
|---|---|---|
| 硬编码颜色（Color.rgb / 0xff… / Color.WHITE 等） | 54 处 | 另有 UiTheme 中 6 个调色常量 |
| 硬编码 textSize | 23 处 | 范围 10f–24f，大量 10–12sp |
| 代码内中文字符串字面量 | ~408 处 | 无 strings.xml |
| `dp(...)` 硬编码尺寸 | ~166 处 | 无 dimens / 设计令牌 |
| 废弃 API 调用 | 23 处 | onBackPressed、startActivityForResult、systemUiVisibility 等 |
| contentDescription 设置 | 18 处 | 大量图标/按钮缺失 |
| `removeAllViews` 整页重建 | 8 处 | 列表无复用 |
| 主线程 Handler 轮询/延时 | 10+ 处 | 2s 调度轮询、4s keepalive 等 |
| 全局单例状态 object | 10+ 个 | TaskRuntime / ManagedMode / UiLogs … |
| ViewModel / Flow / LiveData | 0 | |
| 深色模式支持 | 0 | |

---

## 1. 屏幕与功能清单（Compose 重写需保留的行为）

### 1.1 HomeActivity（`HomeActivity.kt`，1062 行，继承 PreviewActivity，singleTask，launcher）
单 Activity 内用字符串 `page`（"主页/托管/调度/日志/设置"）切换 5 个 Tab（L36, L356），另嵌入 `PanePage` 二级页面栈（`secondaryPages`）。

公共头部 / 预览区（`buildPreviewLayout` L214–L350）：
- 品牌标题 "BAAS"；配置选择器 "xxx ▾"（`chooseProfile` L375，ListPopupWindow）+ "管理"按钮→ProfileActivity（L262）
- 内嵌 SurfaceView 游戏预览（L220，虚拟屏），黑底占位 "游戏未启动"/Shizuku 状态提示（L272, L150）
- 状态文字、"手动接管/继续任务"按钮（takeover）、"关闭显示"（L316）
- 按钮行："展开预览"→PreviewActivity（L343）、"启动游戏"（L344）、"关闭游戏"（L349）
- 横竖屏自适应 `arrangePanels()`（L228），横屏有挖孔屏边距处理
- 底部/顶部 Tab 导航（L356，选中态蓝底白字 L429–430）

Tab「主页」（L443）：开始（core_profile）/停止任务按钮；任务状态（启动中/执行中/等待暂停/已暂停·可手动接管/正在停止/任务失败·请查看日志/本轮已完成/待机/等待下次任务，L811–815）+ 状态色（L826）；运行配置/下次唤醒时间；已启用任务列表（下次执行时间+状态色，L761–797），排序切换"⇅ 下次执行/执行顺序"（持久化到 SharedPreferences L456）；空态"暂无启用任务"+"前往调度"（L781）；手动任务面板 `ManualTaskPanel`；反和谐结果提示（L114–130）。

Tab「托管」（L463）：开启托管（含精确闹钟权限跳转 L472）/停止托管；托管状态与下次执行（L832–860）；多配置勾选开关 `compactSwitch`（L494）+ 资源读数 `ProfileResourcesView`（体力/信用点/青辉石，AP 自然恢复估算）；失败历史（可折叠，"N 条"，清空，L509–530）；历史条目：配置名/时间/结果着色/"立即尝试"重试/失败原因/详情对话框（阶段、开始/失败时间、耗时、最后有效帧图片、导出 PNG via SAF L989）。

Tab「调度」（L533）：摘要"配置 · n/m 个任务已启用"；全部启用/禁用、立即刷新、一键推迟；分段筛选 全部/已启用/已禁用（L552）；排序切换；表头（任务/间隔、下次执行、启用、配置）；任务行 `ScheduleTaskRow`（彩色分类标签、开关、下次执行、进入 ScheduleActivity 编辑）；长按拖拽排序 `ScheduleDrag`（仅"全部+执行顺序"时可拖，L561）；空态"此筛选下没有任务"；2s 轮询刷新下次执行时间（L61–73, L703）。

Tab「日志」（L613）：`LogFilters`（级别分段 5 色、搜索框+清除），`LogOutput` 深色日志面板（200ms 节流渲染、条数"x/y 条"）、"自动滚动"切换、全屏→LogActivity（携带 query/level/follow）。

Tab「设置」（L641）：配置文件管理卡片→ProfileActivity；应用与权限（Shizuku 状态、"应用与电池设置"跳转 L659）；每配置 `SettingsPanel`（ConfigForm 表单，撤销/保存）；Debug 构建"开发诊断"→MainActivity（L664）；关于 `AboutDialog`。

### 1.2 PreviewActivity（`PreviewActivity.kt`，441 行，横屏，也是 HomeActivity 父类）
Shizuku 授权检查与 UserService 绑定（Companion）、15s 连接超时+重试（L40, L277）、Binder 断开处理（L86）；全屏 SurfaceView 渲染虚拟屏，触摸映射转发 `broker.touch`（L393）、拖动/缩放；接管/恢复任务；启动/关闭预览游戏（L191, L197）；单线程 Executor 执行 IPC；失败提示"操作失败，请查看日志"（L415）。

### 1.3 PreviewIdleActivity（独立 taskAffinity，放在虚拟屏上的占位页，24sp 文本）

### 1.4 ProfileActivity（`ProfileActivity.kt`，133 行，竖屏）
配置列表（✓ 选中、名称·区服）；新建（名称+区服 官服/B服/日服/国际服）；设置→ConfigActivity；重命名；复制；导出 ZIP（SAF 201）；删除（确认）；导入 ZIP（202）；导入 config/event JSON（203）；运行中操作统一拦截"请先停止任务"。

### 1.5 ConfigActivity（60 行）：标题"配置设置"，ScrollView 包 SettingsPanel/ConfigForm；未保存离开确认（L47–48）；状态保存。

### 1.6 ScheduleActivity（166 行）：单任务调度编辑——执行顺序、执行间隔（秒）+常用预设（每半小时/每小时/每天/一次性）、下次执行（北京时间，"立即"）、每日重置时间（增删）、禁用时段（本地时间，增删）、前置/后置任务、执行参数（咖啡厅/日程/竞技/制造/商店/总力战/演习/推图/扫荡次数等）；校验提示；未保存确认。

### 1.7 LogActivity（55 行）：全屏日志（标题、自动滚动、返回），状态保存 query/level/follow。

### 1.8 组件
`UiTheme`（调色、按钮/flatButton/segments/card/compactSwitch/dialog/input/chevron/insets）、`ChoiceMenu`（PopupWindow 单选）、`NumberInput`（± 步进）、`BaasInput`（EditText 样式）、`ConfigForm`（JSON 驱动表单：开关、选择、数字、列表编辑对话框、商品/编队编辑）、`UiNotice`（自绘 Snackbar，可带操作，自动消失）、`AboutDialog`、`ProfileResourcesView`、`ManualTaskPanel`（推图/剧情/活动图：故事/任务/挑战，故事数量，活动关卡查看，其他功能如一键反和谐）、`PanePage`（自制二级页栈+状态保存）。

### 1.9 debug 源集
`MainActivity`（诊断主页，选择服务器、输出）、`TaskActivity`、`ProbeActivity.java`、`ProfileProbeActivity`、`RecognitionProbeActivity`、`WorkflowTargetActivity`，以及 `DebugTrace/DisplayDiagnostics/LatencyVerifier/PreviewVerifier/PreviewDiagnostics`。界面为英文、裸 TextView/AlertDialog，风格与正式版不一致（可接受，但重写时建议统一用 Compose 的"开发者选项"页）。

---

## 2. 问题清单

### 2.1 架构
- 🔴 **上帝 Activity**：`HomeActivity.kt` 1062 行，承担 5 个 Tab、预览、托管历史、对话框、SAF 导出、轮询、状态保存；`renderPage()`（L401–680）一个函数拼出所有页面。
- 🔴 **继承耦合**：`HomeActivity : PreviewActivity()`（L23），预览/Shizuku 连接生命周期与首页绑在一起，靠 override `buildPreviewLayout`/`previewMessage`/`onPreviewState` 定制。
- 🔴 **无 ViewModel/状态层**：状态散落于 Activity 字段（L36–101 大量 lateinit View）和全局 `object`（`TaskRuntime`、`ManagedMode`、`UiLogs`…），以回调列表 `TaskRuntime.add/remove(changed)` 推送字符串 JSON 快照。
- 🟠 **数据模型是裸 JSON**：`JSONObject(TaskRuntime.snapshot)` 在 UI 中反复解析（L814, L834, L116），字段名字符串散落，无类型安全。
- 🟠 **业务逻辑写在 UI 里**：调度排序/过滤（L681–702）、时间格式化（L733–760）、托管历史解析（L861–990）、错误原因提取（L122–127）。
- 🟠 用字符串比较判断状态："成功/挂起/停止"（L896–899）、`ManagedMode.phase.startsWith("托管停止")`（L841），i18n 后会全部失效。
- 🟡 基类为 `android.app.Activity`，非 `ComponentActivity`，无法用 Activity Result API / Lifecycle / OnBackPressedDispatcher。

### 2.2 导航
- 🟠 自制导航：Tab 用中文字符串作 key（L36, L356），二级页用 `PanePage` 栈 + 拦截 `startActivity`（L366–369）+ Bundle 手动序列化（PanePage.kt L79），且同时存在独立 Activity（Profile/Config/Schedule/Log/Preview），两套机制并存。
- 🟠 返回逻辑分散在 4 处 `onBackPressed`（HomeActivity L372, ConfigActivity L54, ScheduleActivity L156, PanePage L25/L44/L72），Android 13+ 预测性返回不支持。
- 🟡 页面内"返回"按钮为文本 flatButton（LogActivity L28, ProfileActivity L37），无标准 TopAppBar。

### 2.3 废弃 API（file:line）
- 🟠 `UiTheme.kt:37` `systemUiVisibility` + FLAG_LAYOUT_*（API 30 废弃）
- 🟠 `UiTheme.kt:41-42` `statusBarColor/navigationBarColor`（API 35 废弃，targetSdk 35 下 edge-to-edge 强制）
- 🟠 `UiTheme.kt:58-62` `systemWindowInset*`、`consumeSystemWindowInsets/consumeDisplayCutout`
- 🟠 `onBackPressed`：HomeActivity.kt:372-373、ConfigActivity.kt:54、ScheduleActivity.kt:156、PanePage.kt:25/44/72
- 🟠 `startActivityForResult`：HomeActivity.kt:989、PanePage.kt:19、ProfileActivity.kt:86/98/105（+ onActivityResult 请求码 201/202/203）
- 🟡 `resources.displayMetrics.scaledDensity`：PreviewActivity.kt:169（API 34 废弃，非线性字体缩放下错误）
- 🟡 `android.widget.Switch`（UiTheme.kt:21, compactSwitch）、`android.app.AlertDialog`（UiTheme.kt:106 等）、`Theme.Material.Light.NoActionBar`（styles.xml）——均为框架旧组件
- 🟡 `context.getDrawable(...)!!`：ManualTaskPanel.kt:93、ProfileResourcesView.kt:42（应 ContextCompat/ painterResource）

### 2.4 Insets / 挖孔屏 / 方向
- 🟠 手写 insets（UiTheme.kt:33–66），根布局整体 padding，含 IME；滚动内容无法绘制到系统栏下，键盘弹出时整页 padding 跳动。
- 🟠 横屏挖孔屏特殊处理 `landscapeCutoutPadding`（UiTheme.kt:48–51），只判断 left/right 是否>0 后改用固定 dp，逻辑 hacky。
- 🟠 方向策略不一致：Log/Profile/Config/Schedule 锁竖屏，Preview 锁横屏，Home 自行处理 `configChanges="orientation|screenSize"`（Manifest L27），平板/折叠屏/分屏体验差；`screenOrientation` 在 Android 16 大屏上会被忽略。

### 2.5 视觉一致性 / 硬编码
- 🟠 颜色硬编码 54 处：如 HomeActivity.kt:757 `Color.rgb(0,109,166)`、:826/:898 成功绿 `rgb(28,139,102)` 重复出现、LogOutput.kt:14 日志底 `rgb(15,23,42)`、LogFilters.kt:23 五色、ScheduleTaskRow.kt:92–96 任务分类 9 色（Tailwind 色值）。无语义色（success/warning/onSurface）。
- 🟠 字号 23 处硬编码且偏小：10f（HomeActivity.kt:570–573 表头、LogFilters.kt:22）、11f/11.5f（L288, L525, L539, L622）、12f 大量；无排版层级（Typography）。
- 🟠 尺寸 ~166 处 `dp(n)`，圆角 6/8/10/12 混用（ChoiceMenu.kt:20/27、HomeActivity.kt:382/430/563）。
- 🟡 Toast 与自绘 `UiNotice` 混用（SettingsPanel.kt:28 用 Toast，其余用 UiNotice）。
- 🟡 用 Unicode 字符代替图标："▾"（L801）、"⇅"（L175）、"✓"（ProfileActivity.kt:41）、"×"（UiNotice.kt:41），各字体渲染不一。
- 🟡 预览提示 `Color.LTGRAY`（L272）与主题色不统一。

### 2.6 深色模式 / 主题
- 🔴 完全不支持深色模式：主题继承 Light、状态栏/导航栏固定浅色+LIGHT 图标（UiTheme.kt:37–42）、卡片 `Color.WHITE` 写死（多处）。系统深色模式下仍是白屏。
- 🟡 无动态取色（Material You）；`launcher_colors.xml` 只有一个白色。

### 2.7 国际化
- 🔴 ~408 处中文字面量直接写在 Kotlin 中，无 `strings.xml`。
- 🟠 **英文→中文的运行时映射表**：HomeActivity.kt:133–146 `previewMessage()` 把 PreviewActivity 发出的英文状态（PreviewActivity.kt:127/276/295/297/335/341–343）翻译成中文，靠字符串完全匹配，任何改动都会静默失效；PreviewActivity 自身又混有中文（L40/78/81/88）。
- 🟠 Tab key、筛选 key 用中文（"全部/已启用/已禁用" L552–553），状态判断用中文（L896–899）。
- 🟡 日期格式 `SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())` 多处重复（L743, L749, L822, L849, L883, L928），时区（北京时间 vs 本地）混用需注意。
- 🟡 区服名"官服/B服/日服/国际服"在 ProfileActivity:49、ManualTaskPanel:46/111/120/126、ConfigForm:213 重复硬编码。

### 2.8 无障碍
- 🟠 触摸目标不足 48dp：BaasInput 36dp（BaasInput.kt:23）、ConfigForm 开关 42×36（ConfigForm.kt:121）、选择器 132×36（:155）、NumberInput ± 32×36（NumberInput.kt:49）、ManualTaskPanel 动作按钮高 34dp（:101）、调度 compactAction 32dp（HomeActivity.kt:539）、日志按钮 32dp（:622）、LogFilters 高 32（LogFilters.kt:22）、segments 默认 36（UiTheme.kt:80）。
- 🟠 字号 10–11sp 在默认字号下已偏小；字体放大后多为固定高度（minHeight 32/36、固定宽 dp(78)/dp(42) 表头 L571–572）会截断。
- 🟠 contentDescription 仅 18 处：chevron 图标、ic_play（ManualTaskPanel:93）、资源图标（ProfileResourcesView:42）、任务分类彩色标签、Tab 选中状态均无语义；Tab 选中只靠颜色区分，无 `selected`/stateDescription。
- 🟡 对比度：muted `rgb(101,122,139)` 在白底约 4.6:1，用在 10–11sp 小字上不足（小字建议 ≥4.5，边缘）；`pale` 背景上的 muted 更低；LTGRAY 在黑底尚可。
- 🟡 拖拽排序（ScheduleDrag）无无障碍替代操作（上移/下移 action）。
- 🟡 自绘 UiNotice 未 `announceForAccessibility`/liveRegion（需核实）。

### 2.9 状态处理 / 生命周期 / 线程
- 🟠 状态恢复靠手工 Bundle：HomeActivity.kt:190–212 保存 10+ 字段，PanePage 递归保存 Intent+Bundle，易漏；进程被杀后 `TaskRuntime` 等全局单例状态与 UI 不同步。
- 🟠 **轮询**：HomeActivity.kt:61–73/1051 每 2s 主线程刷新主页/调度/托管（含读取 store、解析 JSON）；TaskService.kt:68/87 500ms/4s 刷新通知；SocketBroker.kt:40 4s keepalive。应改为事件流 + 仅可见时收集。
- 🟠 主线程 IO/解析：`store.events()`、`store.initialize()`（HomeActivity.kt:190, L409）读配置文件/JSON 在主线程；托管历史中解码失败帧图片（L960–990 区域）在主线程。
- 🟠 监听器手动注册/注销：`TaskRuntime.remove(changed)` 于 onDestroy（HomeActivity.kt:1060, ConfigActivity.kt:59, LogActivity.kt:54），回调中再 `runOnUiThread`（ConfigActivity:19, ManualTaskPanel:30）。若异常路径漏注销即泄漏 Activity；`UiNotice` 用 `isFinishing/isDestroyed` 兜底（UiNotice.kt:17）说明已出现过此类问题。
- 🟠 PreviewActivity 的 `worker` 单线程 Executor（L46）中大量 `try{}catch(Throwable)`，回调后 `runOnUiThread` 并靠 `closed` 标志防护（L328–336）——Compose 重写应用 lifecycleScope/协程取消。
- 🟡 `Handler(...).postDelayed({ Process.killProcess(...) }, 100)`（OcrService.kt:23）——属服务逻辑，UI 侧注意不要依赖。
- 🟡 吞异常：74 处 catch，部分空 catch（PreviewActivity.kt:39）；`safely{}`（HomeActivity.kt:1041）把异常统一弹窗，信息为原始 message（可能是英文/技术栈）。

### 2.10 性能
- 🟠 整页重建：`renderPage()` 每次切页/操作 `removeAllViews` 后全部重新 new View（HomeActivity.kt:401–440, :778, :872；ProfileActivity.kt:34；ScheduleActivity.kt:76；ConfigForm.kt:96）。用"签名字符串"避免重建（L413, L693–701, L773）是手工 diff。
- 🟠 列表全部是 LinearLayout-in-ScrollView，无回收：调度任务列表、托管历史、配置列表、日志。
- 🟠 日志：`LogOutput` 把全部日志渲染成一个大 Spannable 塞进单个 TextView（LogOutput.kt:30–31），每 200ms 重新 render 全量；日志增长后布局/测量开销线性增加。
- 🟡 过度绘制：根 pale 背景 + 卡片白底 + 按钮 GradientDrawable + Ripple 多层；每个按钮都 new GradientDrawable/StateListDrawable（UiTheme.kt:85–100），无复用。
- 🟡 ScheduleDrag 拖动结束时 `list.removeAllViews(); before.forEach{ addView }`（ScheduleDrag.kt:68）。

### 2.11 UX / 交互
- 🟠 状态文案冗长且两套（中英），"任务失败 · 请查看日志"需要用户自行切 Tab 找日志，缺直达。
- 🟠 运行中禁止操作只在点击后提示"请先停止任务"（ProfileActivity.kt:46/85/90/97/104、ConfigForm.kt:66），按钮未预先置灰。
- 🟠 Shizuku 未授权/未启动时的引导分散在预览占位文字（L150）和 ManualTaskPanel 提示（:130"请返回主页点击…"），缺统一的首次引导/权限检查页。
- 🟠 精确闹钟权限、电池优化跳转仅在点击"开启托管"时触发（L472），无事前说明。
- 🟡 调度页"立即刷新""一键推迟"无二次确认/撤销，影响全部任务。
- 🟡 拖拽排序仅在"全部+按执行顺序"可用（L561），但无提示告诉用户为何不能拖。
- 🟡 "已保存"反馈方式不一（summary 文本追加"· 已保存" L604、Toast、UiNotice）。
- 🟡 时间显示：调度用"MM-dd HH:mm\nX 分钟后"两行（L744），托管用秒级，历史用 yyyy-MM-dd，格式不统一。

### 2.12 错误 / 空 / 加载状态
- 🟠 加载态缺失：Companion 连接仅文字"连接中…"，无进度指示；配置导入导出、反和谐等长操作无 loading。
- 🟡 空态有但简陋："暂无启用任务"（L781）、"此筛选下没有任务"（L560）、"暂无记录"（L877）、"无活动数据"（ManualTaskPanel:48）——仅一行文字。
- 🟡 错误统一 AlertDialog 显示 `error.message`（ConfigForm.kt:271、ProfileActivity.kt:130、ScheduleActivity.kt:164），无重试/复制详情。

---

## 3. Compose 重写建议

### 3.1 架构
- 单 Activity（`ComponentActivity`）+ Navigation Compose：`Home(Tab: 主页/托管/调度/日志/设置)`、`Profiles`、`ProfileConfig/{id}`、`ScheduleEdit/{profileId}/{task}`、`LogFullscreen`、`Preview`。路由用类型安全对象，不用中文字符串。
- 每屏一个 ViewModel + 不可变 `UiState` data class；把 `TaskRuntime`/`ManagedMode`/`ProfileStore` 包成 Repository，对外暴露 `StateFlow`（替代回调列表+2s 轮询），UI 用 `collectAsStateWithLifecycle()`。
- 把 JSON 快照解析为 Kotlin 数据类（kotlinx.serialization），状态用 enum/sealed（TaskState、ManagedPhase、HistoryResult），**禁止用显示文案判断状态**。
- 时间格式化、调度排序过滤（TaskOrder）、失败原因提取移到 domain 层并加单测。
- 依赖：可先手动 DI 或 Hilt；保持 `:companion-runtime` 模块不动。

### 3.2 主题与设计令牌
- Material 3 `MaterialTheme`：从现有调色生成 light/dark ColorScheme（seed #008FD3），Android 12+ 可选动态取色；扩展 `ExtendedColors`（success、warning、日志级别 5 色、任务分类 9 色）。
- Typography：正文最小 14sp、辅助 12sp，取消 10/11sp；Shapes 统一圆角（如 8/12/16）。
- `enableEdgeToEdge()` + `Scaffold`/`WindowInsets.safeDrawing`，删除 UiTheme.setContentView 的 insets 逻辑；横屏挖孔用 `WindowInsets.displayCutout`。
- 全部文案进 `strings.xml`（含 plurals），预览状态改为枚举→stringResource，删除 `previewMessage` 映射表。
- 图标改用 Material Symbols（替代 ▾ ⇅ ✓ ×）。

### 3.3 组件映射
- Tab → `NavigationBar`（竖屏）/`NavigationRail`（横屏/大屏）；ChoiceMenu/ListPopupWindow → `ExposedDropdownMenuBox`/`DropdownMenu`；compactSwitch → `Switch`；segments → `SingleChoiceSegmentedButtonRow`；UiNotice/Toast → `SnackbarHost`；AlertDialog → `AlertDialog`/`ModalBottomSheet`（列表编辑器）；NumberInput → 自定义步进组件（48dp 触摸目标）。
- 列表 → `LazyColumn`（key=funcName），拖拽排序用 `sh.calvin.reorderable` 或自实现，并提供无障碍"上移/下移" customActions。
- 日志 → `LazyColumn` 按行渲染（每行一条 entry，`AnnotatedString` 高亮），保留"自动滚动"=是否锁定底部；过滤在 ViewModel 后台线程做。
- ConfigForm（JSON 驱动表单）→ 保持 schema 驱动，写成 `@Composable FieldRenderer(field)` 的 when 分发。
- SAF 导入导出 → `rememberLauncherForActivityResult(CreateDocument/OpenDocument)`。
- 返回 → `BackHandler`（未保存确认对话框）。

### 3.4 互操作陷阱（重点）
- **虚拟屏预览 SurfaceView**：Compose 中用 `AndroidView(factory = { SurfaceView(it) })` 包装，Surface 生命周期（surfaceCreated/Destroyed → `broker.setPreviewSurface`）必须与 `DisposableEffect`/Lifecycle 正确绑定；注意 `setZOrderOnTop(false)`、SurfaceView 与 Compose 叠层（遮罩/按钮放在上层时需 `setZOrderMediaOverlay` 或改用 TextureView/`AndroidExternalSurface`，后者需评估延迟）。触摸坐标映射（PreviewActivity.kt:393）要基于实际 Surface 尺寸与 letterbox，避免被 Compose padding 影响。
- **首页内嵌预览 + 全屏预览**：目前 Home 继承 PreviewActivity 共享连接；重写时把 Shizuku/Companion 连接提升为进程级 `PreviewConnection` 单例/Service，由 ViewModel 引用计数，避免切屏重连（15s 超时）。
- **Shizuku**：`OnBinderReceivedListener/DeadListener`、`requestPermission` 回调（PreviewActivity.kt:75/85/86）需在 Repository 层转成 Flow；权限结果 requestCode 102 仍是回调式，注意在 Activity 重建后重新注册。
- **Chaquopy**：Python 初始化/调用较重，严禁在 Composition 或主线程中触发；`TaskRuntime` 的 Python 回调线程 → 转 `MutableStateFlow` 时用 `update{}`，UI 不要直接持有 PyObject。Compose 预览（@Preview）中无法加载 Chaquopy，状态层需可用 Fake 实现。
- **PreviewIdleActivity** 有独立 `taskAffinity="moe.baas.aio.virtual"`，在虚拟屏上启动，**必须保留为独立 Activity**，不要并入单 Activity 导航。
- **TaskService 前台服务/通知**：通知刷新逻辑与 UI 解耦，重写 UI 时不要改动其 Intent action（START/STOP）。
- **方向**：现有 PreviewActivity 锁横屏；单 Activity 后改为按窗口尺寸自适应布局（WindowSizeClass），预览页可用 `requestedOrientation` 动态设置，但注意 Android 16 大屏忽略方向锁。
- **debug 源集**：debug 有同名 `MainActivity` 等入口与 Manifest 合并；重写时保留 `BuildConfig.DEBUG` 入口（HomeActivity.kt:664 通过类名反射启动），或改为 debug 专属路由。
- **构建**：引入 Compose 需加 `org.jetbrains.kotlin.plugin.compose`（Kotlin 2.1.20 对应版本）与 Compose BOM，`buildFeatures { compose = true }`；当前仅依赖 androidx.core，APK 体积会增加（R8 开启可控）。

### 3.5 迁移顺序建议
1. 先抽 Repository/StateFlow 与数据类（不改 UI），加单测；
2. 建 Theme + strings；
3. 逐屏迁移：Log → Profile → Schedule 编辑 → 设置/ConfigForm → 调度 Tab → 托管 → 主页 → 预览（最后，风险最高）；
4. 每步可在旧 Activity 里用 `ComposeView` 嵌入，逐步替换。
