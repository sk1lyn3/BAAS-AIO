package moe.baas.aio

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.widget.*
import moe.baas.aio.UiTheme.item
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku

/** Explicit WebUI tasks run once; the Home start button still runs a whole profile. */
class ManualTaskActivity : Activity() {
    private val store by lazy { ProfileStore(this) }
    private lateinit var selected: ProfileStore.Profile
    private lateinit var content: LinearLayout
    private lateinit var generic: Switch
    private lateinit var fromMenu: Switch
    private lateinit var stories: NumberInput
    private val actions = mutableListOf<Pair<Button, Boolean>>()
    private var activityInfo: JSONObject? = null
    private val changed: (String) -> Unit = { if (!isDestroyed) updateButtons() }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun text(label: String, size: Float = 14f) = UiTheme.text(this, label, size)
    private fun button(label: String, action: () -> Unit) = UiTheme.button(this, label) { safely(action) }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        selected = store.get(state?.getString("profileId") ?: intent.getStringExtra("profileId") ?: store.selected().id)
        val mode = when (selected.server) { "日服" -> "JP"; "官服" -> "CN"; else -> "Global" }
        activityInfo = assets.open("activities-info.json").bufferedReader().use { JSONObject(it.readText()).optJSONObject(mode) }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(UiTheme.pale)
            val p = UiTheme.dp(this@ManualTaskActivity, 12); setPadding(p, p, p, p)
        }
        content.item(row().apply {
            item(text("专项任务", 20f).apply { setTypeface(null, Typeface.BOLD) }, 1f)
            item(button("返回") { finish() }, 1f)
        })
        content.item(text(selected.name, 12f))
        content.item(button("任务参数") {
            check(!TaskRuntime.isActive) { "请先停止任务再修改参数" }
            val groups = ConfigCatalog(this).groups
            AlertDialog.Builder(this).setItems((0 until groups.length()).map { groups.getJSONObject(it).getString("title") }.toTypedArray()) { _, i ->
                startActivity(Intent(this, ConfigActivity::class.java).putExtra("profileId", selected.id).putExtra("group", i))
            }.show()
        })
        content.item(text("推图与剧情", 16f))
        addTasks(listOf("explore_normal_task", "explore_hard_task", "main_story", "group_story", "mini_story"))
        content.item(text("活动图", 16f))
        if (activityInfo != null) content.item(button("查看活动关卡") { showStages() })
        generic = Switch(this).apply {
            text = "使用通用活动入口"; minHeight = UiTheme.dp(this@ManualTaskActivity, 48)
            isChecked = state?.getBoolean("generic") ?: false
        }; content.item(generic)
        fromMenu = Switch(this).apply {
            text = "从活动菜单开始"; minHeight = UiTheme.dp(this@ManualTaskActivity, 48)
            isChecked = state?.getBoolean("fromMenu") ?: false
        }; content.item(fromMenu)
        val countRow = row()
        countRow.item(text("故事数量"), 1f)
        stories = NumberInput(this, state?.getString("stories") ?: "12", "故事数量", 1.toBigDecimal(), 99.toBigDecimal())
        countRow.item(stories, 1f); content.item(countRow)
        fun showOptions() {
            fromMenu.visibility = if (generic.isChecked) android.view.View.VISIBLE else android.view.View.GONE
            countRow.visibility = fromMenu.visibility
            updateButtons()
        }
        generic.setOnCheckedChangeListener { _, _ -> showOptions() }
        addTasks(listOf("explore_activity_story", "explore_activity_mission", "explore_activity_challenge"))
        content.item(text("其他", 16f)); addTasks(listOf("de_clothes"))
        content.item(button("停止任务") { if (TaskRuntime.isActive) startService(Intent(this, TaskService::class.java).setAction(TaskService.STOP)) })
        setContentView(ScrollView(this).apply { addView(content) }); showOptions(); TaskRuntime.observe(changed)
    }
    private fun addTasks(tasks: List<String>) {
        tasks.chunked(2).forEach { pair ->
            content.item(row().apply {
                pair.forEach { task ->
                    val action = button(ManualTasks.names.getValue(task)) { start(task) }
                    actions += action to task.startsWith("explore_activity_"); item(action, 1f)
                }
            })
        }
    }
    private fun updateButtons() {
        if (!::generic.isInitialized) return
        actions.forEach { (button, activity) ->
            button.isEnabled = selected.runnable && !TaskRuntime.isActive && !ManagedMode.enabled && (!activity || generic.isChecked || activityInfo != null)
        }
    }
    private fun start(task: String) {
        check(!TaskRuntime.isActive && !TaskRuntime.isProbeRunning && !ManagedMode.enabled) { "请等待当前任务结束" }
        check(Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) { "请先启动 Shizuku 并授权" }
        val options = JSONObject()
        if (task.startsWith("explore_activity_")) {
            options.put("activity_use_generic_entry", generic.isChecked)
            if (generic.isChecked) {
                options.put("activity_generic_start_at_menu", fromMenu.isChecked)
                if (task == "explore_activity_story") options.put("activity_generic_total_story", requireNotNull(stories.value) { "请输入有效故事数量" }.toBigDecimal().intValueExact())
            }
        }
        ManualTasks.validate(task, options)
        startForegroundService(Intent(this, TaskService::class.java).setAction(TaskService.START)
            .putExtra("task", "core_task").putExtra("profileId", selected.id).putExtra("manualTask", task).putExtra("taskOptions", options.toString()))
        startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)); finish()
    }
    private fun showStages() {
        val info = requireNotNull(activityInfo); val stages = info.getJSONObject("stages")
        val attributes = mapOf("burst" to "爆发", "pierce" to "贯穿", "mystic" to "神秘", "shock" to "振动")
        val rows = mutableListOf<String>()
        stages.keys().forEach { key ->
            val type = listOf("story", "mission", "challenge").firstOrNull { key.startsWith(it) } ?: return@forEach
            val label = mapOf("story" to "故事", "mission" to "任务", "challenge" to "挑战").getValue(type)
            val value = stages.get(key)
            if (key == type && value is JSONArray) for (i in 0 until value.length()) {
                val attribute = value.optString(i); if (attribute.isNotEmpty()) rows += "$label ${i + 1} · ${attributes[attribute] ?: attribute}"
            } else if (value is JSONObject) {
                val start = value.optJSONArray("start") ?: return@forEach
                val attrs = (0 until start.length()).map { i -> val attr = start.optJSONArray(i)?.optString(0) ?: start.optString(i); attributes[attr] ?: attr }
                rows += "$key · ${attrs.joinToString(" ")}"
            }
        }
        AlertDialog.Builder(this).setTitle(info.getString("name")).setItems(rows.toTypedArray(), null).setPositiveButton("关闭", null).show()
    }
    override fun onResume() { super.onResume(); if (::generic.isInitialized) updateButtons() }
    override fun onSaveInstanceState(state: Bundle) {
        state.putString("profileId", selected.id); state.putBoolean("generic", generic.isChecked)
        state.putBoolean("fromMenu", fromMenu.isChecked); state.putString("stories", stories.text)
        super.onSaveInstanceState(state)
    }
    override fun onDestroy() { TaskRuntime.remove(changed); super.onDestroy() }
    private fun safely(action: () -> Unit) {
        try { action() } catch (error: Exception) { AlertDialog.Builder(this).setMessage(error.message ?: "启动任务失败").setPositiveButton("确定", null).show() }
    }
}
