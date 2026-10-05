package moe.baas.aio

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.widget.*
import moe.baas.aio.UiTheme.item
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ScheduleActivity : Activity() {
    private val store by lazy { ProfileStore(this) }
    private lateinit var profileId: String
    private lateinit var name: String
    private lateinit var original: String
    private lateinit var draft: JSONObject
    private lateinit var fields: LinearLayout
    private lateinit var priority: NumberInput
    private lateinit var interval: NumberInput
    private fun column() = UiTheme.column(this)
    private fun row() = UiTheme.row(this)
    private fun text(label: String, size: Float = 14f) = UiTheme.text(this, label, size)
    private fun button(label: String, action: () -> Unit) = UiTheme.button(this, label) { safely(action) }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        try {
            profileId = state?.getString("profileId") ?: requireNotNull(intent.getStringExtra("profileId"))
            name = state?.getString("name") ?: requireNotNull(intent.getStringExtra("task"))
            val events = store.events(store.get(profileId))
            original = state?.getString("original") ?: (0 until events.length()).map { events.getJSONObject(it) }.first { it.getString("func_name") == name }.toString()
            draft = JSONObject(state?.getString("draft") ?: original)
        } catch (error: Exception) {
            UiTheme.dialog(this).setMessage(error.message).setPositiveButton("返回") { _, _ -> finish() }.show(); return
        }
        val root = UiTheme.page(this)
        root.item(row().apply {
            item(text(draft.getString("event_name"), 20f).apply { setTypeface(null, Typeface.BOLD) }, 1f)
            item(button("返回") { leave() }, 1f)
        })
        root.item(text(store.get(profileId).name, 12f))
        fields = column()
        root.addView(ScrollView(this).apply { addView(fields) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.item(row().apply {
            item(button("取消") { leave() }, 1f)
            item(UiTheme.button(this@ScheduleActivity, "保存", true) { safely { capture(); store.saveEvent(profileId, name, original, changes()); finish() } }, 1f)
        })
        UiTheme.setContentView(this, root); render()
    }
    private fun number(value: Any, label: String) = NumberInput(this, value.toString(), label, integer = false)
    private fun capture() {
        val p = requireNotNull(priority.value) { "请输入有效执行顺序" }.toDouble(); val i = requireNotNull(interval.value) { "请输入有效执行间隔" }.toDouble()
        require(p.isFinite() && i.isFinite()) { "请输入有效数字" }
        if (p != draft.getDouble("priority")) draft.put("priority", p)
        if (i != draft.getDouble("interval")) draft.put("interval", i)
    }
    private fun changes() = JSONObject().apply {
        val before = JSONObject(original)
        for (key in listOf("priority", "interval", "next_tick", "daily_reset", "disabled_time_range", "pre_task", "post_task")) {
            if (draft.get(key).toString() != before.get(key).toString()) put(key, draft.get(key))
        }
    }
    private fun render() {
        fields.removeAllViews()
        fields.item(text("执行顺序")); priority = number(draft.get("priority"), "执行顺序"); fields.item(priority)
        fields.item(text("执行间隔（秒）")); interval = number(draft.get("interval"), "执行间隔")
        fields.item(row().apply {
            item(interval, 2f)
            item(button("常用") {
                val labels = arrayOf("每半小时", "每小时", "每天", "一次性")
                val seconds = intArrayOf(1800, 3600, 86400, -1)
                UiTheme.dialog(this@ScheduleActivity).setTitle("执行间隔")
                    .setItems(labels) { _, i -> interval.setValue(seconds[i].toString()) }.show()
            }, 1f)
        })
        fields.item(text("下次执行"))
        fields.item(row().apply {
            val tick = draft.getDouble("next_tick")
            item(button(if (tick <= 0) "立即" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date((tick * 1000).toLong()))) { capture(); chooseDate() }, 2f)
            item(button("立即") { capture(); draft.put("next_tick", System.currentTimeMillis() / 1000.0); render() }, 1f)
        })
        fields.item(text("每日重置（UTC）"))
        val reset = draft.getJSONArray("daily_reset")
        for (i in 0 until reset.length()) fields.item(row().apply {
            val at = reset.getJSONArray(i)
            item(button(clock(at)) { capture(); chooseClock(at) { time -> reset.put(i, time); render() } }, 2f)
            item(button("删除") { capture(); reset.remove(i); render() }, 1f)
        })
        fields.item(button("添加重置时间") { capture(); chooseClock(JSONArray().put(4).put(0).put(0)) { at -> reset.put(at); render() } })
        fields.item(text("禁用时段（本地时间）"))
        val disabled = draft.getJSONArray("disabled_time_range")
        for (i in 0 until disabled.length()) fields.item(row().apply {
            val range = disabled.getJSONArray(i)
            item(button("${clock(range.getJSONArray(0))}—${clock(range.getJSONArray(1))}") {
                capture(); chooseClock(range.getJSONArray(0)) { start -> chooseClock(range.getJSONArray(1)) { end -> disabled.put(i, JSONArray().put(start).put(end)); render() } }
            }, 2f)
            item(button("删除") { capture(); disabled.remove(i); render() }, 1f)
        })
        fields.item(button("添加禁用时段") {
            capture(); chooseClock(JSONArray().put(0).put(0).put(0)) { start -> chooseClock(JSONArray().put(1).put(0).put(0)) { end -> disabled.put(JSONArray().put(start).put(end)); render() } }
        })
        for ((key, label) in listOf("pre_task" to "前置任务", "post_task" to "后置任务")) {
            fields.item(button("$label · ${draft.getJSONArray(key).length()} 项") { capture(); chooseTasks(key, label) })
        }
        fields.item(button("执行参数") {
            val map = mapOf("cafe_reward" to "咖啡厅", "no1_cafe_invite" to "咖啡厅", "no2_cafe_invite" to "咖啡厅", "lesson" to "日程课（学园）", "arena" to "竞技场", "create" to "制造", "common_shop" to "普通商店", "tactical_challenge_shop" to "战术挑战商店", "total_assault" to "总力战", "joint_firing_drill" to "作战演习（联合火力）", "normal_task" to "关卡推图与剧情", "hard_task" to "关卡推图与剧情", "rewarded_task" to "扫荡次数", "scrimmage" to "扫荡次数", "clear_special_task_power" to "扫荡次数", "activity_sweep" to "扫荡次数")
            val groups = ConfigCatalog(this@ScheduleActivity).groups
            val group = (0 until groups.length()).firstOrNull { groups.getJSONObject(it).getString("title") == map[name] } ?: 0
            startActivity(Intent(this@ScheduleActivity, ConfigActivity::class.java).putExtra("profileId", profileId).putExtra("group", group))
        })
    }
    private fun clock(at: JSONArray) = "%02d:%02d:%02d".format(at.getInt(0), at.getInt(1), at.getInt(2))
    private fun chooseClock(at: JSONArray, choose: (JSONArray) -> Unit) {
        TimePickerDialog(this, { _, hour, minute -> choose(JSONArray().put(hour).put(minute).put(at.getInt(2))) }, at.getInt(0), at.getInt(1), true).show()
    }
    private fun chooseDate() {
        val calendar = Calendar.getInstance().apply { timeInMillis = maxOf(System.currentTimeMillis(), (draft.getDouble("next_tick") * 1000).toLong()) }
        DatePickerDialog(this, { _, year, month, day ->
            TimePickerDialog(this, { _, hour, minute ->
                calendar.set(year, month, day, hour, minute, 0); calendar.set(Calendar.MILLISECOND, 0)
                draft.put("next_tick", calendar.timeInMillis / 1000.0); render()
            }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true).show()
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
    }
    private fun chooseTasks(key: String, label: String) {
        val events = store.events(store.get(profileId))
        val tasks = (0 until events.length()).map { events.getJSONObject(it) }.filter { it.getString("func_name") != name }
        val previous = draft.getJSONArray(key)
        val selected = (0 until previous.length()).map { previous.getString(it) }.toMutableList()
        val checks = BooleanArray(tasks.size) { tasks[it].getString("func_name") in selected }
        UiTheme.dialog(this).setTitle(label).setMultiChoiceItems(tasks.map { it.getString("event_name") }.toTypedArray(), checks) { _, i, checked ->
            val task = tasks[i].getString("func_name"); if (checked) { if (task !in selected) selected += task } else selected.remove(task)
        }.setNegativeButton("取消", null).setPositiveButton("确定") { _, _ -> draft.put(key, JSONArray(selected)); render() }.show()
    }
    private fun leave() = safely {
        if (!::draft.isInitialized) { finish(); return@safely }
        val modified = try { capture(); changes().length() > 0 } catch (_: Exception) { true }
        if (!modified) finish()
        else UiTheme.dialog(this).setMessage("放弃未保存的修改？").setNegativeButton("继续编辑", null).setPositiveButton("放弃") { _, _ -> finish() }.show()
    }
    @Deprecated("Platform back API") override fun onBackPressed() = leave()
    override fun onSaveInstanceState(state: Bundle) {
        if (::draft.isInitialized) {
            try { capture() } catch (_: Exception) {}
            state.putString("profileId", profileId); state.putString("name", name); state.putString("original", original); state.putString("draft", draft.toString())
        }; super.onSaveInstanceState(state)
    }
    private fun safely(action: () -> Unit) {
        try { action() } catch (error: Exception) { UiTheme.dialog(this).setMessage(error.message ?: "调度设置失败").setPositiveButton("确定", null).show() }
    }
}
