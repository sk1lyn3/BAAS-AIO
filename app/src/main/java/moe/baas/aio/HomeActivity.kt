package moe.baas.aio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.imageResource
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color as AColor
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Application shell; device IO and the upstream task engine remain shared. */
class HomeActivity : PreviewActivity() {
    override val fullscreenPreview = false
    override val createPreviewIfMissing = false
    override val landscapeCutoutPadding = 0
    private val store by lazy { ProfileStore(this) }
    private var selectedProfile by mutableStateOf<ProfileStore.Profile?>(null)
    private var profiles by mutableStateOf(emptyList<ProfileStore.Profile>())
    private val settingsPanels = mutableMapOf<String, SettingsPanel>()
    private var restoredSettings: Bundle? = null
    private var exportingFailureFrame: String? = null
    internal var page by mutableStateOf("主页")
    private var renderedPage = ""
    /** Bumped by renderPage(); page content keyed on it is rebuilt like the old removeAllViews(). */
    private var renders by mutableIntStateOf(0)
    private var pendingScroll = 0
    private var scheduleKeyValue = ""
    private var scheduleRows by mutableStateOf(emptyList<ScheduleRowModel>())
    private var scheduleTotal by mutableIntStateOf(0)
    private var scheduleEnabled by mutableIntStateOf(0)
    private var scheduleSaved by mutableStateOf(false)
    private var scheduleDragging = false
    private var scheduleTimes by mutableIntStateOf(0)
    private val pagePositions = mutableMapOf<String, Int>()
    /** v3: every tab keeps its own scroll state (tabs animate side by side, so they cannot share one). */
    private val scrolls = androidx.compose.runtime.mutableStateMapOf<String, ScrollState>()
    private fun scrollOf(name: String) = scrolls.getOrPut(name) { ScrollState(0) }
    private val pageScroll get() = scrollOf(page)
    private var viewport: LayoutCoordinates? = null
    private lateinit var secondaryPages: PaneHost
    private var restoredSecondaryPages: Bundle? = null
    private var profileNames = emptyMap<String, String>()
    private var taskNames = emptyMap<String, String>()
    private var taskFilter by mutableStateOf("全部")
    private var sortByTime by mutableStateOf(false)
    private var homeSortByTime by mutableStateOf(true)
    private var homeTasks by mutableStateOf(emptyList<JSONObject>())
    private var homeTotal by mutableIntStateOf(0)
    private var homeTaskSignature = ""
    private val scheduleHandler = Handler(Looper.getMainLooper())
    private var visible = false
    private val pollSchedule = object : Runnable {
        override fun run() {
            if (!visible) return
            refreshPreviewState()
            if (page == "主页") refreshHomeTasks()
            if (page == "调度") refreshSchedulePage()
            LiveTask.tick++ // managed status and resource rows re-read on each poll
            scheduleHandler.postDelayed(this, 2000)
        }
    }
    private var logQuery by mutableStateOf("")
    private var logLevel by mutableIntStateOf(0)
    private var followLogs by mutableStateOf(true)
    private val logModel = LogModel()
    private var managedHistoryExpanded by mutableStateOf(false)
    private var managedRevision by mutableIntStateOf(0)
    private var previewAvailable by mutableStateOf(false)
    private var previewGameRunning by mutableStateOf(false)
    private var shizuku by mutableStateOf(ShizukuAccess.State.MISSING)
    private var choosingProfile by mutableStateOf(false)
    private var lastTaskNotice = TaskRuntime.snapshot
    private val changed: (String) -> Unit = { snapshot ->
        if (!isDestroyed) {
            settingsPanels[selectedProfile?.id]?.takeIf { page == "设置" }?.refreshEnabled()
            if (snapshot != lastTaskNotice) {
                lastTaskNotice = snapshot
                showUtilityResult(snapshot)
            }
        }
    }
    private fun showUtilityResult(snapshot: String) {
        val state = JSONObject(snapshot)
        val result = state.optJSONObject("progress") ?: return
        if (result.optString("manualTask") != "de_clothes") return
        when (state.optString("state")) {
            "completed" -> UiNotice.show(this, "反和谐配置已写入，请重启游戏下载资源")
            "stopped" -> UiNotice.show(this, "反和谐操作已停止")
            "failed" -> {
                val logs = result.optJSONArray("log")
                val reason = result.optString("error").ifBlank {
                    if (logs == null) "请查看日志" else (logs.length() - 1 downTo 0).asSequence()
                        .map { logs.getJSONObject(it) }.firstOrNull { it.optInt("level") >= 3 }
                        ?.optString("message") ?: "请查看日志"
                }.trim().lineSequence().last().substringAfter("De-clothes error ")
                UiNotice.show(this, "反和谐失败：$reason", error = true)
            }
        }
    }
    override fun previewMessage(message: String) = when (message) {
        "Connecting Companion…" -> "连接中…"
        "Companion disconnected" -> "设备已断开"
        "Take control" -> "手动接管"
        "Resume task" -> "继续任务"
        "Resuming…" -> "正在恢复…"
        "Pausing…" -> "等待暂停…"
        "Waiting for task display…" -> "等待任务画面…"
        "Preview idle · Start a game or task" -> "游戏未启动"
        "Manual control · Resume when finished" -> "手动操作中"
        "Waiting for the current operation…" -> "等待当前操作结束…"
        "Task running · Take control to interact" -> "自动操作中"
        "Native preview · drag / pinch · Back to close" -> "可手动操作"
        else -> message
    }
    override fun onShizukuState(state: ShizukuAccess.State) {
        shizuku = state
        if (page == "设置" && ::secondaryPages.isInitialized) renderPage()
    }
    override fun onPreviewGameState(running: Boolean) { previewGameRunning = running }
    override fun onPreviewState(available: Boolean) {
        previewAvailable = available
        if (!available) previewGameRunning = false
        surfaceHost.getChildAt(0)?.visibility = if (available) View.VISIBLE else View.INVISIBLE
    }

