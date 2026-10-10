# 原版 UI 规格（基准：2c088c3，View 实现）

> 本文件逐行阅读原版代码整理，是 compose-ui-v2 的唯一验收标准。尺寸单位 dp / sp；颜色为 sRGB。
> 引用格式 `文件:行`。

## 0. 全局视觉基础（UiTheme.kt）

| Token | 值 |
|---|---|
| blue | rgb(0,143,211) `#008FD3` |
| navy（正文） | rgb(32,57,78) `#20394E` |
| muted（次要） | rgb(101,122,139) `#657A8B` |
| pale（页面底色、状态栏/导航栏色） | rgb(237,247,253) `#EDF7FD` |
| border | rgb(218,232,241) `#DAE8F1` |
| danger | rgb(220,72,82) `#DC4852` |
| success（运行中、成功） | rgb(28,139,102) |
| deep blue（已到期） | rgb(0,109,166) |
| 日志底色 | rgb(15,23,42)；日志字 rgb(203,213,225) |

主题：`Theme.Material.Light.NoActionBar`，colorAccent #008FD3（影响平台对话框按钮、Switch 涟漪、时间/日期选择器）。

窗口（`UiTheme.setContentView` :34-67）：内容延伸到系统栏后；状态栏、导航栏颜色 = pale，浅色图标；根布局 padding = 原 padding + (systemBars ∪ displayCutout ∪ ime) 安全区。`landscapeCutoutPadding`：横屏且该侧有刘海时，该侧基础 padding 改为指定值（Home 为 0）。

### 0.1 基础组件
- **text()** :80 — TextView，默认 14sp、navy、sans-serif Regular，padding 左右 4 / 上下 6，includeFontPadding=true。
- **button(label, primary)** :84 — 平台 Material Button：文字 13sp **sans-serif-medium**、不大写、单行省略；minHeight 48、minWidth 88（来自样式）；padding 8/4；背景圆角 10：primary = blue 填充 + 白字；否则白底 + 1dp border 描边 + blue 字；禁用文字色 muted；涟漪 #22008FD3；**保留 Material 按钮阴影**（启用时 elevation 2dp，按下 +4dp translationZ，禁用无阴影）。
- **flatButton** :95 — button 的变体：minHeight 36、12sp、**无阴影**、padding 10/4。
- **fieldBackground(focused, invalid)** :100 — 白底圆角 10；描边 1dp border；focus=2dp blue；invalid=2dp danger。
- **BaasInput** — EditText 13sp，文字 navy（禁用 muted），hint muted，光标 2×20dp blue，选中高亮 #33008FD3，minHeight 36，padding 12/8，背景 fieldBackground（focus 时 2dp blue）。
- **dialog()** :106 — 平台 AlertDialog，窗口背景白色圆角 16、四周 inset 8。
- **inputPanel** :114 — 对话框内容容器 padding 16/12/16/8，输入框 minHeight 44。
- **compactSwitch** :119 — 轨道 32×18 圆角 9，选中 blue / 未选中 border，切换淡入淡出 180ms；拇指 16dp 白色圆；无文字。
- **chevron** :136 — `ic_chevron`，居中，不参与无障碍；展开时旋转 90°（140ms）。
- **quietRipple(color, radius=6)** :140 — 背景色 + 涟漪 #10657A8B。
- **setToggleSelected** :142 — 选中：pale 底 + 1dp blue 描边 + blue 字；未选中：白底 + 1dp border + muted 字；圆角 8。
- **segments** :149 — 无 colors：白底圆角 8、1dp border 外框并裁切；选中项 blue 填充白字，未选中透明 muted 字。有 colors（日志级别）：每项间隔 2dp；文字为该色，选中加粗；填充 = 色 α0x30（选中）/α0x10，描边 1dp = 色 αB0（选中）/α40，圆角 6。每项 minHeight = height 参数（默认 36），等宽。
- **card()** :190 — 白底圆角 12，padding 12/8/12/8。
- **item()**：UiTheme 版 margin 左右 3、上下 4；**HomeActivity 内的 item 为左右 3、上下 2**（HomeActivity.kt:182）。
- **UiNotice** — 底部浮层：左右 margin 12，距底 72（HomeActivity）/16（其他）；白底圆角 12、1dp border、elevation 6；padding 10/6/4/6；左侧 24dp 宽 “i”（blue）或 “!”（danger）17sp 粗体；消息 13sp 最多 4 行；可选 flatButton 动作；右侧 “×” 20sp muted，40×44；入场 alpha 0→1 + 上移 12dp（180ms），退场 alpha→0 + 下移 8dp（140ms）；显示 4.5s（有动作 8s，按无障碍推荐时长调整）；新提示替换旧提示。
- **ChoiceMenu** — 锚定弹出：宽 = 锚点宽（默认 132），高 = min(n×40+8, 288)，白底圆角 8 + 1dp border，elevation 6，内边距 4，位于锚点下方 4dp、右对齐；每行 40dp，12sp，选中项 pale 底 + blue 粗体 + 右侧 24dp “✓”(13sp blue)；打开时指示箭头转 90°，关闭转回。
- **settingsCard(title)**（Home:717）— card，padding 12/0/8/0，一行高 44：标题 13sp 粗体（无 padding）+ 右侧 24dp 宽 “›” 22sp muted；整卡涟漪 #22008FD3。

