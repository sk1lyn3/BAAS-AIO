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
            ?: if (managed) "托管任务" else "自动任务"
        val tasks = progress.optJSONArray("tasks")
        val completed = (0 until (tasks?.length() ?: 0)).count {
            tasks!!.optJSONObject(it)?.optString("state") == "completed"
        }
        val text = listOfNotNull(info?.first, if (managed) "托管 · $state" else state).joinToString(" · ") +
            if (tasks != null) "\n已完成 $completed 项" else ""
        return label to text
    }
    fun build(content: Pair<String, String>): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "任务运行", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(context, 0, Intent(context, HomeActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(content.first).setContentText(content.second.lineSequence().first())
            .setStyle(Notification.BigTextStyle().bigText(content.second))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setShowWhen(false).setColor(UiTheme.blue)
            .setCategory(Notification.CATEGORY_PROGRESS).setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
    }
}
