package moe.baas.aio

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject

/** Presents the existing task snapshot without coupling notifications to the Python core. */
class TaskNotifications(private val context: Context) {
    companion object {
        const val ID = 41
        private const val CHANNEL = "workflow"
        private const val WAITING_ID = 42
        private fun nextExecution(wakeAt: Long): String =
            "下次执行 " + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(wakeAt))
        fun showManagedWaiting(context: Context, wakeAt: Long, profileCount: Int) {
            val next = if (wakeAt > 0) nextExecution(wakeAt) else "等待启用任务"
            val content = "托管中 · $profileCount 个配置" to "等待 · $next"
            try { context.getSystemService(NotificationManager::class.java).notify(WAITING_ID, TaskNotifications(context).build(content)) }
            catch (error: SecurityException) { android.util.Log.w("BaasWorkflow", "Managed notification permission denied", error) }
        }
        fun clearManagedWaiting(context: Context) {
            context.getSystemService(NotificationManager::class.java).cancel(WAITING_ID)
        }
        fun requestPermission(activity: Activity) {
            if (Build.VERSION.SDK_INT < 33 || activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
            val prefs = activity.getSharedPreferences("notifications", Context.MODE_PRIVATE)
            if (prefs.getBoolean("requested", false)) return
            prefs.edit().putBoolean("requested", true).apply()
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 103)
        }
    }
    private val profiles = mutableMapOf<String, Pair<String, Map<String, String>>>()
    private fun profile(path: String): Pair<String, Map<String, String>> = profiles.getOrPut(path) {
        runCatching {
            val store = ProfileStore(context)
            val selected = store.get(path.substringAfterLast('/'))
            val events = store.events(selected)
            selected.name to (ManualTasks.names + (0 until events.length()).associate {
                val event = events.getJSONObject(it)
                event.getString("func_name") to event.getString("event_name")
            })
        }.getOrDefault(path.substringAfterLast('/') to ManualTasks.names)
    }
    fun describe(snapshot: String, managed: Boolean, initialProfile: String?): Pair<String, String> {
        val value = JSONObject(snapshot)
        val progress = value.optJSONObject("progress") ?: JSONObject()
        val state = when (value.optString("state")) {
            "starting" -> "启动中"
            "pausing" -> "等待暂停"
            "paused" -> "已暂停"
            "stopping" -> "正在停止"
            else -> if (progress.optString("phase") == "idle") "等待任务" else "执行中"
        }
        val path = progress.optString("profile").takeIf { it.isNotBlank() && it != "null" } ?: initialProfile
        val info = path?.let { profile(it) }
        val task = progress.optString("currentTask").takeIf { it.isNotBlank() && it != "null" }
        val label = task?.let { info?.second?.get(it) ?: ManualTasks.names[it] ?: it }
            ?: if (value.optString("state") == "starting") "正在初始化" else "等待任务"
        val total = progress.optInt("totalTasks", 0)
        val completed = progress.optInt("completedTasks", 0)
        val idle = progress.optString("phase") == "idle"
        val next = progress.optDouble("nextWakeAt", Double.NaN)
        val waiting = if (next.isFinite() && next > 0) "等待任务 · ${nextExecution((next * 1000).toLong())}"
            else "等待启用任务"
        val text = (if (idle && state == "等待任务") waiting else if (state == "执行中" && task != null) label else "$state · $label") +
            if (!idle && total > 0) "\n已完成 $completed/$total 项" else ""
        return "${if (managed) "托管中" else "运行中"} · ${info?.first ?: if (managed) "多个配置" else "测试任务"}" to text
    }
    fun build(content: Pair<String, String>): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "任务运行", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(context, 0, Intent(context, HomeActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return androidx.core.app.NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setColor(UiTheme.blue)
            .setLargeIcon(android.graphics.BitmapFactory.decodeResource(context.resources, R.drawable.baas_logo))
            .setContentTitle(content.first).setContentText(content.second.lines().joinToString(" · "))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(androidx.core.app.NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
    }
}
