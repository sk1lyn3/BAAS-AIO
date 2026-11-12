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
            else output.text = "Shizuku permission denied"
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
            pause.text = if (TaskRuntime.state == "paused") "Resume" else "Pause"
            stop.isEnabled = TaskRuntime.isActive
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val page = DebugPage(this, "Task diagnostics")
        configuration = page.actions(page.section("Profile"), "Choose profile" to { chooseProfile() }).single()
        val workflow = page.section("Workflow")
        starters += page.actions(workflow,
            "Run due tasks once" to { selectedTask = "core_once"; pendingStart = true; startTask() },
            "Start managed mode" to { selectedTask = "core_managed"; pendingStart = true; startTask() })
        val probes = page.section("Workflow probes")
        starters += page.actions(probes,
            "Lesson lookup\n(no tickets)" to { selectedTask = "lesson_probe"; pendingStart = true; startTask() },
            "Shop refresh\n(no purchases)" to { selectedTask = "shop_refresh_probe"; pendingStart = true; startTask() })
        starters += page.actions(probes, "Replay mission" to { chooseExploreMission() })
        val controls = page.actions(page.section("Task controls"),
            "Pause" to { command(if (TaskRuntime.state == "paused") TaskService.RESUME else TaskService.PAUSE) },
            "Stop" to { command(TaskService.STOP) },
            "Preview" to { startActivity(Intent(this, PreviewActivity::class.java)) })
        pause = controls[0]; stop = controls[1]
        page.actions(page.section("Tools"),
            "Logs" to { startActivity(Intent(this, LogActivity::class.java)) },
            "Battery settings" to { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
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
        if (TaskRuntime.isProbeRunning) { pendingStart = false; output.text = "Wait for the current probe to finish"; return }
        if (!Shizuku.pingBinder()) { output.text = "Start Shizuku first"; return }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) { Shizuku.requestPermission(104); return }
        pendingStart = false
        startForegroundService(Intent(this, TaskService::class.java).setAction(TaskService.START)
            .putExtra("cycles", intent.getIntExtra("cycles", 0)).putExtra("task", selectedTask)
            .putExtra("taskOptions", probeOptions)
            .putExtra("profileId", selectedProfileId ?: ProfileStore(this).selected().id))
    }
    private fun chooseProfile() {
        val store = ProfileStore(this); val profiles = store.list()
        UiTheme.dialog(this).setTitle("Profile")
            .setItems(profiles.map { it.toString() }.toTypedArray()) { _, index ->
                store.select(profiles[index].id); selectedProfileId = profiles[index].id
                configuration.text = profiles[index].toString()
            }.show()
    }
    private fun chooseExploreMission() {
        val input = UiTheme.input(this).apply { setText("H15-3"); setSingleLine(true) }
        val dialog = UiTheme.dialog(this).setTitle("Replay mission")
            .setMessage("Normal: 24-1 · Hard: H15-3. Replays the full route, including cleared stages, and uses AP.")
            .setView(UiTheme.inputPanel(this, input)).setNegativeButton("Cancel", null)
            .setPositiveButton("Start", null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val mission = input.text.toString().trim().uppercase(java.util.Locale.ROOT)
                if (!mission.matches(Regex("(?:[1-9][0-9]*-[1-5]|H[1-9][0-9]*-[1-3])"))) {
                    input.error = "Enter a normal or hard mission, e.g. 24-1 or H15-3"
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
