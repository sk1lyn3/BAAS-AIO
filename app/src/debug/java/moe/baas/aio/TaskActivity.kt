package moe.baas.aio

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import rikka.shizuku.Shizuku

class TaskActivity : Activity() {
    override fun attachBaseContext(base: android.content.Context) { super.attachBaseContext(base); Appearance.apply(this, base) }
    private lateinit var output: TextView
    private val starters = mutableListOf<Button>()
    private lateinit var pause: Button
    private lateinit var stop: Button
    private var pendingStart = false
    private var selectedTask = "fixture"
    private var probeOptions = "{}"
    private lateinit var configuration: TextView
    private var selectedProfileId: String? = null
    private val permission = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == 104) {
            if (result == PackageManager.PERMISSION_GRANTED && pendingStart) startTask()
            else output.text = getString(R.string.debug_shizuku_permission_denied)
        }
    }
    private val binder = Shizuku.OnBinderReceivedListener { runOnUiThread { if (pendingStart) startTask() } }
    private val changed: (String) -> Unit = { snapshot ->
        if (!isDestroyed) {
            output.text = runCatching { org.json.JSONObject(snapshot).toString(2) }.getOrDefault(snapshot)
            starters.forEach {
                it.isEnabled = !TaskRuntime.isActive && !TaskRuntime.isProbeRunning
                it.alpha = if (it.isEnabled) 1f else .45f
            }
            pause.isEnabled = TaskRuntime.state in setOf("running", "paused", "pausing")
            pause.text = if (TaskRuntime.state == "paused") getString(R.string.debug_resume) else getString(R.string.debug_pause)
            stop.isEnabled = TaskRuntime.isActive
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val page = DebugPage(this, getString(R.string.debug_task_diagnostics))
        configuration = page.actions(page.section(getString(R.string.debug_profile)), getString(R.string.debug_choose_profile) to { chooseProfile() }).single()
        val workflow = page.section(getString(R.string.debug_workflow))
        starters += page.actions(workflow,
            getString(R.string.debug_run_due_once) to { selectedTask = "core_once"; pendingStart = true; startTask() },
            getString(R.string.debug_start_managed) to { selectedTask = "core_managed"; pendingStart = true; startTask() })
        val probes = page.section(getString(R.string.debug_workflow_probes))
        starters += page.actions(probes,
            getString(R.string.debug_lesson_lookup) to { selectedTask = "lesson_probe"; pendingStart = true; startTask() },
            getString(R.string.debug_shop_refresh) to { selectedTask = "shop_refresh_probe"; pendingStart = true; startTask() })
        starters += page.actions(probes,
            getString(R.string.debug_replay_mission) to { chooseExploreMission() },
            getString(R.string.debug_activity_entry) to { selectedTask = "activity_entry_probe"; pendingStart = true; startTask() })
        val controls = page.actions(page.section(getString(R.string.debug_task_controls)),
            getString(R.string.debug_pause) to { command(if (TaskRuntime.state == "paused") TaskService.RESUME else TaskService.PAUSE) },
            getString(R.string.debug_stop) to { command(TaskService.STOP) },
            getString(R.string.debug_preview) to { startActivity(Intent(this, PreviewActivity::class.java)) })
        pause = controls[0]; stop = controls[1]
        page.actions(page.section(getString(R.string.debug_tools)),
            getString(R.string.debug_logs) to { startActivity(Intent(this, LogActivity::class.java)) },
            getString(R.string.debug_battery_settings) to { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:$packageName"))) })
        output = page.report()
        TaskRuntime.observe(changed)
        Shizuku.addRequestPermissionResultListener(permission)
        Shizuku.addBinderReceivedListenerSticky(binder)
        handleIntent(intent)
    }
    override fun onResume() {
        super.onResume()
        val profile = ProfileStore(this).selected()
        selectedProfileId = profile.id; configuration.text = profile.toString()
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent) }
    private fun handleIntent(intent: Intent) {
        val action = intent.getStringExtra("action")
        // Activity recreation must not replay a completed start/stop command.
        intent.removeExtra("action")
        when (action) {
            "notification_preview" -> {
                val manager = getSystemService(android.app.NotificationManager::class.java)
                val snapshot = intent.getStringExtra("snapshot")
                if (snapshot == null) manager.cancel(43)
                else {
                    val notifications = TaskNotifications(applicationContext)
                    manager.notify(43, notifications.build(notifications.describe(snapshot,
                        intent.getBooleanExtra("managed", false), null)))
                }
            }
            "start" -> { selectedTask = intent.getStringExtra("task") ?: "fixture"
                probeOptions = intent.getStringExtra("taskOptions") ?: "{}"
                val store = ProfileStore(this)
                selectedProfileId = intent.getStringExtra("profileId") ?: store.selected().id
                store.select(requireNotNull(selectedProfileId))
                pendingStart = true; startTask() }
            "pause" -> command(TaskService.PAUSE)
            "resume" -> command(TaskService.RESUME)
            "stop" -> command(TaskService.STOP)
        }
    }
    private fun startTask() {
        if (TaskRuntime.isActive) { pendingStart = false; return }
        if (TaskRuntime.isProbeRunning) { pendingStart = false; output.text = getString(R.string.debug_wait_probe); return }
        if (!Shizuku.pingBinder()) { output.text = getString(R.string.debug_start_shizuku); return }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) { Shizuku.requestPermission(104); return }
        pendingStart = false
        startForegroundService(Intent(this, TaskService::class.java).setAction(TaskService.START)
            .putExtra("cycles", intent.getIntExtra("cycles", 0)).putExtra("task", selectedTask)
            .putExtra("taskOptions", probeOptions)
            .putExtra("profileId", selectedProfileId ?: ProfileStore(this).selected().id))
    }
    private fun chooseProfile() {
        val store = ProfileStore(this); val profiles = store.list()
        UiTheme.dialog(this).setTitle(getString(R.string.debug_profile))
            .setItems(profiles.map { it.toString() }.toTypedArray()) { _, index ->
                store.select(profiles[index].id); selectedProfileId = profiles[index].id
                configuration.text = profiles[index].toString()
            }.show()
    }
    private fun chooseExploreMission() {
        val input = UiTheme.input(this).apply { setText("H15-3"); setSingleLine(true) }
        val dialog = UiTheme.dialog(this).setTitle(getString(R.string.debug_replay_mission))
            .setMessage(getString(R.string.debug_replay_description))
            .setView(UiTheme.inputPanel(this, input)).setNegativeButton(getString(R.string.debug_cancel), null)
            .setPositiveButton(getString(R.string.debug_start), null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val mission = input.text.toString().trim().uppercase(java.util.Locale.ROOT)
                if (!mission.matches(Regex("(?:[1-9][0-9]*-[1-5]|H[1-9][0-9]*-[1-3])"))) {
                    input.error = getString(R.string.debug_invalid_mission)
                } else {
                    probeOptions = org.json.JSONObject().put("mission", mission).toString()
                    selectedTask = "explore_probe"; pendingStart = true
                    dialog.dismiss(); startTask()
                }
            }
        }
        dialog.show()
    }
    private fun command(action: String) { if (TaskRuntime.isActive) startService(Intent(this, TaskService::class.java).setAction(action)) }
    override fun onDestroy() {
        TaskRuntime.remove(changed)
        Shizuku.removeRequestPermissionResultListener(permission)
        Shizuku.removeBinderReceivedListener(binder)
        super.onDestroy()
    }
}
