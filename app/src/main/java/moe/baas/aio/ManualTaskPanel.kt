package moe.baas.aio

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import moe.baas.aio.UiTheme.item
import org.json.JSONArray
import org.json.JSONObject

/** Inline WebUI task controls; execution still uses the existing Python task service. */
class ManualTaskPanel(
    private val host: Activity,
    private val profileId: String,
    private val section: String,
    state: Bundle? = null,
    private val hasUnsavedConfig: () -> Boolean = { false }
) : LinearLayout(host) {
    private var selected = ProfileStore(host).get(profileId)
    private var activityTitle: TextView? = null
    private var stagesButton: Button? = null
    private lateinit var generic: Switch
    private lateinit var fromMenu: Switch
    private lateinit var stories: NumberInput
    private val actions = mutableListOf<Pair<Button, Boolean>>()
    private lateinit var stop: Button
    private var activityInfo: JSONObject? = null
    private val changed: (String) -> Unit = { host.runOnUiThread { updateButtons() } }
    private fun row() = UiTheme.row(host, Gravity.CENTER_VERTICAL)
    private fun text(label: String, size: Float = 13f) = UiTheme.text(host, label, size)
    private fun button(label: String, action: () -> Unit) = UiTheme.flatButton(host, label) { safely(action) }
    private fun switch(label: String, checked: Boolean): Switch = UiTheme.compactSwitch(host, checked, label).also { control ->
        item(row().apply {
            addView(text(label), LayoutParams(0, -2, 1f))
            addView(control)
        })
    }
    init {
        orientation = VERTICAL
        when (section) {
            "推图" -> addTasks(listOf("explore_normal_task", "explore_hard_task"))
            "剧情" -> addTasks(listOf("main_story", "group_story", "mini_story"), fullFirst = true)
            "活动图" -> {
                val mode = when (selected.server) { "日服" -> "JP"; "官服" -> "CN"; else -> "Global" }
                activityInfo = host.assets.open("activities-info.json").bufferedReader().use { JSONObject(it.readText()).optJSONObject(mode) }
                activityTitle = text(activityInfo?.optString("name") ?: "无活动数据")
                item(activityTitle!!)
                generic = switch("使用通用活动入口", state?.getBoolean("generic") ?: false)
                fromMenu = switch("从活动菜单开始", state?.getBoolean("fromMenu") ?: false)
                val menuRow = fromMenu.parent as View
                val countRow = row()
                countRow.addView(text("故事数量"), LayoutParams(0, -2, 1f))
                stories = NumberInput(host, state?.getString("stories") ?: "12", "故事数量", 1.toBigDecimal(), 99.toBigDecimal())
                countRow.addView(stories, LayoutParams(UiTheme.dp(host, 132), UiTheme.dp(host, 36)))
                item(countRow)
                fun showOptions() {
                    menuRow.visibility = if (generic.isChecked) View.VISIBLE else View.GONE
                    countRow.visibility = menuRow.visibility
                    updateButtons()
                }
                generic.setOnCheckedChangeListener { _, _ -> showOptions() }
                addTasks(listOf("explore_activity_story", "explore_activity_mission", "explore_activity_challenge"))
                stagesButton = button("查看活动关卡") { showStages() }.apply {
                    visibility = if (activityInfo == null) View.GONE else View.VISIBLE
                }
                item(stagesButton!!)
                showOptions()
            }
            "其他功能" -> {
                item(button("一键反和谐（未接入）") { }.apply { isEnabled = false; alpha = .45f })
            }
        }
        if (actions.isNotEmpty()) {
            stop = button("停止任务") { if (TaskRuntime.isActive) host.startService(Intent(host, TaskService::class.java).setAction(TaskService.STOP)) }
            item(stop)
        }
        updateButtons()
    }
    private fun addTasks(tasks: List<String>, fullFirst: Boolean = false) {
        val rows = if (fullFirst) listOf(listOf(tasks.first())) + tasks.drop(1).chunked(2) else tasks.chunked(2)
        rows.forEach { pair ->
            item(row().apply {
                pair.forEach { task ->
                    val label = when (task) {
                        "explore_activity_story" -> "推故事"
                        "explore_activity_mission" -> "推任务"
                        "explore_activity_challenge" -> "推挑战"
                        else -> ManualTasks.names.getValue(task)
                    }
                    val action = button(label) { start(task) }.apply {
                        val icon = host.getDrawable(R.drawable.ic_play)!!.mutate().apply {
                            setTintList(textColors)
                            val size = UiTheme.dp(host, 16)
                            setBounds(0, 0, size, size)
                        }
                        setCompoundDrawablesRelative(icon, null, null, null)
                        compoundDrawablePadding = UiTheme.dp(host, 5)
                    }
                    actions += action to task.startsWith("explore_activity_"); addView(action, LayoutParams(0, UiTheme.dp(host, 34), 1f).apply { marginEnd = UiTheme.dp(host, 4) })
                }
            })
        }
    }
    fun refreshProfile() {
        val current = ProfileStore(host).get(profileId)
        if (current.server == selected.server) return
        selected = current
        if (section == "活动图") {
            val mode = when (current.server) { "日服" -> "JP"; "官服" -> "CN"; else -> "Global" }
            activityInfo = host.assets.open("activities-info.json").bufferedReader().use { JSONObject(it.readText()).optJSONObject(mode) }
            activityTitle?.text = activityInfo?.optString("name") ?: "无活动数据"
            stagesButton?.visibility = if (activityInfo == null) View.GONE else View.VISIBLE
        }
        updateButtons()
    }
    private fun updateButtons() {
        actions.forEach { (button, activity) ->
            button.isEnabled = selected.runnable && !TaskRuntime.isActive && !TaskRuntime.isProbeRunning && !ManagedMode.enabled && (!activity || (::generic.isInitialized && generic.isChecked) || activityInfo != null)
            button.alpha = if (button.isEnabled) 1f else .45f
        }
        if (::stop.isInitialized) stop.visibility = if (TaskRuntime.isActive) View.VISIBLE else View.GONE
    }
    private fun start(task: String) {
        check(!TaskRuntime.isActive && !TaskRuntime.isProbeRunning && !ManagedMode.enabled) { "请等待当前任务结束" }
        check(!hasUnsavedConfig()) { "请先保存配置再启动任务" }
        val access = ShizukuAccess.state(host)
        check(access == ShizukuAccess.State.READY) { "${access.label}，请返回主页点击${access.action}" }
        val options = JSONObject()
        if (task.startsWith("explore_activity_")) {
            options.put("activity_use_generic_entry", generic.isChecked)
            if (generic.isChecked) {
                options.put("activity_generic_start_at_menu", fromMenu.isChecked)
                if (task == "explore_activity_story") options.put("activity_generic_total_story", requireNotNull(stories.value) { "请输入有效故事数量" }.toBigDecimal().intValueExact())
            }
        }
        ManualTasks.validate(task, options)
        host.startForegroundService(Intent(host, TaskService::class.java).setAction(TaskService.START)
            .putExtra("task", "core_task").putExtra("profileId", selected.id).putExtra("manualTask", task).putExtra("taskOptions", options.toString()))
        host.startActivity(Intent(host, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        if (host !is HomeActivity) host.finish()
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
        AlertDialog.Builder(host).setTitle(info.getString("name")).setItems(rows.toTypedArray(), null).setPositiveButton("关闭", null).show()
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); TaskRuntime.observe(changed); updateButtons() }
    override fun onDetachedFromWindow() { TaskRuntime.remove(changed); super.onDetachedFromWindow() }
    fun saveState() = Bundle().apply {
        if (::generic.isInitialized) {
            putBoolean("generic", generic.isChecked); putBoolean("fromMenu", fromMenu.isChecked); putString("stories", stories.text)
        }
    }
    private fun safely(action: () -> Unit) {
        try { action() } catch (error: Exception) { UiNotice.show(host, error.message ?: "启动任务失败", error = true) }
    }
}
