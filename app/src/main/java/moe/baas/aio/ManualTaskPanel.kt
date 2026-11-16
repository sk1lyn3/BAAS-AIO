package moe.baas.aio

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

/** Inline WebUI task controls; execution still uses the existing Python task service. */
class ManualTaskPanel(
    private val host: Activity,
    private val profileId: String,
    private val section: String,
    state: Bundle? = null,
    private val hasUnsavedConfig: () -> Boolean = { false }
) {
    private var selected by mutableStateOf(ProfileStore(host).get(profileId))
    private var activityInfo by mutableStateOf<JSONObject?>(null)
    private var generic by mutableStateOf(state?.getBoolean("generic") ?: false)
    private var fromMenu by mutableStateOf(state?.getBoolean("fromMenu") ?: false)
    private var stories by mutableStateOf(state?.getString("stories") ?: "12")
    private val storyRules = NumberRules(1.toBigDecimal(), 99.toBigDecimal())
    private val tasks: List<List<String>> = when (section) {
        "推图" -> listOf("explore_normal_task", "explore_hard_task").chunked(2)
        "剧情" -> listOf(listOf("main_story"), listOf("group_story", "mini_story"))
        "活动图" -> listOf("explore_activity_story", "explore_activity_mission", "explore_activity_challenge").chunked(2)
        "其他功能" -> listOf(listOf("de_clothes"))
        else -> emptyList()
    }
    init { if (section == "活动图") activityInfo = loadActivity(selected.server) }
    private companion object { @Volatile var activities: JSONObject? = null }
    private fun loadActivity(server: String): JSONObject? {
        val mode = GameServer.mode(server)
        // Parsed once per process: the core is fixed per process and the panel is rebuilt on every page render.
        val all = activities ?: JSONObject(CoreData.read(host, "activities-info.json")).also { activities = it }
        return all.optJSONObject(mode)
    }

    @Composable
    fun Content() {
        LiveTask.snapshot // updateButtons() ran on every task change
        Column(Modifier.fillMaxWidth()) {
            if (section == "活动图") {
                BText(activityInfo?.optString("name") ?: host.getString(R.string.activity_no_data), 13f, modifier = Modifier.item())
                SwitchRow(host.getString(R.string.activity_generic_entry), generic) { generic = it }
                if (generic) {
                    SwitchRow(host.getString(R.string.activity_start_from_menu), fromMenu) { fromMenu = it }
                    Row(Modifier.fillMaxWidth().item(), verticalAlignment = Alignment.CenterVertically) {
                        BText(host.getString(R.string.activity_story_count), 13f, modifier = Modifier.weight(1f))
                        NumberField(stories, { stories = it }, host.getString(R.string.activity_story_count), storyRules, Modifier.size(132.dp, 36.dp))
                    }
                }
            }
            tasks.forEach { pair ->
                Row(Modifier.fillMaxWidth().item(), verticalAlignment = Alignment.CenterVertically) {
                    pair.forEach { task ->
                        val label = when (task) {
                            "explore_activity_story" -> host.getString(R.string.activity_explore_story)
                            "explore_activity_mission" -> host.getString(R.string.activity_explore_mission)
                            "explore_activity_challenge" -> host.getString(R.string.activity_explore_challenge)
                            else -> ManualTasks.names.getValue(task)
                        }
                        val activity = task.startsWith("explore_activity_")
                        val enabled = (section != "其他功能" || selected.server in listOf("官服", "B服")) && selected.runnable && !TaskRuntime.isActive &&
                            !TaskRuntime.isProbeRunning && !ManagedMode.enabled && (!activity || generic || activityInfo != null)
                        BButton(label, Modifier.weight(1f).height(34.dp).padding(end = 4.dp), flat = true, enabled = enabled, disabledAlpha = .45f,
                            icon = painterResource(R.drawable.ic_play)) { safely { start(task) } }
                    }
                }
            }
            if (section == "活动图" && activityInfo != null)
                BButton(host.getString(R.string.activity_view_stages), Modifier.fillMaxWidth().item(), flat = true) { safely { showStages() } }
            if (tasks.isNotEmpty() && TaskRuntime.isActive)
                BButton(host.getString(R.string.action_stop_task), Modifier.fillMaxWidth().item(), flat = true) {
                    safely { if (TaskRuntime.isActive) host.startService(Intent(host, TaskService::class.java).setAction(TaskService.STOP)) }
                }
        }
    }

    @Composable
    private fun SwitchRow(label: String, checked: Boolean, change: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().item(), verticalAlignment = Alignment.CenterVertically) {
            BText(label, 13f, modifier = Modifier.weight(1f))
            CompactSwitch(checked, label, Modifier.size(40.dp, 18.dp)) { change(it) }
        }
    }

    fun refreshProfile() {
        val current = ProfileStore(host).get(profileId)
        if (current.server == selected.server) return
        selected = current
        if (section == "活动图") activityInfo = loadActivity(current.server)
    }
    private fun start(task: String) {
        CoreUpdater.requireCanStart()
        check(task != "de_clothes" || selected.server in listOf("官服", "B服")) { host.getString(R.string.declothes_cn_only) }
        check(!TaskRuntime.isActive && !TaskRuntime.isProbeRunning && !ManagedMode.enabled) { host.getString(R.string.action_wait_task) }
        check(!hasUnsavedConfig()) { host.getString(R.string.config_save_before_task) }
        val access = ShizukuAccess.state(host)
        check(access == ShizukuAccess.State.READY) { host.getString(R.string.shizuku_return_home_action, access.label, access.action) }
        val options = JSONObject()
        if (task.startsWith("explore_activity_")) {
            options.put("activity_use_generic_entry", generic)
            if (generic) {
                options.put("activity_generic_start_at_menu", fromMenu)
                if (task == "explore_activity_story") options.put("activity_generic_total_story", requireNotNull(storyRules.value(stories)) { host.getString(R.string.activity_invalid_story_count) }.toBigDecimal().intValueExact())
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
        val attributes = mapOf("burst" to host.getString(R.string.attribute_burst), "pierce" to host.getString(R.string.attribute_pierce), "mystic" to host.getString(R.string.attribute_mystic), "shock" to host.getString(R.string.attribute_shock))
        val rows = mutableListOf<String>()
        stages.keys().forEach { key ->
            val type = listOf("story", "mission", "challenge").firstOrNull { key.startsWith(it) } ?: return@forEach
            val label = mapOf("story" to host.getString(R.string.activity_story), "mission" to host.getString(R.string.activity_mission), "challenge" to host.getString(R.string.activity_challenge)).getValue(type)
            val value = stages.get(key)
            if (key == type && value is JSONArray) for (i in 0 until value.length()) {
                val attribute = value.optString(i); if (attribute.isNotEmpty()) rows += "$label ${i + 1} · ${attributes[attribute] ?: attribute}"
            } else if (value is JSONObject) {
                val start = value.optJSONArray("start") ?: return@forEach
                val attrs = (0 until start.length()).map { i -> val attr = start.optJSONArray(i)?.optString(0) ?: start.optString(i); attributes[attr] ?: attr }
                rows += "$key · ${attrs.joinToString(" ")}"
            }
        }
        UiTheme.dialog(host).setTitle(info.getString("name")).setItems(rows.toTypedArray(), null).setPositiveButton(host.getString(R.string.action_close), null).show()
    }
    fun saveState() = Bundle().apply {
        if (section == "活动图") {
            putBoolean("generic", generic); putBoolean("fromMenu", fromMenu); putString("stories", stories)
        }
    }
    private fun safely(action: () -> Unit) {
        try { action() } catch (error: Exception) { UiNotice.show(host, error.message ?: host.getString(R.string.task_start_failed), error = true) }
    }
}
