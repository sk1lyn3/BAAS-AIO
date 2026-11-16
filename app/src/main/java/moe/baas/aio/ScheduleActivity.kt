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
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.dp
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
    private val settings by lazy { ConfigCatalog(this).section(name) }
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
            UiTheme.dialog(this).setMessage(error.message).setPositiveButton(getString(R.string.action_back)) { _, _ -> finish() }.show(); return
        }
        show(); render()
    }
    private fun capture() {
        val p = requireNotNull(decimal.value(priority)) { getString(R.string.schedule_invalid_priority) }.toDouble(); val i = requireNotNull(decimal.value(interval)) { getString(R.string.schedule_invalid_interval) }.toDouble()
        require(p.isFinite() && i.isFinite()) { getString(R.string.input_invalid_number) }
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
                button(getString(R.string.action_back), Modifier.weight(1f).item()) { leave() }()
            }
            BText(profileName, 12f, modifier = Modifier.fillMaxWidth().item())
            val sc = androidx.compose.foundation.rememberScrollState()
            Column(Modifier.weight(1f).fillMaxWidth().fadingScrollbar(sc).verticalScroll(sc)) { Fields() }
            Row(Modifier.fillMaxWidth().item(), verticalAlignment = Alignment.Top) {
                button(getString(R.string.action_cancel), Modifier.weight(1f).item()) { leave() }()
                button(getString(R.string.action_save), Modifier.weight(1f).item(), primary = true) { capture(); store.saveEvent(profileId, name, original, changes()); finish() }()
            }
        }
    }

    @Composable
    private fun Fields() {
        // Fields() has no parameters, so Compose skips it when only Content() is invalidated. The draft is a plain
        // JSONObject (not snapshot state); read the render counter HERE so every render() re-runs these rows.
        version
        val full = Modifier.fillMaxWidth().item()
        BText(getString(R.string.schedule_priority), modifier = full)
        NumberField(priority, { priority = it }, getString(R.string.schedule_priority), decimal, full)
        BText(getString(R.string.schedule_interval_seconds), modifier = full)
        Row(full, verticalAlignment = Alignment.CenterVertically) {
            NumberField(interval, { interval = it }, getString(R.string.schedule_interval), decimal, Modifier.weight(2f).item())
            button(getString(R.string.schedule_common_intervals), Modifier.weight(1f).item().height(36.dp)) {
                val labels = arrayOf(getString(R.string.schedule_half_hour), getString(R.string.schedule_hourly), getString(R.string.schedule_daily), getString(R.string.schedule_once))
                val seconds = intArrayOf(1800, 3600, 86400, -1)
                UiTheme.dialog(this@SchedulePage).setTitle(getString(R.string.schedule_interval))
                    .setItems(labels) { _, i -> interval = seconds[i].toString() }.show()
            }()
        }
        BText(getString(R.string.schedule_next_beijing), modifier = full)
        Row(full, verticalAlignment = Alignment.Top) {
            val tick = draft.getDouble("next_tick")
            button(if (tick <= 0) getString(R.string.schedule_immediate) else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).apply { timeZone = beijing }.format(Date((tick * 1000).toLong())),
                Modifier.weight(2f).item()) { capture(); chooseDate() }()
            button(getString(R.string.schedule_immediate), Modifier.weight(1f).item()) { capture(); draft.put("next_tick", System.currentTimeMillis() / 1000.0); render() }()
        }
        BText(getString(R.string.schedule_reset_beijing), modifier = full)
        val reset = draft.getJSONArray("daily_reset")
        for (i in 0 until reset.length()) Row(full, verticalAlignment = Alignment.Top) {
            val at = shiftHour(reset.getJSONArray(i), 8)
            button(clock(at), Modifier.weight(2f).item()) { capture(); chooseClock(at) { time -> reset.put(i, shiftHour(time, -8)); render() } }()
            button(getString(R.string.action_delete), Modifier.weight(1f).item()) { capture(); reset.remove(i); render() }()
        }
        button(getString(R.string.schedule_add_reset), full) { capture(); chooseClock(JSONArray().put(4).put(0).put(0)) { at -> reset.put(shiftHour(at, -8)); render() } }()
        BText(getString(R.string.schedule_disabled_local), modifier = full)
        val disabled = draft.getJSONArray("disabled_time_range")
        for (i in 0 until disabled.length()) Row(full, verticalAlignment = Alignment.Top) {
            val range = disabled.getJSONArray(i)
            button("${clock(range.getJSONArray(0))}—${clock(range.getJSONArray(1))}", Modifier.weight(2f).item()) {
                capture(); chooseClock(range.getJSONArray(0)) { start -> chooseClock(range.getJSONArray(1)) { end -> disabled.put(i, JSONArray().put(start).put(end)); render() } }
            }()
            button(getString(R.string.action_delete), Modifier.weight(1f).item()) { capture(); disabled.remove(i); render() }()
        }
        button(getString(R.string.schedule_add_disabled), full) {
            capture(); chooseClock(JSONArray().put(0).put(0).put(0)) { start -> chooseClock(JSONArray().put(1).put(0).put(0)) { end -> disabled.put(JSONArray().put(start).put(end)); render() } }
        }()
        for ((key, label) in listOf("pre_task" to getString(R.string.schedule_pre_tasks), "post_task" to getString(R.string.schedule_post_tasks))) {
            button(getString(R.string.label_item_count, label, draft.getJSONArray(key).length()), full) { capture(); chooseTasks(key, label) }()
        }
        settings?.let { group ->
            button(getString(R.string.schedule_parameters), full) {
                startActivity(Intent(this@SchedulePage, ConfigActivity::class.java).putExtra("profileId", profileId).putExtra("group", group))
            }()
        }
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
        }.setNegativeButton(getString(R.string.action_cancel), null).setPositiveButton(getString(R.string.action_ok)) { _, _ -> draft.put(key, JSONArray(selected)); render() }.show()
    }
    private fun leave() = safely {
        if (!::draft.isInitialized) { finish(); return@safely }
        val modified = try { capture(); changes().length() > 0 } catch (_: Exception) { true }
        if (!modified) finish()
        else UiTheme.dialog(this).setMessage(getString(R.string.confirm_discard_changes)).setNegativeButton(getString(R.string.action_continue_editing), null).setPositiveButton(getString(R.string.action_discard)) { _, _ -> finish() }.show()
    }
    override fun onBackPressed() = leave()
    override fun onSaveInstanceState(state: Bundle) {
        if (::draft.isInitialized) {
            try { capture() } catch (_: Exception) {}
            state.putString("profileId", profileId); state.putString("name", name); state.putString("original", original); state.putString("draft", draft.toString())
        }; super.onSaveInstanceState(state)
    }
    private fun safely(action: () -> Unit) {
        try { action() } catch (error: Exception) { UiTheme.dialog(this).setMessage(error.message ?: getString(R.string.schedule_failed)).setPositiveButton(getString(R.string.action_ok), null).show() }
    }
}
