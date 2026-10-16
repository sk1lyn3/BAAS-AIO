package moe.baas.aio

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku

class TaskActivity : Activity() {
    private lateinit var output: TextView
    private lateinit var start: Button
    private lateinit var pause: Button
    private lateinit var stop: Button
    private var pendingStart = false
    private var selectedTask = "fixture"
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
            output.text = "BAAS core / managed mode\nAllow background activity in App info → Battery settings.\n\n$snapshot"
            start.isEnabled = !TaskRuntime.isActive
            pause.isEnabled = TaskRuntime.state in setOf("running", "paused", "pausing")
            pause.text = if (TaskRuntime.state == "paused") "Resume" else "Pause"
            stop.isEnabled = TaskRuntime.isActive
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        configuration = TextView(this).apply {
            textSize = 18f
            setOnClickListener { startActivity(Intent(this@TaskActivity, ProfileActivity::class.java)) }
        }
        output = TextView(this).apply { textSize = 15f }
        start = Button(this).apply { text = "Run due tasks once"; setOnClickListener { selectedTask = "core_once"; pendingStart = true; startTask() } }
        pause = Button(this).apply { text = "Pause"; setOnClickListener { command(if (TaskRuntime.state == "paused") TaskService.RESUME else TaskService.PAUSE) } }
        stop = Button(this).apply { text = "Stop"; setOnClickListener { command(TaskService.STOP) } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(configuration)
            addView(Button(this@TaskActivity).apply {
                text = "Verify lesson students (no tickets used)"
                setOnClickListener { selectedTask = "lesson_probe"; pendingStart = true; startTask() }
            })
            addView(Button(this@TaskActivity).apply {
                text = "Verify shop refresh (no purchases)"
                setOnClickListener { selectedTask = "shop_refresh_probe"; pendingStart = true; startTask() }
            })
            addView(Button(this@TaskActivity).apply {
                text = "Choose configuration"
                setOnClickListener {
                    val store = ProfileStore(this@TaskActivity); val profiles = store.list()
                    android.app.AlertDialog.Builder(this@TaskActivity).setTitle("Configuration")
                        .setItems(profiles.map { it.toString() }.toTypedArray()) { _, index ->
                            store.select(profiles[index].id); selectedProfileId = profiles[index].id
                            configuration.text = "Configuration: ${profiles[index]}"
                        }.show()
                }
            })
            addView(start); addView(pause); addView(stop)
            addView(Button(this@TaskActivity).apply {
                text = "Start managed mode"
                setOnClickListener { selectedTask = "core_managed"; pendingStart = true; startTask() }
            })
            addView(Button(this@TaskActivity).apply {
                text = "Logs"
                setOnClickListener { startActivity(Intent(this@TaskActivity, LogActivity::class.java)) }
            })
            addView(Button(this@TaskActivity).apply {
                text = "Background / battery settings"
                setOnClickListener { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:$packageName"))) }
            })
            addView(Button(this@TaskActivity).apply { text = "Virtual display preview"; setOnClickListener { startActivity(Intent(this@TaskActivity, PreviewActivity::class.java)) } })
            addView(ScrollView(this@TaskActivity).apply { addView(output) }, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        TaskRuntime.observe(changed)
        Shizuku.addRequestPermissionResultListener(permission)
        Shizuku.addBinderReceivedListenerSticky(binder)
        handleIntent(intent)
    }
    override fun onResume() {
        super.onResume()
        val profile = ProfileStore(this).selected()
        selectedProfileId = profile.id; configuration.text = "Configuration: $profile"
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
            .putExtra("profileId", selectedProfileId ?: ProfileStore(this).selected().id))
    }
    private fun command(action: String) { if (TaskRuntime.isActive) startService(Intent(this, TaskService::class.java).setAction(action)) }
    override fun onDestroy() {
        TaskRuntime.remove(changed)
        Shizuku.removeRequestPermissionResultListener(permission)
        Shizuku.removeBinderReceivedListener(binder)
        super.onDestroy()
    }
}
