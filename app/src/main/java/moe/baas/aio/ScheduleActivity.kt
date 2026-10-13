package moe.baas.aio

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class ScheduleActivity : PaneActivity() {
    override fun createPage(intent: Intent, close: () -> Unit) = SchedulePage(this, intent, close)
}

class SchedulePage(activity: Activity, intent: Intent, close: () -> Unit) : PanePage(activity, intent, close) {
    private val store by lazy { ProfileStore(this) }
    private lateinit var profileId: String
    private lateinit var name: String
    private lateinit var original: String
    private lateinit var draft: JSONObject
    private lateinit var profileName: String
    private val beijing = TimeZone.getTimeZone("GMT+08:00")
    private val decimal = NumberRules(integer = false)
    /** Former NumberInput views: recreated (reset to the draft) on every render(). */
    private var priority by mutableStateOf("")
    private var interval by mutableStateOf("")
    private var version by mutableIntStateOf(0)
    private fun button(label: String, modifier: Modifier, primary: Boolean = false, action: () -> Unit) =
        @Composable { BButton(label, modifier, primary = primary) { safely(action) } }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        try {
            profileId = state?.getString("profileId") ?: requireNotNull(intent.getStringExtra("profileId"))
            name = state?.getString("name") ?: requireNotNull(intent.getStringExtra("task"))
            val events = store.events(store.get(profileId))
            original = state?.getString("original") ?: (0 until events.length()).map { events.getJSONObject(it) }.first { it.getString("func_name") == name }.toString()
            draft = JSONObject(state?.getString("draft") ?: original)
            profileName = store.get(profileId).name
        } catch (error: Exception) {
            UiTheme.dialog(this).setMessage(error.message).setPositiveButton("返回") { _, _ -> finish() }.show(); return
        }
        show(); render()
    }
    private fun capture() {
        val p = requireNotNull(decimal.value(priority)) { "请输入有效执行顺序" }.toDouble(); val i = requireNotNull(decimal.value(interval)) { "请输入有效执行间隔" }.toDouble()
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
        priority = draft.get("priority").toString(); interval = draft.get("interval").toString(); version++
    }

    @Composable
    override fun Content() {
        version
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().item(), verticalAlignment = Alignment.Top) {
                BText(draft.getString("event_name"), 22f, weight = FontWeight.ExtraBold, modifier = Modifier.weight(1f).item())
                button("返回", Modifier.weight(1f).item()) { leave() }()
            }
            BText(profileName, 12f, modifier = Modifier.fillMaxWidth().item())
            val sc = androidx.compose.foundation.rememberScrollState()
            Column(Modifier.weight(1f).fillMaxWidth().fadingScrollbar(sc).verticalScroll(sc)) { Fields() }
            Row(Modifier.fillMaxWidth().item(), verticalAlignment = Alignment.Top) {
                button("取消", Modifier.weight(1f).item()) { leave() }()
                button("保存", Modifier.weight(1f).item(), primary = true) { capture(); store.saveEvent(profileId, name, original, changes()); finish() }()
            }
        }
    }

    @Composable
    private fun Fields() {
        // Fields() has no parameters, so Compose skips it when only Content() is invalidated. The draft is a plain
        // JSONObject (not snapshot state); read the render counter HERE so every render() re-runs these rows.
        version
        val full = Modifier.fillMaxWidth().item()
        BText("执行顺序", modifier = full)
        NumberField(priority, { priority = it }, "执行顺序", decimal, full)
        BText("执行间隔（秒）", modifier = full)
        Row(full, verticalAlignment = Alignment.Top) {
            NumberField(interval, { interval = it }, "执行间隔", decimal, Modifier.weight(2f).item())
            button("常用", Modifier.weight(1f).item()) {
                val labels = arrayOf("每半小时", "每小时", "每天", "一次性")
                val seconds = intArrayOf(1800, 3600, 86400, -1)
                UiTheme.dialog(this@SchedulePage).setTitle("执行间隔")
                    .setItems(labels) { _, i -> interval = seconds[i].toString() }.show()
            }()
        }
        BText("下次执行（北京时间）", modifier = full)
        Row(full, verticalAlignment = Alignment.Top) {
            val tick = draft.getDouble("next_tick")
            button(if (tick <= 0) "立即" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).apply { timeZone = beijing }.format(Date((tick * 1000).toLong())),
                Modifier.weight(2f).item()) { capture(); chooseDate() }()
            button("立即", Modifier.weight(1f).item()) { capture(); draft.put("next_tick", System.currentTimeMillis() / 1000.0); render() }()
        }
        BText("每日重置（北京时间）", modifier = full)
        val reset = draft.getJSONArray("daily_reset")
        for (i in 0 until reset.length()) Row(full, verticalAlignment = Alignment.Top) {
            val at = shiftHour(reset.getJSONArray(i), 8)
            button(clock(at), Modifier.weight(2f).item()) { capture(); chooseClock(at) { time -> reset.put(i, shiftHour(time, -8)); render() } }()
            button("删除", Modifier.weight(1f).item()) { capture(); reset.remove(i); render() }()
        }
        button("添加重置时间", full) { capture(); chooseClock(JSONArray().put(4).put(0).put(0)) { at -> reset.put(shiftHour(at, -8)); render() } }()
        BText("禁用时段（本地时间）", modifier = full)
        val disabled = draft.getJSONArray("disabled_time_range")
        for (i in 0 until disabled.length()) Row(full, verticalAlignment = Alignment.Top) {
            val range = disabled.getJSONArray(i)
            button("${clock(range.getJSONArray(0))}—${clock(range.getJSONArray(1))}", Modifier.weight(2f).item()) {
                capture(); chooseClock(range.getJSONArray(0)) { start -> chooseClock(range.getJSONArray(1)) { end -> disabled.put(i, JSONArray().put(start).put(end)); render() } }
            }()
            button("删除", Modifier.weight(1f).item()) { capture(); disabled.remove(i); render() }()
        }
        button("添加禁用时段", full) {
            capture(); chooseClock(JSONArray().put(0).put(0).put(0)) { start -> chooseClock(JSONArray().put(1).put(0).put(0)) { end -> disabled.put(JSONArray().put(start).put(end)); render() } }
        }()
        for ((key, label) in listOf("pre_task" to "前置任务", "post_task" to "后置任务")) {
            button("$label · ${draft.getJSONArray(key).length()} 项", full) { capture(); chooseTasks(key, label) }()
        }
        button("执行参数", full) {
            val map = mapOf("cafe_reward" to "咖啡厅", "no1_cafe_invite" to "咖啡厅", "no2_cafe_invite" to "咖啡厅", "lesson" to "日程课（学园）", "arena" to "竞技场", "create" to "制造", "common_shop" to "普通商店", "tactical_challenge_shop" to "战术挑战商店", "total_assault" to "总力战", "joint_firing_drill" to "作战演习（联合火力）", "normal_task" to "扫荡次数", "hard_task" to "扫荡次数", "rewarded_task" to "扫荡次数", "scrimmage" to "扫荡次数", "clear_special_task_power" to "扫荡次数", "activity_sweep" to "扫荡次数")
            val groups = ConfigCatalog(this@SchedulePage).groups
            val group = (0 until groups.length()).firstOrNull { groups.getJSONObject(it).getString("title") == map[name] } ?: 0
            startActivity(Intent(this@SchedulePage, ConfigActivity::class.java).putExtra("profileId", profileId).putExtra("group", group))
        }()
    }
    private fun clock(at: JSONArray) = "%02d:%02d:%02d".format(at.getInt(0), at.getInt(1), at.getInt(2))
    // event.json stores reset clocks in UTC; only the editor converts to Beijing time.
    private fun shiftHour(at: JSONArray, offset: Int) = JSONArray()
        .put((at.getInt(0) + offset + 24) % 24).put(at.getInt(1)).put(at.getInt(2))
    private fun chooseClock(at: JSONArray, choose: (JSONArray) -> Unit) {
        TimePickerDialog(this, { _, hour, minute -> choose(JSONArray().put(hour).put(minute).put(at.getInt(2))) }, at.getInt(0), at.getInt(1), true).also { UiTheme.styleDialog(it) }.show()
    }
    private fun chooseDate() {
        val calendar = Calendar.getInstance(beijing).apply { timeInMillis = maxOf(System.currentTimeMillis(), (draft.getDouble("next_tick") * 1000).toLong()) }
        DatePickerDialog(this, { _, year, month, day ->
            TimePickerDialog(this, { _, hour, minute ->
                calendar.set(year, month, day, hour, minute, 0); calendar.set(Calendar.MILLISECOND, 0)
                draft.put("next_tick", calendar.timeInMillis / 1000.0); render()
            }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true).also { UiTheme.styleDialog(it) }.show()
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).also { UiTheme.styleDialog(it) }.show()
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
    override fun onBackPressed() = leave()
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