## 1. Activity 与导航结构（务必保持）
| Activity | 方向 | 说明 |
|---|---|---|
| HomeActivity | 自由旋转，`configChanges=orientation|screenSize`，singleTask，Launcher | 继承 PreviewActivity，嵌入预览 |
| ProfileActivity / ConfigActivity / ScheduleActivity | 竖屏 | `PaneActivity` 宿主同一个 `PanePage` |
| LogActivity | 竖屏 | 全屏日志 |
| PreviewActivity | 横屏，exported | 全屏预览；extras `gamePackage`、`verify` |
| PreviewIdleActivity | taskAffinity `moe.baas.aio.virtual` | 不改 |

- **竖屏**：Home 内打开 Profile/Config/Schedule → 启动独立 Activity（系统默认切换动画）。
- **横屏**：`HomeActivity.startActivity` 拦截这三类 Intent（PaneHost.handles），在右侧内容区压栈显示页面（无动画替换），返回键先弹出页面；栈空时 `renderPage(keepScroll=true)`。
- Profile 页的 “设置” → ConfigActivity（profileId）；Schedule 页 “执行参数” → ConfigActivity（profileId, group）。
- 横屏 pane 中的 SAF 结果码 201..203 转发给栈顶页面。
- 从任意页面启动手动任务后：`startActivity(HomeActivity, CLEAR_TOP)`，非 Home 宿主 finish。

## 2. HomeActivity

### 2.1 根布局（buildPreviewLayout :214）
- 根：pale，padding 12/4/12/8（+安全区）；竖屏纵向排列，横屏横向。横屏时加 FLAG_FULLSCREEN（隐藏状态栏），刘海侧 padding 0。
- **previewPanel**：竖屏宽度撑满、高度自适应；横屏 weight 1。
- **contentHost**：竖屏 weight 1；横屏 weight 1 且左 margin 8。

### 2.2 头部 header（:246-268，item 3/2）
- 竖屏：`BAAS` 16sp 粗体 blue，letterSpacing .02，高 32，右 padding 4 ｜ 配置按钮（flatButton，高 32，weight 1，右 margin 6，文字 “名称 ▾”，左对齐，单行省略，cd “选择配置”）｜ “管理” flatButton 40×32（padding 0）。
- 横屏：隐藏 BAAS；配置按钮宽 144；“管理”；弹性空白；然后 takeover 按钮与 “关闭显示” 按钮（高 32，左右 padding 8）。
- 配置按钮 → ListPopupWindow：宽 = 按钮宽，白底圆角 8 + 1dp border，行 12sp、minHeight 40、padding 10/8、单行省略；当前项 blue 字 + pale 底，其余 navy；选择后 `store.select` 并重绘页面。
- “管理” → ProfileActivity（横屏为 pane）。

### 2.3 预览区（:269-301）
- FrameLayout 黑底，margin 3/4/3/0；竖屏高度 = (宽−6)×9/16，横屏 weight 1 填满。
- SurfaceView 居中按 16:9 等比 letterbox（layoutSurface）；不可用时 INVISIBLE。
- 可用且游戏运行中时背景 pale，否则黑。
- previewHint：居中 14sp LTGRAY；READY 时 “游戏未启动”，否则 “{state.label}\n{state.action}” 且可点击 → requestShizukuAccess；预览可用时隐藏。
- 横屏：status 覆盖在预览顶部（白字，#99000000 底，单行省略，padding 8/4/4/4）。
- 竖屏 controlRow（item）：status（11sp muted，padding 4，weight 1，可点击 → requestShizukuAccess）｜ takeover（Material 默认按钮样式，12sp blue 字，高 44，仅任务运行时可见）｜ 关闭显示（同 takeover 样式，12sp，高 44，总是可见）。
- 按钮行（item）：展开预览 / 启动游戏 / 关闭游戏（button，等宽，禁用 alpha .45）。
  - 展开预览：enabled = previewAvailable → PreviewActivity。
  - 启动游戏：enabled = !gameRunning && !task.active && !managed && profile.runnable → launchPreviewGame(selected.gamePackage)。
  - 关闭游戏：enabled = !task.active && previewAvailable。
