package moe.baas.aio

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Portrait application shell; device IO and the upstream task engine remain shared. */
class HomeActivity : PreviewActivity() {
    override val fullscreenPreview = false
    override val createPreviewIfMissing = false
    private val store by lazy { ProfileStore(this) }
    private lateinit var selectedProfile: ProfileStore.Profile
    private val blue = UiTheme.blue
    private val navy = UiTheme.navy
    private val muted = UiTheme.muted
    private val pale = UiTheme.pale
    private val settingsPanels = mutableMapOf<String, SettingsPanel>()
    private var restoredSettings: Bundle? = null
    private var page = "主页"
    private lateinit var body: LinearLayout
    private lateinit var pageScroll: ScrollView
    private lateinit var bodyHost: FrameLayout
    private lateinit var profile: Button
    private lateinit var taskStatus: TextView
    private lateinit var taskDetail: TextView
    private var profileNames = emptyMap<String, String>()
    private var taskNames = emptyMap<String, String>()
    private var taskFilter = "全部"
    private var sortByTime = false
    private var homeSortByTime = true
    private lateinit var homeTasks: LinearLayout
    private lateinit var homeTaskCount: TextView
    private var homeTaskSignature = ""
    private val scheduleHandler = Handler(Looper.getMainLooper())
    private var visible = false
    private val pollSchedule = object : Runnable {
        override fun run() {
            if (!visible) return
            refreshPreviewState()
            if (page == "主页") refreshHomeTasks()
            if (page == "托管") refreshManagedStatus()
            scheduleHandler.postDelayed(this, 2000)
        }
    }
    private var logQuery = ""
    private var logLevel = 0
    private var followLogs = true
    private lateinit var followButton: Button
    private lateinit var logOutput: LogOutput
    private lateinit var run: Button
    private lateinit var stop: Button
    private lateinit var managedStatus: TextView
    private lateinit var managedDetail: TextView
    private lateinit var managedStart: Button
    private lateinit var managedStop: Button
    private val managedRetryButtons = mutableMapOf<String, Button>()
    private lateinit var managedHistoryTitle: TextView
    private lateinit var managedHistoryArrow: ImageView
    private lateinit var managedHistoryClear: Button
    private lateinit var managedHistoryBody: LinearLayout
    private var managedHistoryExpanded = false
    private var managedHistorySignature = ""
    private lateinit var expandPreview: Button
    private lateinit var previewGame: Button
    private lateinit var closeGame: Button
    private var previewAvailable = false
    private var previewGameRunning = false
    private lateinit var previewHint: TextView
    private lateinit var embeddedSurface: SurfaceView
    private val nav = mutableMapOf<String, Button>()
    private var lastTaskNotice = TaskRuntime.snapshot
    private val changed: (String) -> Unit = { snapshot ->
        if (!isDestroyed) {
            refreshStatus()
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
    private val logsChanged: (String) -> Unit = { if (!isDestroyed && page == "日志") renderLogs(it) }
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
        if (::previewHint.isInitialized) {
            previewHint.text = if (state == ShizukuAccess.State.READY) "游戏未启动" else "${state.label}\n${state.action}"
            previewHint.isClickable = state != ShizukuAccess.State.READY
        }
        if (page == "设置" && ::body.isInitialized) renderPage()
    }
    override fun onPreviewGameState(running: Boolean) { previewGameRunning = running }
    override fun onPreviewState(available: Boolean) {
        previewAvailable = available
        if (!available) previewGameRunning = false
        if (::closeGame.isInitialized) closeGame.isEnabled = available && !TaskRuntime.isActive
        if (::previewHint.isInitialized) previewHint.visibility = if (available) View.GONE else View.VISIBLE
        if (::embeddedSurface.isInitialized) embeddedSurface.visibility = if (available) View.VISIBLE else View.INVISIBLE
        if (::profile.isInitialized && ::selectedProfile.isInitialized) refreshStatus()
    }

    private fun dp(value: Int) = UiTheme.dp(this, value)
    private fun column() = UiTheme.column(this)
    private fun row() = UiTheme.row(this, Gravity.CENTER_VERTICAL)
    private fun text(value: String, size: Float = 14f, color: Int = navy) = UiTheme.text(this, value, size, color)
    private fun background(color: Int, radius: Int = 12) = UiTheme.background(this, color, radius)
    private fun button(label: String, primary: Boolean = false, action: () -> Unit) =
        UiTheme.button(this, label, primary) { safely(action) }
    private fun sortToggle(byTime: Boolean, change: (Boolean) -> Unit): Button {
        var current = byTime
        fun label() = if (current) "⇅ 下次执行" else "⇅ 执行顺序"
        lateinit var control: Button
        control = UiTheme.flatButton(this, label()) {
            safely { current = !current; control.text = label(); change(current) }
        }
        return control
    }
    private fun LinearLayout.item(view: View, weight: Float = 0f) {
        addView(view, LinearLayout.LayoutParams(if (weight > 0) 0 else -1, -2, weight).apply {
            setMargins(dp(3), dp(2), dp(3), dp(2))
        })
    }
    private fun card() = UiTheme.card(this)
    override fun onCreate(state: Bundle?) {
        ManagedMode.attach(this)
        store.initialize(); page = state?.getString("page") ?: "主页"
        restoredSettings = state?.getBundle("settingsPanels")
        taskFilter = state?.getString("taskFilter") ?: "全部"; sortByTime = state?.getBoolean("sortByTime") ?: false
        homeSortByTime = state?.getBoolean("homeSortByTime")
            ?: getPreferences(MODE_PRIVATE).getBoolean("homeSortByTime", true)
        logQuery = state?.getString("logQuery") ?: ""; logLevel = state?.getInt("logLevel") ?: 0
        followLogs = state?.getBoolean("followLogs") ?: true
        super.onCreate(state)
        TaskRuntime.observe(changed); TaskRuntime.observeLogs(logsChanged)
    }
    override fun onSaveInstanceState(state: Bundle) {
        state.putString("page", page); state.putString("taskFilter", taskFilter); state.putBoolean("sortByTime", sortByTime)
        state.putBoolean("homeSortByTime", homeSortByTime)
        state.putString("logQuery", logQuery); state.putInt("logLevel", logLevel); state.putBoolean("followLogs", followLogs)
        state.putBundle("settingsPanels", Bundle().apply {
            restoredSettings?.let { putAll(it) }
            settingsPanels.forEach { (id, panel) -> putBundle(id, panel.saveState()) }
        })
        super.onSaveInstanceState(state)
    }
    override fun buildPreviewLayout(surface: SurfaceView, status: TextView, takeover: Button): View {
        embeddedSurface = surface.apply {
            // This preview has no controls over the image; avoid punching a hole in the UI window.
            setZOrderOnTop(true); visibility = View.INVISIBLE; contentDescription = "游戏预览"
        }
        val root = column().apply { setBackgroundColor(pale); setPadding(dp(12), dp(4), dp(12), dp(8)) }
        root.item(row().apply {
            addView(text("BAAS", 16f, blue).apply {
                typeface = Typeface.create("sans-serif", Typeface.BOLD); letterSpacing = .02f
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                includeFontPadding = false; maxLines = 1
                setPadding(0, 0, dp(4), 0)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(32)))
            profile = UiTheme.flatButton(this@HomeActivity, "") { safely { chooseProfile() } }.apply {
                minHeight = dp(32); minimumHeight = dp(32)
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                contentDescription = "选择配置"
            }
            addView(profile, LinearLayout.LayoutParams(0, dp(32), 1f).apply { rightMargin = dp(6) })
            addView(UiTheme.flatButton(this@HomeActivity, "管理") {
                startActivity(Intent(this@HomeActivity, ProfileActivity::class.java))
            }.apply { minHeight = dp(32); minimumHeight = dp(32); setPadding(0, 0, 0, 0) },
                LinearLayout.LayoutParams(dp(40), dp(32)))
        })
        val preview = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(surface, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
            previewHint = text("游戏未启动", 14f, Color.LTGRAY).apply { gravity = Gravity.CENTER }
            previewHint.setOnClickListener { requestShizukuAccess() }
            addView(previewHint, FrameLayout.LayoutParams(-1, -1))
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutSurface() }
        }
        root.addView(preview, LinearLayout.LayoutParams(-1, 0).apply { setMargins(dp(3), dp(4), dp(3), 0) })
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val height = ((root.width - root.paddingLeft - root.paddingRight - dp(6)) * 9f / 16f).toInt()
            if (preview.layoutParams.height != height) preview.layoutParams = (preview.layoutParams as LinearLayout.LayoutParams).apply { this.height = height }
        }
        status.setTextColor(muted); status.setBackgroundColor(Color.TRANSPARENT); status.textSize = 11f
        status.setPadding(dp(4), dp(4), dp(4), dp(4))
        takeover.isAllCaps = false; takeover.textSize = 12f; takeover.setTextColor(blue)
        takeover.minHeight = dp(44); takeover.minimumHeight = dp(44)
        root.item(row().apply {
            item(status, 1f)
            addView(takeover, LinearLayout.LayoutParams(-2, dp(44)))
            addView(closePhysicalDisplayButton(takeover), LinearLayout.LayoutParams(-2, dp(44)))
        })
        root.item(row().apply {
            expandPreview = button("展开预览") { startActivity(Intent(this@HomeActivity, PreviewActivity::class.java)) }; item(expandPreview, 1f)
            previewGame = button("启动游戏") {
                val selected = store.selected()
                safely { launchPreviewGame(selected.gamePackage) }
            }
            item(previewGame, 1f)
            closeGame = button("关闭游戏") { closePreviewGame() }; item(closeGame, 1f)
        })
        body = column().apply { setPadding(0, dp(4), 0, dp(4)) }
        pageScroll = ScrollView(this).apply { isFillViewport = true; addView(body) }
        bodyHost = FrameLayout(this).apply { addView(pageScroll, FrameLayout.LayoutParams(-1, -1)) }
        root.addView(bodyHost, LinearLayout.LayoutParams(-1, 0, 1f))
        root.item(row().apply {
            for (name in listOf("主页") + (if (BuildConfig.DEBUG) listOf("托管") else emptyList()) + listOf("调度", "日志", "设置")) {
                val tab = button(name) { page = name; renderPage() }
                nav[name] = tab; item(tab, 1f)
            }
        })
        renderPage()
        return root
    }
    private fun chooseProfile() {
        val profiles = store.list()
        val popup = ListPopupWindow(this).apply {
            anchorView = profile
            width = profile.width
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            isModal = true
            setBackgroundDrawable(background(Color.WHITE, 8).apply { setStroke(dp(1), UiTheme.border) })
            setAdapter(object : ArrayAdapter<ProfileStore.Profile>(this@HomeActivity, android.R.layout.simple_list_item_1, profiles) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    (super.getView(position, convertView, parent) as TextView).apply {
                        val selected = getItem(position)?.id == selectedProfile.id
                        textSize = 12f; minHeight = dp(40)
                        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(dp(10), dp(8), dp(10), dp(8))
                        setTextColor(if (selected) blue else navy)
                        setBackgroundColor(if (selected) pale else Color.TRANSPARENT)
                    }
            })
        }
        popup.setOnItemClickListener { _, _, index, _ ->
            popup.dismiss()
            safely { store.select(profiles[index].id); renderPage() }
        }
        popup.show()
    }
    private fun renderPage(keepScroll: Boolean = false) {
        val previousScroll = if (keepScroll) pageScroll.scrollY else 0
        val parent = if (page == "日志") bodyHost else pageScroll
        if (body.parent !== parent) {
            (body.parent as? ViewGroup)?.removeView(body)
            parent.addView(body, FrameLayout.LayoutParams(-1, if (page == "日志") -1 else -2))
        }
        pageScroll.visibility = if (page == "日志") View.GONE else View.VISIBLE
        selectedProfile = store.selected()
        profileNames = store.list().associate { it.id to it.name }
        val events = store.events(selectedProfile)
        taskNames = ManualTasks.names + (0 until events.length()).associate { events.getJSONObject(it).let { e -> e.getString("func_name") to e.getString("event_name") } }
        body.removeAllViews()
        nav.forEach { (name, button) ->
            button.setTextColor(if (name == page) Color.WHITE else blue)
            button.background = background(if (name == page) blue else Color.WHITE, 10)
        }
        when (page) {
            "主页" -> {
                val controls = card()
                taskStatus = text("", 16f).apply { setTypeface(null, Typeface.BOLD) }; controls.item(taskStatus)
                taskDetail = text("", 12f, muted); controls.item(taskDetail)
                controls.item(row().apply {
                    run = button("开始", true) { startTask("core_profile") }; item(run, 1f)
                    stop = button("停止任务") { if (ManagedMode.enabled) ManagedMode.stop() else if (TaskRuntime.isActive) startService(Intent(this@HomeActivity, TaskService::class.java).setAction(TaskService.STOP)) }; item(stop, 1f)
                })
                body.item(controls)
                body.item(row().apply {
                    homeTaskCount = text("", 12f, muted); item(homeTaskCount, 1f)
                    addView(sortToggle(homeSortByTime) {
                        homeSortByTime = it
                        getPreferences(MODE_PRIVATE).edit().putBoolean("homeSortByTime", homeSortByTime).apply()
                        refreshHomeTasks(force = true)
                    }, LinearLayout.LayoutParams(-2, -2))
                })
                homeTasks = column(); body.item(homeTasks)
                refreshHomeTasks(force = true)
            }
            "托管" -> {
                managedRetryButtons.clear()
                body.item(card().apply {
                    managedStatus = text("", 16f).apply { setTypeface(null, Typeface.BOLD) }; item(managedStatus)
                    managedDetail = text("", 12f, muted); item(managedDetail)
                    item(row().apply {
                        managedStart = button("开启托管", true) {
                            if (!ManagedMode.canSchedule()) {
                                startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                    android.net.Uri.parse("package:$packageName")))
                            } else {
                                if (!ensureShizukuReady()) return@button
                                ManagedMode.start()
                            }
                        }; item(managedStart, 1f)
                        managedStop = button("停止托管") { ManagedMode.stop() }; item(managedStop, 1f)
                    })
                })
                body.item(text("托管配置", 12f, muted))
                val selectedIds = ManagedMode.selectedIds().toSet()
                store.list().forEach { managedProfile ->
                    body.item(card().apply {
                        item(row().apply {
                            item(column().apply {
                                item(text(managedProfile.name, 14f))
                                item(text(managedProfile.server, 11f, muted))
                            }, 1f)
                            addView(UiTheme.compactSwitch(this@HomeActivity, managedProfile.id in selectedIds, "托管${managedProfile.name}").apply {
                                isEnabled = managedProfile.runnable
                                setOnCheckedChangeListener { _, checked ->
                                    ManagedMode.select(managedProfile.id, checked); refreshManagedStatus()
                                }
                            }, LinearLayout.LayoutParams(dp(44), dp(44)))
                        })
                    })
                }
                val historyHeader = row().apply {
                    minimumHeight = dp(44)
                    isClickable = true; isFocusable = true
                    background = UiTheme.quietRipple(this@HomeActivity, pale, 10)
                    addView(text("失败历史", 12f, muted), LinearLayout.LayoutParams(-2, -2))
                    managedHistoryTitle = text("", 11f, muted)
                    addView(managedHistoryTitle, LinearLayout.LayoutParams(0, -2, 1f))
                    managedHistoryArrow = UiTheme.chevron(this@HomeActivity)
                    addView(managedHistoryArrow, LinearLayout.LayoutParams(dp(24), dp(44)))
                    setOnClickListener {
                        managedHistoryExpanded = !managedHistoryExpanded
                        managedHistoryArrow.animate().rotation(if (managedHistoryExpanded) 90f else 0f).setDuration(140).start()
                        refreshManagedStatus()
                    }
                }
                body.item(historyHeader)
                managedHistoryArrow.rotation = if (managedHistoryExpanded) 90f else 0f
                managedHistoryClear = UiTheme.flatButton(this, "清空") {
                    ManagedMode.clearFailureHistory(); refreshManagedStatus()
                }.apply {
                    textSize = 11f
                    background = UiTheme.quietRipple(this@HomeActivity, Color.WHITE, 8)
                }
                managedHistoryBody = card()
                body.item(managedHistoryBody)
                managedHistorySignature = ""
                refreshManagedStatus()
            }
            "调度" -> {
                val enabledCount = (0 until events.length()).count { events.getJSONObject(it).getBoolean("enabled") }
                val summary = text("$enabledCount / ${events.length()} 个任务已启用", 12f, muted)
                body.item(summary)
                lateinit var toggleAll: Button
                fun compactAction(label: String, action: () -> Unit) = UiTheme.flatButton(this, label) { safely(action) }.apply {
                    minHeight = dp(32); minimumHeight = dp(32); textSize = 11.5f
                }
                body.item(row().apply {
                    toggleAll = compactAction(if (enabledCount == events.length()) "全部禁用" else "全部启用") {
                        val current = store.events(selectedProfile)
                        val allEnabled = (0 until current.length()).all { current.getJSONObject(it).getBoolean("enabled") }
                        store.setAllEnabled(selectedProfile.id, !allEnabled); renderPage()
                    }
                    item(toggleAll, 1f)
                    item(compactAction("立即刷新") { store.resetSchedule(selectedProfile.id); renderPage() }, 1f)
                    item(compactAction("一键推迟") { store.resetSchedule(selectedProfile.id, true); renderPage() }, 1f)
                })
                body.item(row().apply {
                    item(UiTheme.segments(this@HomeActivity, listOf("全部", "已启用", "已禁用"), listOf("全部", "已启用", "已禁用").indexOf(taskFilter)) {
                        safely { taskFilter = listOf("全部", "已启用", "已禁用")[it]; renderPage() }
                    }, 1f)
                    addView(sortToggle(sortByTime) { sortByTime = it; renderPage() }, LinearLayout.LayoutParams(-2, -2))
                })
                val selected = selectedProfile
                val now = System.currentTimeMillis() / 1000.0
                val indices = (0 until events.length()).filter { index ->
                    val enabled = events.getJSONObject(index).getBoolean("enabled")
                    taskFilter == "全部" || (taskFilter == "已启用") == enabled
                }.sortedWith(Comparator { a, b ->
                    val first = events.getJSONObject(a); val second = events.getJSONObject(b)
                    val active = second.getBoolean("enabled").compareTo(first.getBoolean("enabled"))
                    if (active != 0) active else TaskOrder.compare(first.optDouble("next_tick", 0.0), first.optDouble("priority"),
                        second.optDouble("next_tick", 0.0), second.optDouble("priority"), now, sortByTime)
                })
                if (indices.isEmpty()) body.item(text("此筛选下没有任务", 14f, muted))
                val draggable = !sortByTime && taskFilter == "全部"
                val panel = column().apply {
                    background = UiTheme.background(this@HomeActivity, Color.WHITE, 10).apply { setStroke(dp(1), UiTheme.border) }
                    clipToOutline = true
                }; body.item(panel)
                panel.addView(row().apply {
                    background = background(pale, 0); setPadding(dp(6), dp(5), dp(6), dp(5))
                    if (draggable) addView(View(this@HomeActivity), LinearLayout.LayoutParams(dp(24), 1))
                    addView(text("#", 10f, muted).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(dp(24), -2))
                    addView(text("任务 / 间隔", 10f, muted), LinearLayout.LayoutParams(0, -2, 1f))
                    addView(text("下次执行", 10f, muted), LinearLayout.LayoutParams(dp(78), -2))
                    addView(text("启用", 10f, muted).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(dp(42), -2))
                    addView(text("配置", 10f, muted).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(dp(30), -2))
                })
                val taskList = column(); panel.addView(taskList)
                val labels = mutableMapOf<View, TextView>()
                val drag = if (!sortByTime && taskFilter == "全部") ScheduleDrag(
                    taskList, pageScroll, { true }, { cards ->
                        store.reorder(selected.id, cards.map { it.tag as String })
                        cards.forEachIndexed { i, view ->
                            val event = (0 until events.length()).map { events.getJSONObject(it) }.first { it.getString("func_name") == view.tag }
                            labels.getValue(view).text = (i + 1).toString()
                            event.put("priority", i + 1)
                        }
                        val current = store.events(selected)
                        val count = (0 until current.length()).count { current.getJSONObject(it).getBoolean("enabled") }
                        summary.text = "$count / ${current.length()} 个任务已启用 · 已保存"
                    }, { showError(it) }, { source, target ->
                        (source as ScheduleTaskRow).enabled == (target as ScheduleTaskRow).enabled
                    }) else null
                indices.forEach { index ->
                    val event = events.getJSONObject(index)
                    lateinit var taskRow: ScheduleTaskRow
                    taskRow = ScheduleTaskRow(this, event, draggable, formatInterval(event.optDouble("interval")),
                        scheduleTime(event), { nextRunColor(event) }, {
                            safely {
                                startActivity(Intent(this, ScheduleActivity::class.java).putExtra("profileId", selected.id).putExtra("task", event.getString("func_name")))
                            }
                        }, { handle -> drag?.start(taskRow, handle) ?: false }, { enabled ->
                            store.setEnabled(selected.id, index, enabled)
                            val updated = store.events(selected)
                            val count = (0 until updated.length()).count { updated.getJSONObject(it).getBoolean("enabled") }
                            summary.text = "$count / ${updated.length()} 个任务已启用 · 已保存"
                            toggleAll.text = if (count == updated.length()) "全部禁用" else "全部启用"
                            renderPage(keepScroll = true)
                        }, { showError(it) })
                    labels[taskRow] = taskRow.priority
                    taskList.addView(taskRow, LinearLayout.LayoutParams(-1, -2))
                }
            }
            "日志" -> {
                val filters = LogFilters(this, logQuery, logLevel) { query, level ->
                    logQuery = query; logLevel = level; renderLogs(TaskRuntime.logSnapshot())
                }
                body.item(row().apply {
                    item(filters.search, 1f)
                    followButton = UiTheme.flatButton(this@HomeActivity, "自动滚动") {
                        followLogs = !followLogs; updateLogFollow()
                        renderLogs(TaskRuntime.logSnapshot())
                    }.apply { minHeight = dp(32); minimumHeight = dp(32); textSize = 11f }
                    addView(followButton, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
                    updateLogFollow()
                    addView(ImageButton(this@HomeActivity).apply {
                        setImageResource(R.drawable.ic_fullscreen); setPadding(dp(5), dp(5), dp(5), dp(5))
                        background = UiTheme.background(this@HomeActivity, Color.WHITE, 8).apply { setStroke(dp(1), UiTheme.border) }
                        contentDescription = "全屏查看日志"; tooltipText = contentDescription
                        setOnClickListener { startActivity(Intent(this@HomeActivity, LogActivity::class.java)
                            .putExtra("query", logQuery).putExtra("level", logLevel).putExtra("follow", followLogs)) }
                    }, LinearLayout.LayoutParams(dp(32), dp(32)))
                })
                logOutput = LogOutput(this, padding = 10)
                body.item(row().apply {
                    item(filters.levels, 1f)
                    addView(logOutput.count, LinearLayout.LayoutParams(dp(80), -2))
                })
                body.addView(logOutput, LinearLayout.LayoutParams(-1, 0, 1f))
                renderLogs(TaskRuntime.logSnapshot())
            }
            "设置" -> {
                val panel = settingsPanels.getOrPut(selectedProfile.id) {
                    SettingsPanel(this, selectedProfile.id, restoredSettings?.getBundle(selectedProfile.id))
                }
                (panel.parent as? ViewGroup)?.removeView(panel)
                body.item(panel)
                body.item(settingsCard("配置文件管理") {
                    startActivity(Intent(this, ProfileActivity::class.java))
                })
                body.item(card().apply {
                    val access = ShizukuAccess.state(this@HomeActivity)
                    val authorized = access == ShizukuAccess.State.READY
                    item(row().apply {
                        item(text("应用与权限", 13f).apply { setTypeface(null, Typeface.BOLD) }, 1f)
                        addView(text(access.label, 11f,
                            if (authorized) blue else UiTheme.danger))
                    })
                    item(UiTheme.flatButton(this@HomeActivity, access.action) { requestShizukuAccess() })
                    item(UiTheme.flatButton(this@HomeActivity, "应用与电池设置") {
                        safely { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:$packageName"))) }
                    })
                })
                if (BuildConfig.DEBUG) body.item(settingsCard("开发诊断") {
                    startActivity(Intent().setClassName(this, "$packageName.MainActivity"))
                })
                body.item(settingsCard("关于") {
                    AboutDialog.show(this)
                })
            }
        }
        refreshStatus()
        pageScroll.post {
            if (page != "日志") pageScroll.scrollTo(0, previousScroll)
        }
    }
    private fun settingsCard(title: String, action: () -> Unit): LinearLayout = card().apply {
        setPadding(dp(12), 0, dp(8), 0)
        addView(row().apply {
            addView(text(title, 13f).apply {
                setTypeface(null, Typeface.BOLD); setPadding(0, 0, 0, 0)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(text("›", 22f, muted).apply {
                gravity = Gravity.CENTER; setPadding(0, 0, 0, 0)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(24), -1))
        }, LinearLayout.LayoutParams(-1, dp(44)))
        isClickable = true; isFocusable = true
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22008fd3),
            background(Color.WHITE), null)
        setOnClickListener { safely(action) }
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
        return "${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date((tick * 1000).toLong()))}"
    }
    private fun nextRunState(event: JSONObject, now: Double = System.currentTimeMillis() / 1000.0): Int {
        if (!event.getBoolean("enabled")) return 2
        val remaining = event.optDouble("next_tick", 0.0) - now
        return when { remaining <= 0 -> 0; remaining < 3600 -> 1; else -> 2 }
    }
    private fun nextRunColor(event: JSONObject) = when (nextRunState(event)) {
        0 -> Color.rgb(0, 109, 166)
        1 -> blue
        else -> muted
    }
    private fun refreshHomeTasks(force: Boolean = false) {
        if (page != "主页" || !::homeTasks.isInitialized) return
        try {
            val events = store.events(selectedProfile)
            val now = System.currentTimeMillis() / 1000.0
            val enabled = (0 until events.length()).map { events.getJSONObject(it) }
                .filter { it.getBoolean("enabled") }
                .sortedWith(Comparator { a, b ->
                    TaskOrder.compare(a.optDouble("next_tick", 0.0), a.optDouble("priority"),
                        b.optDouble("next_tick", 0.0), b.optDouble("priority"), now, homeSortByTime)
                })
            // Read BAAS's committed schedule, rather than the last task-progress snapshot.
            val signature = "${selectedProfile.id}:$homeSortByTime:${events.length()}:" +
                enabled.joinToString("|") { "${it}:${nextRunState(it, now)}" }
            if (!force && signature == homeTaskSignature) return
            homeTaskSignature = signature
            homeTaskCount.text = "任务 · ${enabled.size} / ${events.length()} 已启用"
            homeTasks.removeAllViews()
            homeTasks.item(card().apply {
                if (enabled.isEmpty()) {
                    item(text("暂无启用任务", 14f, muted))
                    item(button("前往调度") { page = "调度"; renderPage() })
                }
                enabled.forEachIndexed { index, event ->
                    if (index > 0) addView(View(this@HomeActivity).apply { setBackgroundColor(UiTheme.border) }, LinearLayout.LayoutParams(-1, dp(1)))
                    item(column().apply {
                        item(text(event.getString("event_name"), 14f))
                        item(text("下次执行：${nextRun(event)}", 11f, nextRunColor(event)).apply {
                            if (nextRunState(event, now) == 0) setTypeface(null, Typeface.BOLD)
                        })
                    })
                }
            })
        } catch (_: Exception) {
            // Keep the current list if the core is momentarily rewriting its schedule.
        }
    }
    private fun refreshStatus() {
        if (!::profile.isInitialized) return
        val selected = selectedProfile
        val label = "${selected.name} ▾"
        if (profile.text.toString() != label) profile.text = label
        expandPreview.isEnabled = previewAvailable
        previewGame.isEnabled = !previewGameRunning && !TaskRuntime.isActive && !ManagedMode.enabled && selected.runnable
        closeGame.isEnabled = !TaskRuntime.isActive && previewAvailable
        listOf(previewGame, closeGame, expandPreview).forEach { it.alpha = if (it.isEnabled) 1f else .45f }
        if (page == "设置") settingsPanels[selected.id]?.refreshEnabled()
        if (page == "托管") refreshManagedStatus()
        if (page != "主页") return
        var state = when (TaskRuntime.state) {
            "starting" -> "启动中"; "running" -> "执行中"; "pausing" -> "等待暂停"; "paused" -> "已暂停 · 可手动接管"
            "stopping" -> "正在停止"; "failed" -> "任务失败 · 请查看日志"; "completed" -> "本轮已完成"; else -> "待机"
        }
        val progress = JSONObject(TaskRuntime.snapshot).optJSONObject("progress")
        if (TaskRuntime.state == "running" && progress?.optString("phase") == "idle") state = "等待下次任务"
        val currentTask = progress?.optString("currentTask")?.takeIf { it.isNotBlank() && it != "null" }
        val taskLabel = currentTask?.let { "$state · ${ManualTasks.displayName(it, taskNames)}" } ?: state
        val runningId = progress?.optString("profile")?.substringAfterLast('/')
        val runningName = profileNames[runningId] ?: selected.name
        val next = progress?.optDouble("nextWakeAt", Double.NaN) ?: Double.NaN
        val detail = if (TaskRuntime.isActive) "运行配置：$runningName" +
            (if (next.isFinite()) " · 下次 ${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date((next * 1000).toLong()))}" else "")
            else "当前配置：${selected.name} · ${selected.server}"
        taskDetail.visibility = if (TaskRuntime.isActive) View.VISIBLE else View.GONE
        if (taskDetail.text.toString() != detail) taskDetail.text = detail
        taskStatus.setTextColor(when (TaskRuntime.state) { "failed" -> UiTheme.danger; "running" -> Color.rgb(28, 139, 102); else -> navy })
        if (taskStatus.text.toString() != taskLabel) taskStatus.text = taskLabel
        run.isEnabled = !TaskRuntime.isActive && !ManagedMode.enabled && selected.runnable && !TaskRuntime.isProbeRunning
        stop.isEnabled = TaskRuntime.isActive || ManagedMode.enabled
        listOf(run, stop).forEach { it.alpha = if (it.isEnabled) 1f else .45f }
    }
    private fun refreshManagedStatus() {
        if (!::managedStatus.isInitialized) return
        val value = JSONObject(TaskRuntime.snapshot).optJSONObject("progress")
        val runningId = value?.optString("profile")?.substringAfterLast('/')
        val name = store.list().firstOrNull { it.id == runningId }?.name
        managedStatus.text = if (ManagedMode.enabled && TaskRuntime.isActive) {
            when (TaskRuntime.state) { "paused" -> "托管已暂停"; "pausing" -> "等待暂停"; "stopping" -> "正在停止托管"; else -> "托管执行中" }
        } else when {
            ManagedMode.phase.startsWith("托管停止") -> "托管已停止"
            ManagedMode.phase == "等待手动重试" -> "等待任务"
            else -> ManagedMode.phase
        }
        val next = ManagedMode.nextWakeAt
        managedDetail.text = when {
            ManagedMode.enabled && TaskRuntime.isActive && name != null -> "运行配置：$name"
            ManagedMode.enabled && next > 0 -> "下次执行：${SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(next))}"
            else -> "已选 ${ManagedMode.selectedIds().size} 个配置"
        }
        managedStart.isEnabled = !ManagedMode.enabled && !TaskRuntime.isActive && !TaskRuntime.isProbeRunning
        managedStop.isEnabled = ManagedMode.enabled
        listOf(managedStart, managedStop).forEach { it.alpha = if (it.isEnabled) 1f else .45f }
        refreshManagedHistory()
        managedRetryButtons.values.forEach { retry ->
            retry.isEnabled = ManagedMode.enabled && !TaskRuntime.isActive
            retry.alpha = if (retry.isEnabled) 1f else .45f
        }
    }
    private fun refreshManagedHistory() {
        if (!::managedHistoryTitle.isInitialized) return
        val history = ManagedMode.failureHistory()
        managedHistoryTitle.text = "${history.length()} 条"
        managedHistoryClear.visibility = if (managedHistoryExpanded && history.length() > 0) View.VISIBLE else View.GONE
        managedHistoryBody.visibility = if (managedHistoryExpanded) View.VISIBLE else View.GONE
        val profiles = store.list()
        val states = profiles.associate { it.id to ManagedMode.recovery(it.id) }
        val signature = history.toString() + states.toString()
        if (!managedHistoryExpanded || managedHistorySignature == signature) return
        managedHistorySignature = signature
        managedHistoryBody.removeAllViews()
        if (history.length() > 0) managedHistoryBody.addView(managedHistoryClear,
            LinearLayout.LayoutParams(dp(48), dp(32)).apply { gravity = Gravity.START })
        managedRetryButtons.clear()
        val seen = mutableSetOf<String>()
        if (history.length() == 0) managedHistoryBody.addView(text("暂无记录", 12f, muted))
        else for (index in history.length() - 1 downTo 0) {
            val entry = history.getJSONObject(index)
            val id = entry.optString("profileId").ifBlank { profiles.firstOrNull { it.name == entry.optString("profile") }?.id.orEmpty() }
            val latest = !entry.optBoolean("simulated") && id.isNotEmpty() && seen.add(id)
            val retryable = latest && states[id]?.length()?.let { it > 0 } == true
            val date = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(entry.getLong("time")))
            managedHistoryBody.addView(View(this).apply { setBackgroundColor(UiTheme.border) }, LinearLayout.LayoutParams(-1, dp(1)))
            val record = column().apply {
                setPadding(dp(4), dp(8), dp(4), dp(8))
                background = UiTheme.quietRipple(this@HomeActivity, Color.WHITE, 8)
                addView(row().apply {
                    addView(text(entry.optString("profile"), 12f).apply {
                        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                        setTypeface(null, Typeface.BOLD); setPadding(0, 0, dp(8), 0)
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(text(date, 10f, muted).apply { setPadding(0, 0, 0, 0) })
                })
                val result = entry.optString("result")
                val color = when {
                    result.contains("成功") -> Color.rgb(28, 139, 102)
                    result.contains("挂起") || result.contains("停止") -> UiTheme.danger
                    else -> blue
                }
                addView(row().apply {
                    addView(text(result, 11f, color).apply { setPadding(0, dp(4), 0, dp(2)) }, LinearLayout.LayoutParams(0, -2, 1f))
                    if (retryable) {
                        val retry = UiTheme.flatButton(this@HomeActivity, "立即尝试") {
                            safely { ManagedMode.retryProfile(id); refreshManagedStatus() }
                        }
                        addView(retry, LinearLayout.LayoutParams(dp(84), dp(34)))
                        managedRetryButtons[id] = retry
                    }
                })
                val reason = entry.optString("error").trim().lineSequence().lastOrNull().orEmpty()
                addView(text(reason.take(240), 11f, muted).apply {
                    maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, 0, 0, 0)
                })
                setOnClickListener {
                    val task = entry.optString("task")
                    val phase = when (entry.optString("phase")) {
                        "restoring" -> "恢复游戏"
                        "executing" -> "执行任务"
                        "initializing" -> "初始化"
                        "managed" -> "托管调度"
                        else -> ""
                    }
                    val detail = listOfNotNull(
                        "配置：${entry.optString("profile")}", "时间：$date", "处理：${entry.optString("result")}",
                        phase.takeIf { it.isNotBlank() }?.let { "失败阶段：$it" },
                        task.takeIf { it.isNotBlank() }?.let { "失败时任务：${ManualTasks.displayName(it, ManualTasks.names)}" },
                        entry.optString("context").takeIf { it.isNotBlank() }?.let { "\n错误前的日志：\n$it" },
                        "\n错误详情：\n${entry.optString("error")}").joinToString("\n")
                    UiTheme.dialog(this@HomeActivity).setTitle("错误记录")
                        .setMessage(detail).setPositiveButton("关闭", null)
                        .setNeutralButton("复制") { _, _ ->
                            getSystemService(android.content.ClipboardManager::class.java)
                                .setPrimaryClip(android.content.ClipData.newPlainText("BAAS 错误记录", detail))
                        }.show()
                }
            }
            managedHistoryBody.addView(record, LinearLayout.LayoutParams(-1, -2))
        }
    }
    private fun updateLogFollow() = UiTheme.setToggleSelected(followButton, followLogs)
    private fun renderLogs(value: String) {
        logOutput.follows = followLogs
        logOutput.render(value, logQuery, logLevel)
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
    private fun showError(error: Exception) { UiNotice.show(this, error.message ?: "操作失败", error = true) }
    private fun safely(action: () -> Unit) { try { action() } catch (error: Exception) { showError(error) } }
    override fun onResume() {
        super.onResume(); visible = true
        TaskNotifications.requestPermission(this)
        if (::body.isInitialized) {
            settingsPanels.keys.retainAll(store.list().map { it.id }.toSet())
            settingsPanels[store.selected().id]?.reloadIfClean()
            renderPage()
        }
        scheduleHandler.removeCallbacks(pollSchedule); scheduleHandler.postDelayed(pollSchedule, 2000)
    }
    override fun onPause() {
        visible = false; scheduleHandler.removeCallbacks(pollSchedule)
        super.onPause()
    }
    override fun onDestroy() {
        scheduleHandler.removeCallbacksAndMessages(null)
        TaskRuntime.remove(changed); TaskRuntime.removeLogListener(logsChanged); super.onDestroy()
    }
}
