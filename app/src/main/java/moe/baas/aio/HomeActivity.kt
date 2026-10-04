package moe.baas.aio

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.widget.*
import org.json.JSONArray
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
    private val blue = Color.rgb(0, 143, 211)
    private val navy = Color.rgb(32, 57, 78)
    private val muted = Color.rgb(101, 122, 139)
    private val pale = Color.rgb(237, 247, 253)
    private var page = "主页"
    private lateinit var body: LinearLayout
    private lateinit var pageScroll: ScrollView
    private lateinit var profile: Button
    private lateinit var taskStatus: TextView
    private lateinit var run: Button
    private lateinit var stop: Button
    private lateinit var previewGame: Button
    private lateinit var logText: TextView
    private lateinit var previewHint: TextView
    private lateinit var embeddedSurface: SurfaceView
    private val nav = mutableMapOf<String, Button>()
    private val changed: (String) -> Unit = { if (!isDestroyed) refreshStatus() }
    private val logsChanged: (String) -> Unit = { if (!isDestroyed && page == "日志") renderLogs(it) }
    override fun previewMessage(message: String) = when (message) {
        "Connecting Companion…" -> "正在连接设备…"
        "Companion disconnected" -> "设备连接已断开"
        "Take control" -> "手动接管"
        "Resume task" -> "继续任务"
        "Resuming…" -> "正在恢复…"
        "Pausing…" -> "等待暂停…"
        "Waiting for task display…" -> "等待任务画面…"
        "Preview idle · Start a game or task" -> "预览待机 · 可启动游戏或任务"
        "Manual control · Resume when finished" -> "已接管 · 操作完成后继续任务"
        "Waiting for the current operation…" -> "等待当前操作结束…"
        "Task running · Take control to interact" -> "任务执行中 · 点手动接管即可操作"
        "Native preview · drag / pinch · Back to close" -> "可直接操作 · 支持滑动和双指缩放"
        else -> message
    }
    override fun onPreviewState(available: Boolean) {
        if (::previewHint.isInitialized) previewHint.visibility = if (available) View.GONE else View.VISIBLE
        if (::embeddedSurface.isInitialized) embeddedSurface.visibility = if (available) View.VISIBLE else View.INVISIBLE
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun text(value: String, size: Float = 14f, color: Int = navy) = TextView(this).apply {
        this.text = value; textSize = size; setTextColor(color); setPadding(dp(4), dp(6), dp(4), dp(6))
    }
    private fun background(color: Int, radius: Int = 12) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun button(label: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 14f; minHeight = dp(44); minimumHeight = dp(44)
        setTextColor(if (primary) Color.WHITE else blue)
        background = background(if (primary) blue else pale, 10)
        setPadding(dp(10), dp(4), dp(10), dp(4))
        setOnClickListener { safely(action) }
    }
    private fun LinearLayout.item(view: View, weight: Float = 0f) {
        addView(view, LinearLayout.LayoutParams(if (weight > 0) 0 else -1, -2, weight).apply {
            setMargins(dp(3), dp(4), dp(3), dp(4))
        })
    }
    private fun card(): LinearLayout = column().apply {
        background = background(Color.WHITE); setPadding(dp(12), dp(8), dp(12), dp(8))
    }
    override fun onCreate(state: Bundle?) {
        store.initialize(); page = state?.getString("page") ?: "主页"
        super.onCreate(state)
        TaskRuntime.observe(changed); TaskRuntime.observeLogs(logsChanged)
    }
    override fun onSaveInstanceState(state: Bundle) { state.putString("page", page); super.onSaveInstanceState(state) }
    override fun buildPreviewLayout(surface: SurfaceView, status: TextView, takeover: Button): View {
        embeddedSurface = surface.apply {
            // This preview has no controls over the image; avoid punching a hole in the UI window.
            setZOrderOnTop(true); visibility = View.INVISIBLE; contentDescription = "游戏预览"
        }
        val root = column().apply { setBackgroundColor(pale); setPadding(dp(12), dp(4), dp(12), dp(8)) }
        root.item(row().apply {
            item(text("BAAS", 24f).apply { setTypeface(null, Typeface.BOLD) }, 1f)
            item(text("蔚蓝档案自动脚本", 12f, muted), 1f)
        })
        val preview = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(surface, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
            previewHint = text("启动游戏或任务后显示画面", 14f, Color.LTGRAY).apply { gravity = Gravity.CENTER }
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
            item(button("展开预览") { startActivity(Intent(this@HomeActivity, PreviewActivity::class.java)) }, 1f)
            previewGame = button("启动游戏") {
                val selected = store.selected()
                val game = when (selected.server) { "国际服" -> "com.nexon.bluearchive"; "国服" -> "com.RoamingStar.BlueArchive"; else -> "com.YostarJP.BlueArchive" }
                launchPreviewGame(game)
            }
            item(previewGame, 1f)
        })
        profile = button("") { chooseProfile() }; root.item(profile)
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
        body.removeAllViews()
        nav.forEach { (name, button) ->
            button.setTextColor(if (name == page) Color.WHITE else blue)
            button.background = background(if (name == page) blue else Color.WHITE, 10)
        }
        when (page) {
            "主页" -> {
                val controls = card()
                taskStatus = text("", 16f).apply { setTypeface(null, Typeface.BOLD) }; controls.item(taskStatus)
                controls.item(text("启动当前配置，按任务调度持续运行，直到主动停止。", 12f, muted))
                controls.item(row().apply {
                    run = button("开始", true) { startTask("core_managed") }; item(run, 1f)
                    stop = button("停止") { if (TaskRuntime.isActive) startService(Intent(this@HomeActivity, TaskService::class.java).setAction(TaskService.STOP)) }; item(stop, 1f)
                })
                body.item(controls)
                body.item(text("任务概览", 17f).apply { setTypeface(null, Typeface.BOLD) })
                val events = store.events(store.selected())
                val enabled = (0 until events.length()).map { events.getJSONObject(it) }.filter { it.getBoolean("enabled") }.sortedBy { it.optInt("priority") }
                body.item(text("已启用 ${enabled.size} / ${events.length()} 项 · 点击调度可调整", 12f, muted))
                enabled.forEach { event -> body.item(card().apply {
                    item(text(event.getString("event_name"), 15f)); item(text(nextRun(event), 12f, muted))
                }) }
            }
            "调度" -> {
                body.item(text("任务调度", 19f).apply { setTypeface(null, Typeface.BOLD) })
                body.item(text("运行中也可调整任务开关；已开始的任务会继续完成。", 12f, muted))
                val selected = store.selected(); val events = store.events(selected)
                (0 until events.length()).sortedBy { events.getJSONObject(it).optInt("priority") }.forEach { index ->
                    val event = events.getJSONObject(index)
                    body.item(card().apply {
                        item(CheckBox(this@HomeActivity).apply {
                            text = event.getString("event_name"); textSize = 15f; setTextColor(navy)
                            isChecked = event.getBoolean("enabled")
                            setOnCheckedChangeListener { view, enabled ->
                                try { store.setEnabled(selected.id, index, enabled) }
                                catch (error: Exception) { view.setOnCheckedChangeListener(null); view.isChecked = !enabled; showError(error) }
                            }
                        })
                        item(text("优先级 ${event.optInt("priority")} · ${nextRun(event)}", 12f, muted))
                    })
                }
            }
            "日志" -> {
                body.item(text("运行日志", 19f).apply { setTypeface(null, Typeface.BOLD) })
                body.item(button("全屏查看日志") { startActivity(Intent(this, LogActivity::class.java)) })
                logText = text("", 12f).apply { setTextIsSelectable(true); typeface = Typeface.MONOSPACE }
                body.item(card().apply { item(logText) }); renderLogs(TaskRuntime.logSnapshot())
            }
            "设置" -> {
                body.item(text("配置与应用", 19f).apply { setTypeface(null, Typeface.BOLD) })
                body.item(card().apply {
                    item(text("配置文件", 16f)); item(text("沿用 BAAS 配置格式，支持导入、导出和复制。", 12f, muted))
                    item(button("管理配置文件") { startActivity(Intent(this@HomeActivity, ProfileActivity::class.java)) })
                })
                body.item(card().apply {
                    val authorized = Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                    item(text(if (authorized) "Shizuku 已授权" else "请启动 Shizuku 并授权", 16f))
                    item(text("后台运行受手机电池策略影响。完全息屏运行暂不支持。", 12f, muted))
                    item(button("应用与电池设置") { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) })
                })
                if (BuildConfig.DEBUG) body.item(button("开发诊断") { startActivity(Intent(this, MainActivity::class.java)) })
            }
        }
        refreshStatus()
        pageScroll.post { pageScroll.scrollTo(0, 0) }
    }
    private fun nextRun(event: JSONObject): String {
        val tick = event.optDouble("next_tick", 0.0)
        if (tick <= System.currentTimeMillis() / 1000.0) return "已到执行时间"
        return "下次 ${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date((tick * 1000).toLong()))}"
    }
    private fun refreshStatus() {
        if (!::profile.isInitialized) return
        val selected = selectedProfile
        val label = "${selected.name} · ${selected.server}  ▾"
        if (profile.text.toString() != label) profile.text = label
        previewGame.isEnabled = !TaskRuntime.isActive
        if (page != "主页") return
        var state = when (TaskRuntime.state) {
            "starting" -> "启动中"; "running" -> "执行中"; "pausing" -> "等待暂停"; "paused" -> "已暂停 · 可手动接管"
            "stopping" -> "正在停止"; "failed" -> "任务失败 · 请查看日志"; "completed" -> "本轮已完成"; else -> "待机"
        }
        val progress = JSONObject(TaskRuntime.snapshot).optJSONObject("progress")
        if (TaskRuntime.state == "running" && progress?.optString("phase") == "idle") state = "等待下次任务"
        val currentTask = progress?.optString("currentTask")?.takeIf { it.isNotBlank() && it != "null" }
        val taskLabel = currentTask?.let { "$state · $it" } ?: state
        if (taskStatus.text.toString() != taskLabel) taskStatus.text = taskLabel
        run.isEnabled = !TaskRuntime.isActive && selected.runnable && !MainActivity.isProbeRunning
        stop.isEnabled = TaskRuntime.isActive
        listOf(run, stop).forEach { it.alpha = if (it.isEnabled) 1f else .45f }
    }
    private fun renderLogs(value: String) {
        var entries = JSONArray(value)
        if (entries.length() == 0) {
            val report = java.io.File(filesDir, "task-report.json")
            if (report.isFile) entries = JSONObject(report.readText()).optJSONArray("log") ?: entries
        }
        logText.text = (0 until entries.length()).joinToString("\n") { i -> entries.optJSONObject(i)?.optString("message") ?: entries.get(i).toString() }
            .ifBlank { "暂无运行日志" }
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
    override fun onResume() { super.onResume(); if (::body.isInitialized) renderPage() }
    override fun onDestroy() { TaskRuntime.remove(changed); TaskRuntime.removeLogListener(logsChanged); super.onDestroy() }
}