- 状态文字映射（previewMessage :133）：连接中… / 设备已断开 / 手动接管 / 继续任务 / 正在恢复… / 等待暂停… / 等待任务画面… / 游戏未启动 / 手动操作中 / 等待当前操作结束… / 自动操作中 / 可手动操作；其余原样（如 “Companion 连接超时，点击重试”、“操作失败，请查看日志”、Shizuku 状态 label）。

### 2.4 内容区
- body 在 ScrollView（fillViewport）内，padding 上下 4；日志页不在 ScrollView 内而是直接撑满。
- 底部 Tab 行（item）：主页 / 托管 / 调度 / 日志 / 设置，button 等宽；当前 Tab blue 填充白字，其余白底 blue 字（**注意：Tab 背景重设后无描边**，圆角 10，仍有按钮阴影）。
- 滚动位置：切换 Tab 时只记住 “调度/日志” 的位置；其他页回到顶部；onResume 触发重绘并回到顶部；横屏 pane 返回时保持位置。切换到不同配置时调度页回顶部。
- 每 2 秒轮询（仅可见时）：刷新预览状态；主页任务列表；调度页时间；托管状态与资源。

### 2.5 主页（:443）
1. 控制卡 card：状态 16sp 粗体（failed=danger，running=success，其他 navy）；详情 12sp muted（仅任务运行时显示：“运行配置：X · 下次 MM-dd HH:mm”）；行：开始（primary）/ 停止任务。
   - 状态文案：starting 启动中 / running 执行中（phase idle 时 “等待下次任务”）/ pausing 等待暂停 / paused 已暂停 · 可手动接管 / stopping 正在停止 / failed 任务失败 · 请查看日志 / completed 本轮已完成 / 其他 待机；有 currentTask 时追加 “ · 任务名”。
   - 开始：enabled = !active && !managed && runnable && !probe；→ ensureShizukuReady → TaskService START task=core_profile。
   - 停止：enabled = active || managed；managed ? ManagedMode.stop() : TaskService STOP。
2. 行：“任务 · n / m 已启用”（12sp muted, weight 1）｜ 排序切换 flatButton “⇅ 下次执行”/“⇅ 执行顺序”（默认下次执行，存 Activity preferences `homeSortByTime`）。
3. 列表 card：无启用任务时 “暂无启用任务”（14sp muted）+ “前往调度” 按钮；否则每项（1dp border 分隔线）：名称 14sp；“下次执行：MM-dd HH:mm|待执行” 11sp，颜色 deep blue（已到期，粗体）/ blue（1 小时内）/ muted。

### 2.6 托管（:463）
1. 状态卡：状态 16sp 粗体；详情 12sp muted；行：开启托管（primary）/ 停止托管。
   - 状态：托管且任务运行时 paused 托管已暂停 / pausing 等待暂停 / stopping 正在停止托管 / 其他 托管执行中；否则 phase 以“托管停止”开头 → 托管已停止，“等待手动重试” → 等待任务，否则 phase 原文。
   - 详情：运行配置：X ／ 下次执行：[名称 · ]MM-dd HH:mm:ss ／ 已选 n 个配置。
   - 开启：enabled = !managed && !active && !probe；未授权精确闹钟 → 系统设置 ACTION_REQUEST_SCHEDULE_EXACT_ALARM；否则 ensureShizukuReady → ManagedMode.start()。
