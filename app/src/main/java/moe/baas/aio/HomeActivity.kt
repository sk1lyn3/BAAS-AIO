package moe.baas.aio

import android.app.AlertDialog
import android.content.Intent
import android.content.ClipData
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.DragEvent
import android.widget.*
import org.json.JSONObject
import rikka.shizuku.Shizuku
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
    private var page = "主页"
    private lateinit var body: LinearLayout
    private lateinit var pageScroll: ScrollView
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
            if (page == "主页") refreshHomeTasks()
            scheduleHandler.postDelayed(this, 2000)
        }
    }
    private var logQuery = ""
    private var logLevel = 0
    private var followLogs = false
    private lateinit var logCount: TextView
    private lateinit var followButton: Button
    private lateinit var searchLogs: Button
    private lateinit var logScroll: ScrollView
    private lateinit var run: Button
    private lateinit var stop: Button
    private lateinit var expandPreview: Button
    private lateinit var previewGame: Button
    private lateinit var closeGame: Button
    private var previewAvailable = false
    private lateinit var logText: TextView
    private lateinit var previewHint: TextView
    private lateinit var embeddedSurface: SurfaceView
    private val nav = mutableMapOf<String, Button>()
    private val changed: (String) -> Unit = { if (!isDestroyed) refreshStatus() }
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
    override fun onPreviewState(available: Boolean) {
        previewAvailable = available
        if (::closeGame.isInitialized) closeGame.isEnabled = available && !TaskRuntime.isActive
        if (::previewHint.isInitialized) previewHint.visibility = if (available) View.GONE else View.VISIBLE
        if (::embeddedSurface.isInitialized) embeddedSurface.visibility = if (available) View.VISIBLE else View.INVISIBLE
        if (::profile.isInitialized && ::selectedProfile.isInitialized) refreshStatus()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun text(value: String, size: Float = 14f, color: Int = navy) = UiTheme.text(this, value, size, color)
    private fun background(color: Int, radius: Int = 12) = UiTheme.background(this, color, radius)
    private fun button(label: String, primary: Boolean = false, action: () -> Unit) =
        UiTheme.button(this, label, primary) { safely(action) }
    private fun select(options: List<String>, selected: Int, change: (Int) -> Unit) = Spinner(this).apply {
        var current = selected
        adapter = ArrayAdapter(this@HomeActivity, android.R.layout.simple_spinner_dropdown_item, options)
        setSelection(selected); minimumHeight = dp(48)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position != current) { current = position; change(position) }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    private fun LinearLayout.item(view: View, weight: Float = 0f) {
        addView(view, LinearLayout.LayoutParams(if (weight > 0) 0 else -1, -2, weight).apply {
            setMargins(dp(3), dp(2), dp(3), dp(2))
        })
    }
    private fun card() = UiTheme.card(this)
    override fun onCreate(state: Bundle?) {
        store.initialize(); page = state?.getString("page") ?: "主页"
        taskFilter = state?.getString("taskFilter") ?: "全部"; sortByTime = state?.getBoolean("sortByTime") ?: false
        homeSortByTime = state?.getBoolean("homeSortByTime")
            ?: getPreferences(MODE_PRIVATE).getBoolean("homeSortByTime", true)
        logQuery = state?.getString("logQuery") ?: ""; logLevel = state?.getInt("logLevel") ?: 0
        followLogs = state?.getBoolean("followLogs") ?: false
        super.onCreate(state)
        TaskRuntime.observe(changed); TaskRuntime.observeLogs(logsChanged)
    }
    override fun onSaveInstanceState(state: Bundle) {
        state.putString("page", page); state.putString("taskFilter", taskFilter); state.putBoolean("sortByTime", sortByTime)
        state.putBoolean("homeSortByTime", homeSortByTime)
        state.putString("logQuery", logQuery); state.putInt("logLevel", logLevel); state.putBoolean("followLogs", followLogs)
        super.onSaveInstanceState(state)
    }
    override fun buildPreviewLayout(surface: SurfaceView, status: TextView, takeover: Button): View {
        embeddedSurface = surface.apply {
            // This preview has no controls over the image; avoid punching a hole in the UI window.
            setZOrderOnTop(true); visibility = View.INVISIBLE; contentDescription = "游戏预览"
        }
        val root = column().apply { setBackgroundColor(pale); setPadding(dp(12), dp(4), dp(12), dp(8)) }
        root.item(row().apply {
            addView(text("BAAS", 22f).apply { typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD); letterSpacing = .06f }, LinearLayout.LayoutParams(dp(64), -2))
            profile = button("") { chooseProfile() }; item(profile, 1f)
            addView(button("管理") { startActivity(Intent(this@HomeActivity, ProfileActivity::class.java)) }, LinearLayout.LayoutParams(dp(48), -2))
        })
        val preview = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(surface, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
            previewHint = text("游戏未启动", 14f, Color.LTGRAY).apply { gravity = Gravity.CENTER }
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
        root.item(row().apply { item(status, 1f); addView(takeover, LinearLayout.LayoutParams(-2, dp(44))) })
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
        root.addView(pageScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.item(row().apply {
            for (name in listOf("主页", "调度", "日志", "设置")) {
                val tab = button(name) { page = name; renderPage() }
                nav[name] = tab; item(tab, 1f)
            }
        })
        renderPage()
        return root
    }
    private fun chooseProfile() {
        val profiles = store.list()
        AlertDialog.Builder(this).setTitle("选择配置")
            .setItems(profiles.map { it.toString() }.toTypedArray()) { _, i ->
                safely { store.select(profiles[i].id); renderPage() }
            }.show()
    }
    private fun renderPage() {
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
                    stop = button("停止任务") { if (TaskRuntime.isActive) startService(Intent(this@HomeActivity, TaskService::class.java).setAction(TaskService.STOP)) }; item(stop, 1f)
                })
                body.item(controls)
                homeTaskCount = text("", 12f, muted); body.item(homeTaskCount)
                body.item(select(listOf("按下次执行时间", "按任务执行顺序"), if (homeSortByTime) 0 else 1) {
                    homeSortByTime = it == 0
                    getPreferences(MODE_PRIVATE).edit().putBoolean("homeSortByTime", homeSortByTime).apply()
                    refreshHomeTasks(force = true)
                })
                homeTasks = column(); body.item(homeTasks)
                refreshHomeTasks(force = true)
            }
            "调度" -> {
                val enabledCount = (0 until events.length()).count { events.getJSONObject(it).getBoolean("enabled") }
                val summary = text("$enabledCount / ${events.length()} 个任务已启用", 12f, muted)
                body.item(row().apply {
                    item(summary, 1f)
                    addView(button("批量") {
                        AlertDialog.Builder(this@HomeActivity).setItems(arrayOf("全部启用", "全部禁用", "全部立即执行", "全部延后至凌晨四点")) { _, action ->
                            safely {
                                when (action) {
                                    0 -> store.setAllEnabled(selectedProfile.id, true)
                                    1 -> store.setAllEnabled(selectedProfile.id, false)
                                    2 -> store.resetSchedule(selectedProfile.id)
                                    3 -> store.resetSchedule(selectedProfile.id, true)
                                }; renderPage()
                            }
                        }.show()
                    }, LinearLayout.LayoutParams(dp(64), -2))
                })
                body.item(row().apply {
                    item(select(listOf("全部", "已启用", "已禁用"), listOf("全部", "已启用", "已禁用").indexOf(taskFilter)) {
                        taskFilter = listOf("全部", "已启用", "已禁用")[it]; renderPage()
                    }, 1f)
                    item(select(listOf("按任务执行顺序", "按下次执行时间"), if (sortByTime) 1 else 0) { sortByTime = it == 1; renderPage() }, 1f)
                })
                val selected = selectedProfile
                val now = System.currentTimeMillis() / 1000.0
                val indices = (0 until events.length()).filter { index ->
                    val enabled = events.getJSONObject(index).getBoolean("enabled")
                    taskFilter == "全部" || (taskFilter == "已启用") == enabled
                }.sortedWith(Comparator { a, b ->
                    val first = events.getJSONObject(a); val second = events.getJSONObject(b)
                    TaskOrder.compare(first.optDouble("next_tick", 0.0), first.optDouble("priority"),
                        second.optDouble("next_tick", 0.0), second.optDouble("priority"), now, sortByTime)
                })
                if (indices.isEmpty()) body.item(text("此筛选下没有任务", 14f, muted))
                indices.forEach { index ->
                    val event = events.getJSONObject(index)
                    body.item(card().apply {
                        val nextLabel = text(nextRun(event), 11f, nextRunColor(event))
                        item(row().apply {
                        item(CheckBox(this@HomeActivity).apply {
                            text = event.getString("event_name"); textSize = 14f; setTextColor(navy)
                            buttonTintList = android.content.res.ColorStateList.valueOf(blue)
                            minHeight = dp(48); isChecked = event.getBoolean("enabled")
                            setOnCheckedChangeListener { view, enabled ->
                                try {
                                    store.setEnabled(selected.id, index, enabled)
                                    event.put("enabled", enabled); nextLabel.setTextColor(nextRunColor(event))
                                    val updated = store.events(selected)
                                    val count = (0 until updated.length()).count { updated.getJSONObject(it).getBoolean("enabled") }
                                    summary.text = "$count / ${updated.length()} 个任务已启用 · 已保存"
                                } catch (error: Exception) { view.setOnCheckedChangeListener(null); view.isChecked = !enabled; showError(error) }
                            }
                        }, 1f)
                        addView(button("设置") {
                            check(!TaskRuntime.isActive) { "请先停止任务再修改调度" }
                            startActivity(Intent(this@HomeActivity, ScheduleActivity::class.java).putExtra("profileId", selected.id).putExtra("task", event.getString("func_name")))
                        }.apply { isEnabled = !TaskRuntime.isActive }, LinearLayout.LayoutParams(dp(64), -2))
                        if (!sortByTime && taskFilter == "全部") addView(text("↕", 20f, blue).apply {
                            gravity = Gravity.CENTER; contentDescription = "拖动调整${event.getString("event_name")}顺序"
                            isEnabled = !TaskRuntime.isActive
                            setOnLongClickListener { view ->
                                if (TaskRuntime.isActive) false else view.startDragAndDrop(
                                    ClipData.newPlainText("task", event.getString("func_name")), View.DragShadowBuilder(view), event.getString("func_name"), 0)
                            }
                        }, LinearLayout.LayoutParams(dp(44), dp(48)))
                        })
                        item(row().apply {
                            item(nextLabel, 1f)
                            addView(text("#${event.optInt("priority")} · ${formatInterval(event.optDouble("interval"))}", 11f, muted), LinearLayout.LayoutParams(-2, -2))
                        })
                        if (!sortByTime && taskFilter == "全部") setOnDragListener { view, drag ->
                            when (drag.action) {
                                DragEvent.ACTION_DRAG_STARTED -> drag.localState is String && !TaskRuntime.isActive
                                DragEvent.ACTION_DRAG_ENTERED -> { view.background = background(pale); true }
                                DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> { view.background = background(Color.WHITE); true }
                                DragEvent.ACTION_DRAG_LOCATION -> {
                                    val location = IntArray(2); view.getLocationOnScreen(location)
                                    val bounds = android.graphics.Rect(); pageScroll.getGlobalVisibleRect(bounds)
                                    val y = location[1] + drag.y
                                    if (y < bounds.top + dp(48)) pageScroll.smoothScrollBy(0, -dp(48))
                                    else if (y > bounds.bottom - dp(48)) pageScroll.smoothScrollBy(0, dp(48))
                                    true
                                }
                                DragEvent.ACTION_DROP -> {
                                    safely {
                                        val source = drag.localState as String
                                        val ordered = indices.map { events.getJSONObject(it).getString("func_name") }.toMutableList()
                                        val target = ordered.indexOf(event.getString("func_name"))
                                        check(ordered.remove(source)) { "任务列表已变化" }; ordered.add(target, source)
                                        store.reorder(selected.id, ordered); renderPage()
                                    }; true
                                }
                                else -> false
                            }
                        }
                    })
                }
            }
            "日志" -> {
                body.item(row().apply {
                    logCount = text("", 11f, muted); item(logCount, 1f)
                    followButton = button(if (followLogs) "跟随 ✓" else "跟随") {
                        followLogs = !followLogs; followButton.text = if (followLogs) "跟随 ✓" else "跟随"
                        renderLogs(TaskRuntime.logSnapshot())
                    }
                    addView(followButton, LinearLayout.LayoutParams(dp(64), -2))
                    addView(button("全屏") { startActivity(Intent(this@HomeActivity, LogActivity::class.java)) }, LinearLayout.LayoutParams(dp(64), -2))
                })
                body.item(row().apply {
                    searchLogs = button(if (logQuery.isBlank()) "搜索日志" else "搜索：$logQuery") {
                        val input = EditText(this@HomeActivity).apply { setSingleLine(true); setText(logQuery) }
                        AlertDialog.Builder(this@HomeActivity).setTitle("搜索日志").setView(input)
                            .setNegativeButton("取消", null)
                            .setNeutralButton("清除") { _, _ -> logQuery = ""; updateLogSearch() }
                            .setPositiveButton("应用") { _, _ -> logQuery = input.text.toString(); updateLogSearch() }.show()
                    }
                    item(searchLogs, 1f)
                    item(select(listOf("全部级别", "INFO", "WARNING", "ERROR", "CRITICAL"), logLevel) {
                        logLevel = it; renderLogs(TaskRuntime.logSnapshot())
                    }, 1f)
                })
                logText = text("", 12f, Color.rgb(203, 213, 225)).apply { setTextIsSelectable(true); typeface = Typeface.MONOSPACE }
                logScroll = ScrollView(this).apply {
                    background = background(Color.rgb(15, 23, 42)); setPadding(dp(8), dp(8), dp(8), dp(8))
                    addView(logText)
                }
                body.addView(logScroll, LinearLayout.LayoutParams(-1, 0, 1f))
                renderLogs(TaskRuntime.logSnapshot())
            }
            "设置" -> {
                body.item(card().apply {
                    item(button("管理配置文件") { startActivity(Intent(this@HomeActivity, ProfileActivity::class.java)) })
                    item(button("任务参数") {
                        check(!TaskRuntime.isActive) { "请先停止任务再修改参数" }
                        val groups = ConfigCatalog(this@HomeActivity).groups
                        AlertDialog.Builder(this@HomeActivity).setTitle("任务参数")
                            .setItems((0 until groups.length()).map { groups.getJSONObject(it).getString("title") }.toTypedArray()) { _, i ->
                                startActivity(Intent(this@HomeActivity, ConfigActivity::class.java)
                                    .putExtra("profileId", selectedProfile.id).putExtra("group", i))
                            }.show()
                    })
                    item(button("专项任务") {
                        startActivity(Intent(this@HomeActivity, ManualTaskActivity::class.java).putExtra("profileId", selectedProfile.id))
                    })
                })
                body.item(card().apply {
                    val authorized = Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                    item(text(if (authorized) "Shizuku 已授权" else "请启动 Shizuku 并授权", 16f))
                    item(button("应用与电池设置") { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) })
                })
                if (BuildConfig.DEBUG) body.item(button("开发诊断") { startActivity(Intent(this, MainActivity::class.java)) })
            }
        }
        refreshStatus()
        pageScroll.post {
            body.layoutParams = FrameLayout.LayoutParams(-1, if (page == "日志") pageScroll.height else -2)
            pageScroll.scrollTo(0, 0)
        }
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
        previewGame.isEnabled = !TaskRuntime.isActive && selected.runnable
        closeGame.isEnabled = !TaskRuntime.isActive && previewAvailable
        listOf(previewGame, closeGame, expandPreview).forEach { it.alpha = if (it.isEnabled) 1f else .45f }
        if (page != "主页") return
        var state = when (TaskRuntime.state) {
            "starting" -> "启动中"; "running" -> "执行中"; "pausing" -> "等待暂停"; "paused" -> "已暂停 · 可手动接管"
            "stopping" -> "正在停止"; "failed" -> "任务失败 · 请查看日志"; "completed" -> "本轮已完成"; else -> "待机"
        }
        val progress = JSONObject(TaskRuntime.snapshot).optJSONObject("progress")
        if (TaskRuntime.state == "running" && progress?.optString("phase") == "idle") state = "等待下次任务"
        val currentTask = progress?.optString("currentTask")?.takeIf { it.isNotBlank() && it != "null" }
        val taskLabel = currentTask?.let { "$state · ${taskNames[it] ?: it}" } ?: state
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
        run.isEnabled = !TaskRuntime.isActive && selected.runnable && !MainActivity.isProbeRunning
        stop.isEnabled = TaskRuntime.isActive
        listOf(run, stop).forEach { it.alpha = if (it.isEnabled) 1f else .45f }
    }
    private fun updateLogSearch() {
        searchLogs.text = if (logQuery.isBlank()) "搜索日志" else "搜索：$logQuery"
        renderLogs(TaskRuntime.logSnapshot())
    }
    private fun renderLogs(value: String) {
        val result = UiLogs.render(this, value, logQuery, logLevel)
        logCount.text = "共 ${result.total} 条 · 显示 ${result.count} 条"
        logText.text = result.text
        if (followLogs) logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }
    private fun formatInterval(seconds: Double): String {
        if (seconds < 0) return "一次性"
        val value = when { seconds >= 86400 -> seconds / 86400; seconds >= 3600 -> seconds / 3600; else -> seconds / 60 }
        val unit = when { seconds >= 86400 -> "天"; seconds >= 3600 -> "小时"; else -> "分钟" }
        return java.text.DecimalFormat("0.##").format(value) + " " + unit
    }

    private fun startTask(task: String) {
        check(!TaskRuntime.isActive && !MainActivity.isProbeRunning) { "请等待当前任务结束" }
        check(Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) { "请先启动 Shizuku 并授权" }
        val selected = store.selected(); check(selected.runnable) { "此服务器的运行支持尚未接入" }
        startForegroundService(Intent(this, TaskService::class.java).setAction(TaskService.START)
            .putExtra("task", task).putExtra("profileId", selected.id))
    }
    private fun showError(error: Exception) { AlertDialog.Builder(this).setMessage(error.message ?: "操作失败").setPositiveButton("确定", null).show() }
    private fun safely(action: () -> Unit) { try { action() } catch (error: Exception) { showError(error) } }
    override fun onResume() {
        super.onResume(); visible = true
        if (::body.isInitialized) renderPage()
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