    override fun onCreate(state: Bundle?) {
        ManagedMode.attach(this)
        LiveTask.start()
        store.initialize(); page = state?.getString("page") ?: "主页"
        restoredSettings = state?.getBundle("settingsPanels")
        exportingFailureFrame = state?.getString("exportingFailureFrame")
        restoredSecondaryPages = state?.getBundle("secondaryPages")
        taskFilter = state?.getString("taskFilter") ?: "全部"; sortByTime = state?.getBoolean("sortByTime") ?: false
        homeSortByTime = state?.getBoolean("homeSortByTime")
            ?: getPreferences(MODE_PRIVATE).getBoolean("homeSortByTime", true)
        logQuery = state?.getString("logQuery") ?: ""; logLevel = state?.getInt("logLevel") ?: 0
        followLogs = state?.getBoolean("followLogs") ?: true
        managedHistoryExpanded = false
        super.onCreate(state)
        TaskRuntime.observe(changed)
    }
    override fun onSaveInstanceState(state: Bundle) {
        state.putString("page", page); state.putString("taskFilter", taskFilter); state.putBoolean("sortByTime", sortByTime)
        state.putBoolean("homeSortByTime", homeSortByTime)
        state.putString("logQuery", logQuery); state.putInt("logLevel", logLevel); state.putBoolean("followLogs", followLogs)
        state.putString("exportingFailureFrame", exportingFailureFrame)
        if (::secondaryPages.isInitialized) state.putBundle("secondaryPages", secondaryPages.save())
        state.putBundle("settingsPanels", Bundle().apply {
            restoredSettings?.let { putAll(it) }
            settingsPanels.forEach { (id, panel) -> putBundle(id, panel.saveState()) }
        })
        super.onSaveInstanceState(state)
    }
    override fun createContentView(): View {
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        surface().apply { setZOrderOnTop(false); visibility = View.INVISIBLE; contentDescription = "游戏预览" }
        secondaryPages = PaneHost(this) { renderPage(keepScroll = true) }
        arrangeWindow(resources.configuration)
        renderPage()
        secondaryPages.restore(restoredSecondaryPages)
        restoredSecondaryPages = null
        return ComposeView(this).apply {
            setPadding(dp(12), dp(4), dp(12), dp(8))
            setContent { Ui.Ripples { Root() } }
        }
    }
    private fun surface() = surfaceHost.getChildAt(0) as android.view.SurfaceView
    private fun dp(value: Int) = UiTheme.dp(this, value)
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        arrangeWindow(newConfig)
    }
    private fun arrangeWindow(config: Configuration) {
        if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.requestApplyInsets()
    }
    override fun startActivity(intent: Intent) {
        if (::secondaryPages.isInitialized && resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && secondaryPages.handles(intent)) {
            secondaryPages.open(intent)
        } else super.startActivity(intent)
    }
    @Deprecated("Platform back API")
    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        if (!::secondaryPages.isInitialized || !secondaryPages.back()) super.onBackPressed()
    }

    // ---------------------------------------------------------------- data (former renderPage)

    private fun renderPage(keepScroll: Boolean = false) {
        val renderedBefore = renderedPage
        if (renderedPage != page && renderedPage == "调度") pagePositions[renderedPage] = pageScroll.value
        var previousScroll = if (keepScroll) pageScroll.value
            else if (renderedPage != page) pagePositions[page] ?: 0 else 0
        val selected = store.selected()
        selectedProfile = selected
        profiles = store.list()
        profileNames = profiles.associate { it.id to it.name }
        val events = if (page == "日志") JSONArray() else store.events(selected)
        if (page != "日志") taskNames = ManualTasks.names + (0 until events.length()).associate {
            events.getJSONObject(it).let { e -> e.getString("func_name") to e.getString("event_name") }
        }
        if (page == "调度" && scheduleKeyValue.isNotEmpty() && !scheduleKeyValue.startsWith("${selected.id}|")) {
            previousScroll = 0; pagePositions.remove("调度")
        }
        renderedPage = page
        when (page) {
            "主页" -> refreshHomeTasks(force = true)
            "调度" -> buildSchedule(events)
            "设置" -> settingsPanels.getOrPut(selected.id) {
                SettingsPanel(this, selected.id, restoredSettings?.getBundle(selected.id))
            }
        }
        pendingScroll = previousScroll
        // Re-render of the same tab (profile switch etc.) starts at the top like the original; switching
        // tabs keeps each tab where the user left it.
        if (!keepScroll && renderedBefore == page && pageScroll.value != previousScroll) scrolls[page] = ScrollState(previousScroll)
        renders++
    }
    private fun buildSchedule(events: JSONArray) {
        val indices = scheduleIndices(events)
        scheduleKeyValue = scheduleKey(events)
        scheduleTotal = events.length()
        scheduleEnabled = (0 until events.length()).count { events.getJSONObject(it).getBoolean("enabled") }
        scheduleSaved = false
        scheduleRows = indices.map { index ->
            val event = events.getJSONObject(index)
            ScheduleRowModel(event, formatInterval(event.optDouble("interval")))
        }
    }
    private fun scheduleIndices(events: JSONArray): List<Int> {
        val now = System.currentTimeMillis() / 1000.0
        return (0 until events.length()).filter { index ->
            val enabled = events.getJSONObject(index).getBoolean("enabled")
            taskFilter == "全部" || (taskFilter == "已启用") == enabled
        }.sortedWith(Comparator { a, b ->
            val first = events.getJSONObject(a); val second = events.getJSONObject(b)
            val active = second.getBoolean("enabled").compareTo(first.getBoolean("enabled"))
            if (active != 0) active else TaskOrder.compare(first.optDouble("next_tick", 0.0), first.optDouble("priority"),
                second.optDouble("next_tick", 0.0), second.optDouble("priority"), now, sortByTime)
        })
    }
    private fun scheduleKey(events: JSONArray): String {
        // Exclude next_tick: task completion normally needs only a text/color update.
        val shape = JSONArray()
        for (i in 0 until events.length()) {
            val event = events.getJSONObject(i)
            shape.put(JSONArray(listOf("func_name", "event_name", "enabled", "priority", "interval").map { event.opt(it) }))
        }
        return "${selectedProfile?.id}|$taskFilter|$sortByTime|$shape|${scheduleIndices(events)}"
    }
    private fun refreshSchedulePage() {
        if (scheduleDragging) return
        val selected = selectedProfile ?: return
        val events = store.events(selected)
        if (scheduleKey(events) != scheduleKeyValue) renderPage(keepScroll = true)
        else {
            val byName = (0 until events.length()).associate { events.getJSONObject(it).let { e -> e.getString("func_name") to e } }
            scheduleRows.forEach { row -> byName[row.name]?.let { row.event.put("next_tick", it.opt("next_tick")) } }
            scheduleTimes++
        }
    }
    private fun scheduleTime(event: JSONObject): String {
        val tick = event.optDouble("next_tick", 0.0)
        if (tick <= 0) return "待执行"
        val remaining = tick - System.currentTimeMillis() / 1000.0
        val relative = when {
            remaining <= 0 -> "待执行"
            remaining < 3600 -> "${kotlin.math.ceil(remaining / 60).toInt()} 分钟后"
            remaining < 86400 -> "${(remaining / 3600).toInt()} 小时后"
            else -> "${(remaining / 86400).toInt()} 天后"
        }
        val date = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date((tick * 1000).toLong()))
        return "$date\n$relative"
    }
    private fun nextRun(event: JSONObject): String {
        val tick = event.optDouble("next_tick", 0.0)
        if (tick <= System.currentTimeMillis() / 1000.0) return "待执行"
        return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date((tick * 1000).toLong()))
    }
    private fun nextRunState(event: JSONObject, now: Double = System.currentTimeMillis() / 1000.0): Int {
        if (!event.getBoolean("enabled")) return 2
        val remaining = event.optDouble("next_tick", 0.0) - now
        return when { remaining <= 0 -> 0; remaining < 3600 -> 1; else -> 2 }
    }
    private fun nextRunColor(event: JSONObject) = when (nextRunState(event)) {
        0 -> Ui.DeepBlue
        1 -> Ui.Blue
        else -> Ui.Muted
    }
    private fun refreshHomeTasks(force: Boolean = false) {
        if (page != "主页") return
        val selected = selectedProfile ?: return
        try {
            val events = store.events(selected)
            val now = System.currentTimeMillis() / 1000.0
            val enabled = (0 until events.length()).map { events.getJSONObject(it) }
                .filter { it.getBoolean("enabled") }
                .sortedWith(Comparator { a, b ->
                    TaskOrder.compare(a.optDouble("next_tick", 0.0), a.optDouble("priority"),
                        b.optDouble("next_tick", 0.0), b.optDouble("priority"), now, homeSortByTime)
                })
            // Read BAAS's committed schedule, rather than the last task-progress snapshot.
            val signature = "${selected.id}:$homeSortByTime:${events.length()}:" +
                enabled.joinToString("|") { "${it}:${nextRunState(it, now)}" }
            if (!force && signature == homeTaskSignature) return
            homeTaskSignature = signature
            homeTotal = events.length()
            homeTasks = enabled
        } catch (_: Exception) {
            // Keep the current list if the core is momentarily rewriting its schedule.
        }
    }
    private fun formatInterval(seconds: Double): String {
        if (seconds < 0) return "一次性"
        val value = when { seconds >= 86400 -> seconds / 86400; seconds >= 3600 -> seconds / 3600; seconds >= 60 -> seconds / 60; else -> seconds }
        val unit = when { seconds >= 86400 -> "天"; seconds >= 3600 -> "小时"; seconds >= 60 -> "分钟"; else -> "秒" }
        return java.text.DecimalFormat("0.##").format(value) + " " + unit
    }
    private fun startTask(task: String) {
        check(!TaskRuntime.isActive && !TaskRuntime.isProbeRunning && !ManagedMode.enabled) { "请先停止当前任务或托管" }
        if (!ensureShizukuReady()) return
        val selected = store.selected(); check(selected.runnable) { "此服务器的运行支持尚未接入" }
        startForegroundService(Intent(this, TaskService::class.java).setAction(TaskService.START)
            .putExtra("task", task).putExtra("profileId", selected.id))
    }
    private fun switchPage(name: String) { if (page != name) { page = name; renderPage() } }

    // ---------------------------------------------------------------- layout

    @Composable
    private fun Root() {
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        LiveTask.snapshot
        if (landscape) Row(Modifier.fillMaxSize()) {
            PreviewPanel(true, Modifier.weight(1f).fillMaxHeight())
            ContentHost(Modifier.weight(1f).fillMaxHeight().padding(start = 8.dp))
        } else Column(Modifier.fillMaxSize()) {
            PreviewPanel(false, Modifier.fillMaxWidth())
            ContentHost(Modifier.weight(1f).fillMaxWidth())
        }
    }

    @Composable
    private fun Takeover(landscape: Boolean) {
        val h = if (landscape) 32.dp else 34.dp
        if (takeoverVisible) BButton(takeoverText, Modifier.padding(start = 6.dp).height(h), flat = true, enabled = takeoverEnabled, disabledText = Ui.Blue,
            minHeight = h, minWidth = 0.dp, radius = 17.dp, padding = PaddingValues(horizontal = 12.dp)) { onTakeover() }
        BButton("关闭显示", Modifier.padding(start = 6.dp).height(h), flat = true, minHeight = h, minWidth = 0.dp, radius = 17.dp,
            padding = PaddingValues(horizontal = 12.dp), description = "关闭显示") { closePhysicalDisplay() }
    }

    @Composable
    private fun PreviewPanel(landscape: Boolean, modifier: Modifier) {
        val selected = selectedProfile ?: return
        val active = TaskRuntime.isActive
        Column(modifier) {
            Row(Modifier.fillMaxWidth().homeItem(), verticalAlignment = Alignment.CenterVertically) {
                if (!landscape) HaloBrand(Modifier.padding(start = 2.dp, end = 8.dp))
                Box(if (landscape) Modifier.width(144.dp) else Modifier.weight(1f).padding(end = 6.dp)) {
                    var width by remember { mutableIntStateOf(0) }
                    BButton("${selected.name} ▾", Modifier.fillMaxWidth().height(32.dp).onGloballyPositioned { width = it.size.width },
                        flat = true, minHeight = 32.dp, start = true, radius = 16.dp, padding = PaddingValues(horizontal = 14.dp),
                        description = "选择配置") { choosingProfile = true }
                    if (choosingProfile) ProfilePopup(selected, with(LocalDensity.current) { width.toDp() })
                }
                BButton("管理", Modifier.size(48.dp, 32.dp), flat = true, minHeight = 32.dp, minWidth = 0.dp, padding = NoPadding, radius = 16.dp) {
                    safely { startActivity(Intent(this@HomeActivity, ProfileActivity::class.java)) }
                }
                if (landscape) {
                    Spacer(Modifier.weight(1f))
                    Takeover(true)
                }
            }
            val gameShown = previewAvailable && previewGameRunning
            Box((if (landscape) Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp) else Modifier.fillMaxWidth().padding(start = 3.dp, top = 6.dp, end = 3.dp).aspectRatio(16 / 9f))
                .softShadow(10.dp, RoundedCornerShape(16.dp), 0.22f)
                .background(if (gameShown) Ui.Pale else Color(0xFF060B14), RoundedCornerShape(16.dp))) {
                SurfaceHost(surfaceHost, Modifier.fillMaxSize())
                // A SurfaceView ignores Compose clipping, so round its corners by painting the backdrop over them.
                Box(Modifier.fillMaxSize().roundedMask(16.dp, Ui.BgTop))
                if (!previewAvailable) {
                    val ready = shizuku == ShizukuAccess.State.READY
                    Box(Modifier.fillMaxSize().then(if (!ready) Modifier.clickable(remember { MutableInteractionSource() }, null) { requestShizukuAccess() } else Modifier),
                        contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(44.dp).drawBehind {
                                drawCircle(Color.White.copy(alpha = .22f), size.minDimension / 2, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                                drawCircle(Color(0xFF3DB8F5).copy(alpha = .5f), size.minDimension / 2 - 7.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
                            })
                            BText(if (ready) "游戏未启动" else "${shizuku.label}\n${shizuku.action}", 13f, Color(0xFFB8C7D6), align = TextAlign.Center)
                        }
                    }
                }
                if (landscape) androidx.compose.material3.Text(statusText, Modifier.padding(8.dp).background(Color(0x99060B14), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp), style = textStyle(11f, Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!landscape) Row(Modifier.fillMaxWidth().homeItem(), verticalAlignment = Alignment.CenterVertically) {
                BText(statusText, 11f, Ui.Muted, Modifier.weight(1f).homeItem().clickable(remember { MutableInteractionSource() }, null) { requestShizukuAccess() },
                    padding = PaddingValues(4.dp))
                Takeover(false)
            }
            Row(Modifier.fillMaxWidth().homeItem(), verticalAlignment = Alignment.CenterVertically) {
                BButton("展开预览", Modifier.weight(1f).homeItem(), enabled = previewAvailable, disabledAlpha = .45f) {
                    safely { startActivity(Intent(this@HomeActivity, PreviewActivity::class.java)) }
                }
                BButton("启动游戏", Modifier.weight(1f).homeItem(), enabled = !previewGameRunning && !active && !ManagedMode.enabled && selected.runnable, disabledAlpha = .45f) {
                    val profile = store.selected()
                    safely { launchPreviewGame(profile.gamePackage) }
                }
                BButton("关闭游戏", Modifier.weight(1f).homeItem(), enabled = !active && previewAvailable, disabledAlpha = .45f) { safely { closePreviewGame() } }
            }
        }
    }

    @Composable
    private fun ProfilePopup(selected: ProfileStore.Profile, width: androidx.compose.ui.unit.Dp) {
        val list = remember { store.list() }
        Popup(popupPositionProvider = remember { DropPosition(0, false) }, onDismissRequest = { choosingProfile = false },
            properties = PopupProperties(focusable = true)) {
            val shape = RoundedCornerShape(14.dp)
            Column(Modifier.width(width).softShadow(12.dp, shape, 0.2f).background(Ui.Card, shape).border(1.dp, Ui.Border, shape).clip(shape).padding(4.dp)) {
                list.forEach { profile ->
                    val on = profile.id == selected.id
                    Box(Modifier.fillMaxWidth().defaultMinSize(minHeight = 40.dp).background(if (on) Ui.Pale else Color.Transparent, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp))
                        .clickable { choosingProfile = false; safely { store.select(profile.id); renderPage() } }
                        .padding(horizontal = 10.dp, vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
                        BText(profile.name, 12f, if (on) Ui.Blue else Ui.Navy, padding = NoPadding, maxLines = 1)
                    }
                }
            }
        }
    }

    @Composable
    private fun ContentHost(modifier: Modifier) {
        Box(modifier) {
            val top = secondaryPages.pages.lastOrNull()
            if (top != null) androidx.compose.runtime.key(top) { top.PaneRoot() }
            else Column(Modifier.fillMaxSize()) {
                val r = renders
                // Both tabs stay opaque-content-over-backdrop while sliding, and data is loaded synchronously
                // in renderPage() before `page` recomposes, so there is never an empty or see-through frame.
                androidx.compose.animation.AnimatedContent(page, Modifier.weight(1f).fillMaxWidth().clipToBounds(),
                    transitionSpec = {
                        val dir = if (HomeTabs.indexOf(targetState) > HomeTabs.indexOf(initialState)) 1 else -1
                        (androidx.compose.animation.slideInHorizontally(tween(240, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { dir * it / 6 } +
                            androidx.compose.animation.fadeIn(tween(180, 40))) togetherWith
                            (androidx.compose.animation.slideOutHorizontally(tween(240, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { -dir * it / 6 } +
                                androidx.compose.animation.fadeOut(tween(120)))
                    }, label = "tabs") { shown ->
                    if (shown == "日志") Column(Modifier.fillMaxSize().padding(vertical = 4.dp)) { LogPage() }
                    else {
                        val scroll = scrollOf(shown)
                        Column(Modifier.fillMaxSize().then(if (shown == "调度") Modifier.onGloballyPositioned { viewport = it } else Modifier)
                            .fadingScrollbar(scroll).verticalScroll(scroll).padding(top = 4.dp, bottom = 10.dp)) {
                            androidx.compose.runtime.key(r) {
                                when (shown) {
                                    "主页" -> HomePage()
                                    "托管" -> ManagedPage()
                                    "调度" -> SchedulePage()
                                    "设置" -> SettingsPage()
                                }
                            }
                        }
                    }
                }
                TabBar(page, Modifier.padding(horizontal = 3.dp, vertical = 4.dp)) { name -> safely { switchPage(name) } }
            }
        }
    }

    // ---------------------------------------------------------------- 主页

    @Composable
    private fun HomePage() {
        val selected = selectedProfile ?: return
        val snapshot = LiveTask.snapshot
        HeroCard(Modifier.homeItem(), active = TaskRuntime.isActive) {
            val runtimeState = TaskRuntime.state
            var state = when (runtimeState) {
                "starting" -> "启动中"; "running" -> "执行中"; "pausing" -> "等待暂停"; "paused" -> "已暂停 · 可手动接管"
                "stopping" -> "正在停止"; "failed" -> "任务失败 · 请查看日志"; "completed" -> "本轮已完成"; else -> "待机"
            }
            val progress = JSONObject(snapshot).optJSONObject("progress")
            if (runtimeState == "running" && progress?.optString("phase") == "idle") state = "等待下次任务"
            val currentTask = progress?.optString("currentTask")?.takeIf { it.isNotBlank() && it != "null" }
            val taskLabel = currentTask?.let { "$state · ${ManualTasks.displayName(it, taskNames)}" } ?: state
            val runningId = progress?.optString("profile")?.substringAfterLast('/')
            val runningName = profileNames[runningId] ?: selected.name
            val next = progress?.optDouble("nextWakeAt", Double.NaN) ?: Double.NaN
            val dot = when (runtimeState) { "failed" -> Color(0xFFFF6B75); "running", "starting" -> Color(0xFF3EE6A8); "paused", "pausing" -> Color(0xFFFFC857); else -> Color.White }
            val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
            StatusChip(if (TaskRuntime.isActive) runningName else selected.name, dot, pulse = runtimeState == "running" || runtimeState == "starting",
                Modifier.padding(start = 2.dp, top = 2.dp))
            RollingText(taskLabel, 20f, Color.White, FontWeight.Bold, Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp))
            BText(if (TaskRuntime.isActive) "运行配置：$runningName" +
                (if (next.isFinite()) " · 下次 ${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date((next * 1000).toLong()))}" else "")
                else "${selected.server} · ${homeTasks.size} 个任务已启用", 12f, Color.White.copy(alpha = .78f), Modifier.fillMaxWidth(), maxLines = 1)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                HeroButton("开始", Modifier.weight(1f).homeItem(), solid = true,
                    enabled = !TaskRuntime.isActive && !ManagedMode.enabled && selected.runnable && !TaskRuntime.isProbeRunning) {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); safely { startTask("core_profile") }
                }
                HeroButton("停止任务", Modifier.weight(1f).homeItem(), solid = false, enabled = TaskRuntime.isActive || ManagedMode.enabled) {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    safely { if (ManagedMode.enabled) ManagedMode.stop() else if (TaskRuntime.isActive) startService(Intent(this@HomeActivity, TaskService::class.java).setAction(TaskService.STOP)) }
                }
            }
        }
        Caption("任务 · ${homeTasks.size} / $homeTotal 已启用", Modifier.homeItem()) {
            SortToggle(homeSortByTime) {
                homeSortByTime = it
                getPreferences(MODE_PRIVATE).edit().putBoolean("homeSortByTime", homeSortByTime).apply()
                refreshHomeTasks(force = true)
            }
        }
        Column(Modifier.fillMaxWidth().homeItem()) {
            BCard(Modifier.homeItem()) {
                val enabled = homeTasks
                if (enabled.isEmpty()) {
                    BText("暂无启用任务", 14f, Ui.Muted, Modifier.fillMaxWidth().homeItem())
                    BButton("前往调度", Modifier.fillMaxWidth().homeItem()) { safely { page = "调度"; renderPage() } }
                }
                val now = System.currentTimeMillis() / 1000.0
                enabled.forEachIndexed { index, event ->
                    if (index > 0) Box(Modifier.padding(start = 22.dp)) { Divider() }
                    val due = nextRunState(event, now) == 0
                    val color = nextRunColor(event)
                    Row(Modifier.fillMaxWidth().homeItem().animateContentSize(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.padding(start = 2.dp, end = 10.dp).size(10.dp).background(color.copy(alpha = .18f), CircleShape).padding(2.5.dp).background(color, CircleShape))
                        Column(Modifier.weight(1f)) {
                            BText(event.getString("event_name"), 14f, weight = FontWeight.Medium, modifier = Modifier.fillMaxWidth(), padding = PaddingValues(top = 6.dp, bottom = 1.dp))
                            BText("下次执行：${nextRun(event)}", 11f, color, Modifier.fillMaxWidth(),
                                weight = if (due) FontWeight.Bold else FontWeight.Normal, padding = PaddingValues(bottom = 6.dp))
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SortToggle(byTime: Boolean, change: (Boolean) -> Unit) =
        BButton(if (byTime) "⇅ 下次执行" else "⇅ 执行顺序", flat = true) { safely { change(!byTime) } }

    // ---------------------------------------------------------------- 托管

    @Composable
    private fun ManagedPage() {
        LiveTask.tick; managedRevision
        val snapshot = LiveTask.snapshot
        val list = profiles
        HeroCard(Modifier.homeItem(), active = ManagedMode.enabled && TaskRuntime.isActive) {
            val value = JSONObject(snapshot).optJSONObject("progress")
            val runningId = value?.optString("profile")?.substringAfterLast('/')
            val name = list.firstOrNull { it.id == runningId }?.name
            val status = if (ManagedMode.enabled && TaskRuntime.isActive) {
                when (TaskRuntime.state) { "paused" -> "托管已暂停"; "pausing" -> "等待暂停"; "stopping" -> "正在停止托管"; else -> "托管执行中" }
            } else when {
                ManagedMode.phase.startsWith("托管停止") -> "托管已停止"
                ManagedMode.phase == "等待手动重试" -> "等待任务"
                else -> ManagedMode.phase
            }
            val next = ManagedMode.nextWakeAt
            val nextName = list.firstOrNull { it.id == ManagedMode.nextProfileId }?.name
            val detail = when {
                ManagedMode.enabled && TaskRuntime.isActive && name != null -> "运行配置：$name"
                ManagedMode.enabled && next > 0 -> "下次执行：${nextName?.let { "$it · " }.orEmpty()}${SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(next))}"
                else -> "已选 ${ManagedMode.selectedIds().size} 个配置"
            }
            StatusChip(if (ManagedMode.enabled) "托管中" else "托管", if (ManagedMode.enabled) Color(0xFF3EE6A8) else Color.White,
                pulse = ManagedMode.enabled && TaskRuntime.isActive, Modifier.padding(start = 2.dp, top = 2.dp))
            RollingText(status, 20f, Color.White, FontWeight.Bold, Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp))
            BText(detail, 12f, Color.White.copy(alpha = .78f), Modifier.fillMaxWidth(), maxLines = 1)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                HeroButton("开启托管", Modifier.weight(1f).homeItem(), solid = true,
                    enabled = !ManagedMode.enabled && !TaskRuntime.isActive && !TaskRuntime.isProbeRunning) {
                    safely {
                        if (!ManagedMode.canSchedule()) {
                            startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                android.net.Uri.parse("package:$packageName")))
                        } else if (ensureShizukuReady()) ManagedMode.start()
                    }
                }
                HeroButton("停止托管", Modifier.weight(1f).homeItem(), solid = false, enabled = ManagedMode.enabled) { safely { ManagedMode.stop() } }
            }
        }
        Caption("托管配置", Modifier.homeItem())
        val selectedIds = ManagedMode.selectedIds().toSet()
        list.forEach { managedProfile ->
            BCard(Modifier.homeItem()) {
                Row(Modifier.fillMaxWidth().homeItem(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).homeItem()) {
                        BText(managedProfile.name, 14f, modifier = Modifier.fillMaxWidth().homeItem())
                        ProfileResources(managedProfile, managedProfile.id in selectedIds, Modifier.fillMaxWidth().homeItem())
                    }
                    CompactSwitch(managedProfile.id in selectedIds, "托管${managedProfile.name}", Modifier.size(44.dp, 44.dp), enabled = managedProfile.runnable) { checked ->
                        ManagedMode.select(managedProfile.id, checked); managedRevision++
                        if (checked) resourceRoll[managedProfile.id] = true else resourceRoll.remove(managedProfile.id)
                    }
                }
            }
        }
        val history = ManagedMode.failureHistory()
        Ui.Quiet {
            Row(Modifier.fillMaxWidth().homeItem().defaultMinSize(minHeight = 48.dp).background(Ui.Card, RoundedCornerShape(14.dp)).border(1.dp, Ui.Border, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))
                .padding(start = 8.dp)
                .clickable { managedHistoryExpanded = !managedHistoryExpanded; managedRevision++ }, verticalAlignment = Alignment.CenterVertically) {
                BText("失败历史", 12f, Ui.Muted)
                BText("${history.length()} 条", 11f, Ui.Muted, Modifier.weight(1f))
                Chevron(managedHistoryExpanded, Modifier.size(24.dp, 44.dp))
            }
        }
        androidx.compose.animation.AnimatedVisibility(managedHistoryExpanded,
            enter = androidx.compose.animation.expandVertically(tween(240)) + androidx.compose.animation.fadeIn(tween(200)),
            exit = androidx.compose.animation.shrinkVertically(tween(200)) + androidx.compose.animation.fadeOut(tween(150))) {
            BCard(Modifier.homeItem()) { FailureHistory(history, list) }
        }
    }

    @Composable
    private fun FailureHistory(history: JSONArray, list: List<ProfileStore.Profile>) {
        val states = list.associate { it.id to ManagedMode.recovery(it.id) }
        if (history.length() > 0) BButton("清空", Modifier.size(48.dp, 32.dp), flat = true, textSize = 11f, minWidth = 0.dp, minHeight = 32.dp, stroke = null) {
            safely { ManagedMode.clearFailureHistory(); managedRevision++ }
        }
        val seen = mutableSetOf<String>()
        if (history.length() == 0) BText("暂无记录", 12f, Ui.Muted)
        else for (index in history.length() - 1 downTo 0) {
            val entry = history.getJSONObject(index)
            val id = entry.optString("profileId").ifBlank { list.firstOrNull { it.name == entry.optString("profile") }?.id.orEmpty() }
            val latest = !entry.optBoolean("simulated") && id.isNotEmpty() && seen.add(id)
            val retryable = latest && states[id]?.length()?.let { it > 0 } == true
            val date = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(entry.getLong("time")))
            Divider()
            Ui.Quiet {
                Column(Modifier.fillMaxWidth().background(Ui.Card, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp))
                    .clickable { safely { showFailure(entry, date) } }.padding(horizontal = 4.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BText(entry.optString("profile"), 12f, modifier = Modifier.weight(1f), weight = FontWeight.Bold, padding = PaddingValues(end = 8.dp), maxLines = 1)
                        BText(date, 10f, Ui.Muted, padding = NoPadding)
                    }
                    val result = entry.optString("result")
                    val color = when {
                        result == "重试已取消" -> Ui.Muted
                        result.contains("成功") -> Ui.Success
                        result.contains("挂起") || result.contains("停止") -> Ui.Danger
                        else -> Ui.Blue
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BText(result, 11f, color, Modifier.weight(1f), padding = PaddingValues(top = 4.dp, bottom = 2.dp))
                        if (retryable) BButton("立即尝试", Modifier.size(84.dp, 34.dp), flat = true, minWidth = 0.dp, minHeight = 34.dp,
                            enabled = ManagedMode.enabled && !TaskRuntime.isActive, disabledAlpha = .45f) {
                            safely { ManagedMode.retryProfile(id); managedRevision++ }
                        }
                    }
                    val reason = entry.optString("error").trim().lineSequence().lastOrNull().orEmpty()
                    BText(reason.take(240), 11f, Ui.Muted, padding = NoPadding, maxLines = 2)
                }
            }
        }
    }

    private fun column() = UiTheme.column(this)
    private fun text(value: String, size: Float = 14f, color: Int = UiTheme.navy) = UiTheme.text(this, value, size, color)
    /** Failure record dialog: unchanged platform AlertDialog. */
    private fun showFailure(entry: JSONObject, date: String) {

                    val task = entry.optString("task")
                    val phase = when (entry.optString("phase")) {
                        "restoring" -> "恢复游戏"
                        "executing" -> "执行任务"
                        "initializing" -> "初始化"
                        "managed" -> "托管调度"
                        "planning" -> "读取任务计划"
                        else -> ""
                    }
                    fun timestamp(key: String): String? = entry.optDouble(key, 0.0).takeIf { it > 0 }?.let {
                        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                            .format(java.util.Date((it * 1000).toLong()))
                    }
                    fun elapsed(key: String): String? = entry.optDouble(key, -1.0).takeIf { it >= 0 }?.let {
                        val seconds = it.toLong()
                        if (seconds < 60) "${seconds} 秒" else "${seconds / 60} 分 ${seconds % 60} 秒"
                    }
                    val frame = entry.optJSONObject("frame")
                    val image = FailureFrames.file(this@HomeActivity, frame)?.takeIf { it.isFile }
                    val frameStatus = when (frame?.optString("status")) {
                        "capture_failed" -> if (image != null) "截图通道失败，附此前最后一帧" else "截图通道失败，未获取到有效帧"
                        "available" -> if (image != null) "最后一张有效帧（并非失败瞬间截图）" else "图片未能保存或已丢失"
                        "not_captured" -> "流程失败前尚未获取到有效帧"
                        else -> "旧记录未保存截图信息"
                    }
                    val frameTime = frame?.optDouble("time", 0.0)?.takeIf { it > 0 }
                    val detail = listOfNotNull(
                        "配置：${entry.optString("profile")}", "记录时间：$date", "处理：${entry.optString("result")}",
                        timestamp("startedAt")?.let { "进入流程：$it" },
                        timestamp("failedAt")?.let { "失败时间：$it" },
                        elapsed("elapsedSeconds")?.let { "流程耗时：$it" },
                        phase.takeIf { it.isNotBlank() }?.let { "失败阶段：$it" },
                        task.takeIf { it.isNotBlank() }?.let { "失败时任务：${ManualTasks.displayName(it, ManualTasks.names)}" },
                        timestamp("taskStartedAt")?.let { "任务开始：$it" },
                        elapsed("taskElapsedSeconds")?.let { "任务耗时：$it" },
                        entry.optString("context").takeIf { it.isNotBlank() }?.let { "\n错误前的日志：\n$it" },
                        "\n截图状态：$frameStatus",
                        frameTime?.let { "最后获取时间：" + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date((it * 1000).toLong())) },
                        frameTime?.let { captured -> entry.optDouble("failedAt", 0.0).takeIf { it >= captured }
                            ?.let { "获取后距失败：${(it - captured).toLong()} 秒" } },
                        frame?.takeIf { it.has("ageMs") }?.let { "获取时帧龄：${it.optLong("ageMs") / 1000} 秒" },
                        frame?.optString("error")?.takeIf { it.isNotBlank() }?.let { "截图错误：$it" },
                        frame?.optString("saveError")?.takeIf { it.isNotBlank() }?.let { "图片保存失败：$it" },
                        "\n错误详情：\n${entry.optString("error")}").joinToString("\n")
                    val contents = column().apply {
                        setPadding(dp(20), dp(12), dp(20), dp(12))
                        addView(text(detail, 13f, UiTheme.navy).apply { setTextIsSelectable(true) })
                        if (image != null) {
                            val bitmap = android.graphics.BitmapFactory.decodeFile(image.absolutePath)
                            if (bitmap != null) addView(ImageView(this@HomeActivity).apply {
                                setImageBitmap(bitmap); adjustViewBounds = true
                                contentDescription = "最后一张有效游戏帧"
                                setPadding(0, dp(12), 0, 0)
                                setOnClickListener {
                                    UiTheme.dialog(this@HomeActivity).setTitle("最后一帧")
                                        .setView(ImageView(this@HomeActivity).apply {
                                            setImageBitmap(bitmap); adjustViewBounds = true
                                        }).setPositiveButton("关闭", null).show()
                                }
                            }, LinearLayout.LayoutParams(-1, -2))
                        }
                    }
                    val dialog = UiTheme.dialog(this@HomeActivity).setTitle("错误记录")
                        .setView(ScrollView(this@HomeActivity).apply { addView(contents) }).setPositiveButton("关闭", null)
                        .setNeutralButton("复制") { _, _ ->
                            getSystemService(android.content.ClipboardManager::class.java)
                                .setPrimaryClip(android.content.ClipData.newPlainText("BAAS 错误记录", detail))
                        }
                    if (image != null) dialog.setNegativeButton("保存图片") { _, _ ->
                        safely {
                            exportingFailureFrame = image.name
                            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("image/png")
                                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "BAAS-${image.name}"), 301)
                        }
                    }
                    dialog.show()
                
    }

    /**
     * ProfileResourcesView: server line, then AP / credits / pyroxene read from config.json on each poll.
     * Readings are cached per profile at activity scope: a recreated row (tab switch re-render, AnimatedContent's
     * outgoing slot) shows the last real values synchronously, never the '—' placeholder or an odometer roll from it.
     */
    private val resourceCache = HashMap<String, Map<String, Pair<String, String>>>()
    private val resourceState = mutableStateMapOf<String, Map<String, Pair<String, String>>>()
    /** Profiles whose resource row was just switched on: the next appearance plays the digit-wheel intro once. */
    private val resourceRoll = mutableStateMapOf<String, Boolean>()
    private fun readResources(profile: ProfileStore.Profile): Map<String, Pair<String, String>> {
        val readings = HashMap<String, Pair<String, String>>()
        val config = runCatching { JSONObject(File(profile.path, "config.json").readText()) }.getOrNull() ?: return readings
        val counts = NumberFormat.getIntegerInstance(Locale.CHINA)
        val scannedAt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
        fun update(key: String, data: JSONObject?, label: String, format: (Long, JSONObject) -> String) {
            val count = data?.optLong("count", -1) ?: -1
            val time = data?.optDouble("time", 0.0) ?: 0.0
            val valid = count >= 0 && time.isFinite() && time > 0
            val value = if (valid) format(count, requireNotNull(data)) else "—"
            val detail = if (valid) "$label：${counts.format(count)}\n扫描于 ${scannedAt.format(Date((time * 1000).toLong()))}" else "$label：尚未扫描"
            readings[key] = value to detail
        }
        update("ap", config.optJSONObject("ap"), "体力") { count, data ->
            val max = data.optLong("max", -1)
            if (max >= 24 && count < max) {
                val elapsed = (System.currentTimeMillis() / 1000.0 - data.optDouble("time")).coerceAtLeast(0.0)
                val recovered = (elapsed / 360).toLong().coerceAtMost(max - count)
                "${count + recovered}/$max"
            } else if (max >= 24) "$count/$max" else count.toString()
        }
        update("credits", config.optJSONObject("creditpoints"), "信用点") { count, _ ->
            when {
                count >= 1_000_000 -> String.format(Locale.US, "%.1fM", count / 1_000_000.0)
                count >= 1_000 -> String.format(Locale.US, "%.1fK", count / 1_000.0)
                else -> counts.format(count)
            }
        }
        update("gems", config.optJSONObject("pyroxene"), "青辉石") { count, _ -> counts.format(count) }
        return readings
    }
    @Composable
    private fun ProfileResources(profile: ProfileStore.Profile, show: Boolean, modifier: Modifier) {
        val tick = LiveTask.tick
        Column(modifier) {
            BText(profile.server, 11f, Ui.Muted)
            if (show) {
                val readings = resourceState[profile.id] ?: resourceCache.getOrPut(profile.id) { readResources(profile) }
                LaunchedEffect(tick, show, profile.id) {
                    val fresh = withContext(Dispatchers.IO) { readResources(profile) }
                    resourceCache[profile.id] = fresh
                    if (resourceState[profile.id] != fresh) resourceState[profile.id] = fresh
                }
                ResourceChips(listOf(Triple("ap", R.drawable.currency_icon_ap, "体力"), Triple("credits", R.drawable.currency_icon_gold, "信用点"),
                    Triple("gems", R.drawable.currency_icon_gem, "青辉石")).map { (key, icon, label) ->
                    val (value, detail) = readings[key] ?: ("—" to "$label：尚未扫描")
                    ResourceChip(icon, value, detail)
                }, roll = resourceRoll[profile.id] == true && readings.isNotEmpty())
                if (resourceRoll[profile.id] == true) LaunchedEffect(Unit) { kotlinx.coroutines.delay(1200); resourceRoll.remove(profile.id) }
            }
        }
    }

    // ---------------------------------------------------------------- 调度

    @Composable
    private fun SchedulePage() {
        val selected = selectedProfile ?: return
        Caption("${selected.name} · $scheduleEnabled / $scheduleTotal 个任务已启用", Modifier.homeItem()) {
            // "已保存" pops in as a small success pill instead of a text suffix (same timing as before).
            androidx.compose.animation.AnimatedVisibility(scheduleSaved, enter = androidx.compose.animation.scaleIn(androidx.compose.animation.core.spring(dampingRatio = .5f, stiffness = 700f)) + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut()) {
                BText("✓ 已保存", 11f, Ui.Success, Modifier.background(Ui.Success.copy(alpha = .12f), CircleShape), weight = FontWeight.Bold,
                    padding = PaddingValues(horizontal = 9.dp, vertical = 3.dp))
            }
        }
        Row(Modifier.fillMaxWidth().homeItem(), verticalAlignment = Alignment.CenterVertically) {
            @Composable fun compact(label: String, action: () -> Unit) =
                BButton(label, Modifier.weight(1f).homeItem(), flat = true, minHeight = 32.dp, textSize = 11.5f) { safely(action) }
            compact(if (scheduleEnabled == scheduleTotal) "全部禁用" else "全部启用") {
                val current = store.events(selected)
                val allEnabled = (0 until current.length()).all { current.getJSONObject(it).getBoolean("enabled") }
                store.setAllEnabled(selected.id, !allEnabled); renderPage()
            }
            compact("立即刷新") { store.resetSchedule(selected.id); renderPage() }
            compact("一键推迟") { store.resetSchedule(selected.id, true); renderPage() }
        }
        Row(Modifier.fillMaxWidth().homeItem(), verticalAlignment = Alignment.CenterVertically) {
            val filters = listOf("全部", "已启用", "已禁用")
            Segments(filters, filters.indexOf(taskFilter), Modifier.weight(1f).homeItem()) { safely { taskFilter = filters[it]; renderPage() } }
            SortToggle(sortByTime) { sortByTime = it; renderPage() }
        }
        if (scheduleRows.isEmpty()) BText("此筛选下没有任务", 14f, Ui.Muted, Modifier.fillMaxWidth().homeItem())
        val draggable = !sortByTime && taskFilter == "全部"
        ScheduleTable(scheduleRows, draggable, pageScroll, { viewport }, scheduleTimes, ::scheduleTime, ::nextRunColor,
            onConfigure = { event -> safely {
                startActivity(Intent(this, ScheduleActivity::class.java).putExtra("profileId", selected.id).putExtra("task", event.getString("func_name")))
            } },
            onToggle = { row, enabled ->
                try {
                    val events = store.events(selected)
                    val index = (0 until events.length()).first { events.getJSONObject(it).getString("func_name") == row.name }
                    store.setEnabled(selected.id, index, enabled)
                    row.event.put("enabled", enabled)
                    // The old code set "· 已保存" and then rebuilt the page, so the rebuilt summary has no suffix.
                    renderPage(keepScroll = true)
                } catch (error: Exception) { showError(error); throw error }
            },
            onReorder = { names ->
                try {
                    store.reorder(selected.id, names)
                    val byName = scheduleRows.associateBy { it.name }
                    names.forEachIndexed { i, name -> byName[name]?.event?.put("priority", i + 1) }
                    scheduleRows = names.mapNotNull { byName[it] }
                    val current = store.events(selected)
                    scheduleEnabled = (0 until current.length()).count { current.getJSONObject(it).getBoolean("enabled") }
                    scheduleTotal = current.length(); scheduleSaved = true
                } catch (error: Exception) { showError(error); throw error }
            },
            onDragging = { scheduleDragging = it },
            modifier = Modifier.homeItem())
    }

    // ---------------------------------------------------------------- 日志

    @Composable
    private fun androidx.compose.foundation.layout.ColumnScope.LogPage() {
        logModel.Track(LiveTask.logs, logQuery, logLevel)
        Row(Modifier.fillMaxWidth().homeItem(), verticalAlignment = Alignment.CenterVertically) {
            LogSearch(logQuery, { logQuery = it }, Modifier.weight(1f).homeItem())
            ToggleButton("自动滚动", followLogs, Modifier.padding(end = 6.dp), textSize = 11f, minHeight = 32.dp) { followLogs = !followLogs }
            Box(Modifier.size(32.dp).background(Ui.Card, RoundedCornerShape(10.dp)).border(1.dp, Ui.Border, RoundedCornerShape(10.dp))
                .clip(RoundedCornerShape(8.dp)).clickable {
                    startActivity(Intent(this@HomeActivity, LogActivity::class.java)
                        .putExtra("query", logQuery).putExtra("level", logLevel).putExtra("follow", followLogs))
                }.semantics { contentDescription = "全屏查看日志" }.padding(5.dp), contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.ic_fullscreen), null)
            }
        }
        Row(Modifier.fillMaxWidth().homeItem(), verticalAlignment = Alignment.CenterVertically) {
            LogLevels(logLevel, Modifier.weight(1f).homeItem()) { logLevel = it }
            LogCount(logModel, Modifier.width(80.dp))
        }
        LogOutput(logModel, followLogs, 10.dp, Modifier.fillMaxWidth().weight(1f))
    }

    // ---------------------------------------------------------------- 设置

    @Composable
    private fun SettingsPage() {
        val selected = selectedProfile ?: return
        val panel = settingsPanels[selected.id] ?: return
        panel.Content(Modifier.fillMaxWidth().homeItem())
        SettingsCard("配置文件管理", Modifier.homeItem()) { safely { startActivity(Intent(this, ProfileActivity::class.java)) } }
        // 外观: follow system / light / dark, applied live with a cross-fade (Appearance.switch).
        BCard(Modifier.homeItem()) {
            Row(Modifier.fillMaxWidth().homeItem(), verticalAlignment = Alignment.CenterVertically) {
                BText("外观", 13f, modifier = Modifier.weight(1f).homeItem(), weight = FontWeight.Bold)
                BText(if (Ui.night) "深色" else "浅色", 11f, Ui.Muted)
            }
            var mode by remember { mutableIntStateOf(Appearance.mode(this@HomeActivity)) }
            Segments(listOf("跟随系统", "浅色", "深色"), mode, Modifier.fillMaxWidth().homeItem(),
                descriptions = listOf("外观跟随系统", "浅色外观", "深色外观")) { picked ->
                mode = picked; safely { Appearance.switch(this@HomeActivity, picked) }
            }
        }
        BCard(Modifier.homeItem()) {
            val access = shizuku
            val authorized = access == ShizukuAccess.State.READY
            Row(Modifier.fillMaxWidth().homeItem(), verticalAlignment = Alignment.CenterVertically) {
                BText("应用与权限", 13f, modifier = Modifier.weight(1f).homeItem(), weight = FontWeight.Bold)
                val tone = if (authorized) Ui.Success else Ui.Danger
                Row(Modifier.background(tone.copy(alpha = .12f), CircleShape).padding(horizontal = 9.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(tone, CircleShape)); Spacer(Modifier.width(5.dp))
                    BText(access.label, 11f, tone, weight = FontWeight.Bold, padding = NoPadding)
                }
            }
            BButton(access.action, Modifier.fillMaxWidth().homeItem(), flat = true) { requestShizukuAccess() }
            BButton("应用与电池设置", Modifier.fillMaxWidth().homeItem(), flat = true) {
                safely { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:$packageName"))) }
            }
        }
        if (BuildConfig.DEBUG) SettingsCard("开发诊断", Modifier.homeItem()) { safely { startActivity(Intent().setClassName(this, "$packageName.MainActivity")) } }
        SettingsCard("关于", Modifier.homeItem()) { safely { AboutDialog.show(this) } }
    }

    // ---------------------------------------------------------------- lifecycle

    @Deprecated("Platform activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode in 201..203 && ::secondaryPages.isInitialized) {
            secondaryPages.result(requestCode, resultCode, data)
            return
        }
        if (requestCode != 301) return
        val name = exportingFailureFrame
        exportingFailureFrame = null
        if (resultCode != RESULT_OK) return
        safely {
            val image = FailureFrames.file(this, JSONObject().put("file", name))
            check(image?.isFile == true) { "图片已被清理，请重新打开错误记录" }
            contentResolver.openOutputStream(requireNotNull(data?.data), "wt").use { output ->
                requireNotNull(output) { "无法打开保存位置" }
                requireNotNull(image).inputStream().use { it.copyTo(output) }
            }
            UiNotice.show(this, "图片已保存")
        }
    }

    private fun showError(error: Exception) { UiNotice.show(this, error.message ?: "操作失败", error = true) }
    private fun safely(action: () -> Unit) { try { action() } catch (error: Exception) { showError(error) } }
    override fun onResume() {
        // Finish Android's notification permission before opening Shizuku's dialog.
        if (TaskNotifications.requestPermission(this)) notificationPermissionPending = true
        super.onResume(); visible = true
        if (::secondaryPages.isInitialized) secondaryPages.resume()
        if (::secondaryPages.isInitialized) {
            settingsPanels.keys.retainAll(store.list().map { it.id }.toSet())
            settingsPanels[store.selected().id]?.reloadIfClean()
            renderPage()
        }
        scheduleHandler.removeCallbacks(pollSchedule); scheduleHandler.postDelayed(pollSchedule, 2000)
    }
    private var notificationPermissionPending = false
    override val autoRequestShizukuPermission get() = !notificationPermissionPending
    @Deprecated("Platform permission result API (requestPermissions 103 from TaskNotifications)")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        @Suppress("DEPRECATION") super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 103) {
            notificationPermissionPending = false
            refreshShizukuAccess()
        }
    }
    override fun onPause() {
        visible = false; scheduleHandler.removeCallbacks(pollSchedule)
        super.onPause()
    }
    override fun onDestroy() {
        if (::secondaryPages.isInitialized) secondaryPages.destroy()
        scheduleHandler.removeCallbacksAndMessages(null)
        TaskRuntime.remove(changed); super.onDestroy()
    }
}