2. “托管配置” 12sp muted。
3. 每个配置一张 card：左列 名称 14sp + ProfileResourcesView（区服 11sp muted；选中时资源行：体力/信用点/青辉石图标 24×20 + 11sp muted 数值，间距 6，图标与字 2）；右侧 compactSwitch 44×44（enabled = runnable），切换即 ManagedMode.select。
4. 失败历史头：minHeight 44，pale 底 quietRipple 圆角 10；“失败历史” 12sp muted ｜ “n 条” 11sp muted（weight 1）｜ chevron 24×44（展开 90°）。
5. 展开时 card：有记录时左上 “清空” flatButton 48×32（11sp，白底 quietRipple）；记录倒序，每条前 1dp 分隔线；记录：padding 4/8，配置名 12sp 粗体 单行 ｜ 时间 10sp muted；结果 11sp（重试已取消 muted / 含成功 success / 含挂起或停止 danger / 其他 blue）+（该配置最新且可恢复时）“立即尝试” flatButton 84×34（enabled = managed && !active）；原因（错误最后一行，≤240 字）11sp muted 最多 2 行。无记录 “暂无记录”。
6. 点击记录 → 对话框 “错误记录”：可选中文本 13sp navy（padding 20/12）+ 最后一帧图片（点击放大对话框 “最后一帧”）；按钮：关闭 / 复制（剪贴板 “BAAS 错误记录”）/ 保存图片（SAF image/png，请求码 301，成功 “图片已保存”）。

### 2.7 调度（:533）
1. 摘要 12sp muted：“{配置} · n / m 个任务已启用”（排序或开关后追加 “ · 已保存”）。
2. 行：全部启用|全部禁用 / 立即刷新 / 一键推迟（flatButton 高 32，11.5sp，等宽）。
3. 行：segments 全部/已启用/已禁用（weight 1）｜ 排序切换（默认执行顺序）。
4. 空：“此筛选下没有任务” 14sp muted。
5. 表格 panel：白底圆角 10 + 1dp border 裁切；表头 pale，padding 6/5：[拖动列 24]、“#” 24、“任务 / 间隔” weight、“下次执行” 78、“启用” 42、“配置” 30（10sp muted）。
6. 行（ScheduleTaskRow）：白底，minHeight 66，padding 6/3；可拖动时 “⠿” 20sp muted 24×48（长按开始拖动）；序号 11sp muted 24；详情：名称 13sp medium 最多 2 行 + 分类标签（9sp，色底 α0x18 圆角 4，padding 4/2，右 margin 5）+ 间隔 10sp muted；下次执行 10.5sp 78 宽 两行 “MM-dd HH:mm\nN 分钟后”（颜色同主页规则）；compactSwitch 42×44；“⚙︎” 20sp muted 30×44 → ScheduleActivity；底部 1dp border 分隔线；禁用项详情和序号 alpha .5。
7. 拖动：仅 全部 + 执行顺序 排序时；系统拖放，拖动卡 alpha .18，其它行 160ms Decelerate 位移动画；只能在相同启用状态之间移动；靠近边缘 48dp 自动滚动；放下才保存（`store.reorder`），取消恢复；保存后序号更新、摘要加 “已保存”。拖动中暂停轮询重绘。
8. 开关：`store.setEnabled`；失败时回滚并提示。

### 2.8 日志（:613）
- 行：搜索框（fieldBackground，高 32：放大镜 28×32；输入 11sp，hint “搜索日志”；“×” 18sp muted 24×32 有内容才显示）｜ “自动滚动” 切换（flatButton 高 32 11sp，toggle 样式，右 margin 6）｜ 全屏按钮 32×32（ic_fullscreen，白底圆角 8 描边，padding 5）→ LogActivity(query, level, follow)。
- 行：级别 segments（10sp，高 32，彩色）｜ 计数 “x/y 条” 10sp muted 右对齐 宽 80。
- 输出：ScrollView 圆角 12 深色底，padding 10，等宽 12sp，行距 +3dp ×1.15，可选择；每条 “[LEVEL] message”，前缀着色；空 “暂无日志”/“没有匹配的日志”；渲染节流 200ms；跟随时滚到底。实时日志为空时读 task-report.json。

### 2.9 设置（:641）
1. SettingsPanel（按配置缓存草稿）。
2. settingsCard “配置文件管理” → ProfileActivity。
3. 卡片：行 “应用与权限” 13sp 粗体 ｜ 状态 label 11sp（READY blue 否则 danger）；flatButton state.action → requestShizukuAccess；flatButton “应用与电池设置” → 应用详情设置。
4. DEBUG：settingsCard “开发诊断” → MainActivity。
5. settingsCard “关于” → AboutDialog。

## 3. SettingsPanel / ConfigForm / ManualTaskPanel
- 顶部行：撤销修改（flatButton，高 32，weight 1，右 margin 6，enabled=dirty）｜ 保存（flatButton 但 blue 圆角 8 填充白字，高 32，enabled = !active && dirty && valid）。保存成功 Toast “已保存”；失败对话框。
- 分组（catalog 顺序）：每组一张 card（padding 12/0/8/0，tag=组号）：头部高 44（标题 13sp 粗体 + chevron 24），涟漪 quietRipple 白；内容 padding 0/4/4/8；首次展开时才创建。“关卡推图与剧情” 后插入 “活动图”（id=组数），最后 “其他功能”（id=组数+1）。展开状态随实例保存。
- 字段行：minHeight 44，padding 4，任务运行时 alpha .55；行间 1dp #80DAE8F1 分隔线；标签 12.5sp 最多 2 行，右 padding 8。
  - 布尔：compactSwitch 42×36，整行可点。
  - 数值（无 options）：NumberInput 132 宽。
  - 其他：132×36 fieldBackground 值框（13sp 右对齐 最多 2 行 + chevron α.65 20 宽），整行可点：options → ChoiceMenu；shop → 多选对话框；slots/teams → 编辑对话框；否则文本对话框。
- 条件显示：见 ConfigForm.shown（咖啡厅 2 号、邀请条件、编队方式、制造阶段）。
- NumberInput：fieldBackground（focus/invalid）；“−”/“+” 20sp blue 32×36，禁用 alpha .3；中间输入居中；invalid 时边框 danger；`allowBlank` hint “自动”。
- ManualTaskPanel：推图（explore_normal_task / explore_hard_task 两列）插在 manual_boss 字段后；剧情（main_story 独占一行，其余两列）插在 main_story_regions 后；活动图（活动名、两个开关 “使用通用活动入口”/“从活动菜单开始”、故事数量 NumberInput、推故事/推任务/推挑战、查看活动关卡）；其他功能（de_clothes）。任务按钮：flatButton 高 34，左侧 ic_play 16dp 着色同文字，间距 5，右 margin 4。运行中显示 “停止任务”。启动校验文案见代码；成功后回到 Home（CLEAR_TOP）。

## 4. ProfilePage（ProfileActivity）
- page（pale，padding 12），在 ScrollView 内；onResume 重绘。
- 行：“配置文件” 20sp 粗体（weight 1）｜ “返回” button（weight 1）。
- 每个配置一个 button：“✓ 名称 · 区服”，当前配置为 primary；点击选中。
- “新建配置” button（运行中禁用）→ 对话框（名称输入 + 区服选择 ChoiceMenu 132×36）。
- card：当前配置名 16sp 粗体；行 设置/重命名/复制；行 导出/删除；导入 ZIP；导入 config / event JSON；运行中除“设置”外 alpha .45 禁用。
- 删除确认 “删除配置 X？”；错误统一对话框。

## 5. ConfigPage（ConfigActivity）
- page；行 “配置设置” 20sp 粗体 + “返回” flatButton；配置名 12sp muted；ScrollView 内 SettingsPanel；有 group extra 时展开并滚动到该组。
- 返回/后退：dirty 时 “放弃未保存的修改？”（继续编辑/放弃）。onResume reloadIfClean。

## 6. SchedulePage（ScheduleActivity）
- page；行 任务名 20sp 粗体（weight 1）+ “返回” button（weight 1）；配置名 12sp（navy）；ScrollView 字段；底部 取消 / 保存（primary）。
- 字段：执行顺序（NumberInput 小数）；执行间隔（秒）+ “常用”（对话框：每半小时/每小时/每天/一次性）；下次执行（北京时间）：日期按钮（weight 2）+ 立即（weight 1）；每日重置（北京时间）列表（时间按钮 + 删除）+ 添加重置时间；禁用时段（本地时间）同理；前置任务/后置任务 · n 项（多选对话框）；执行参数 → ConfigActivity(group)。
- 平台 TimePickerDialog / DatePickerDialog（24 小时）；返回时有修改则确认。

## 7. LogActivity
- page；行 “运行日志” 20sp（weight 1）｜ 自动滚动 toggle ｜ “返回” flatButton；搜索框；级别行 + 计数；输出（padding 8）。

## 8. PreviewActivity（全屏）
- 黑底；SurfaceView 居中 letterbox；顶部行：status（白字 #99000000 底，左 padding 12，默认 14sp，英文原文）｜ takeover（平台默认 Material 按钮）。FLAG_FULLSCREEN；手动预览时常亮。

## 9. AboutDialog
- 平台对话框 + ScrollView：card（居中：启动图标 52dp、“BAAS AIO” 22sp 粗体 blue、版本 12sp muted）；card “相关项目” 13sp 粗体 + 4 个项目行（名称 14sp blue、描述 12sp muted、chevron 24×48，点击打开链接）；按钮 “关闭”。
