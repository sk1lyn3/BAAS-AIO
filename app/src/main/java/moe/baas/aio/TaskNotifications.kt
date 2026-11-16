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
        private const val ERROR_ID = 43
        private const val ERROR_CHANNEL = "workflow_errors"
        private fun nextExecution(context: Context, wakeAt: Long): String =
            context.getString(R.string.notification_next_execution,
                java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(wakeAt)))
        fun showManagedWaiting(context: Context, wakeAt: Long, profileCount: Int) {
            val next = if (wakeAt > 0) nextExecution(context, wakeAt) else ManagedMode.phase
            val content = context.getString(R.string.notification_managed_profiles, profileCount) to
                context.getString(R.string.notification_waiting, next)
            try { context.getSystemService(NotificationManager::class.java).notify(WAITING_ID, TaskNotifications(context).build(content)) }
            catch (error: SecurityException) { android.util.Log.w("BaasWorkflow", "Managed notification permission denied", error) }
        }
        fun showManagedFailure(context: Context, message: String, profile: String? = null,
                               result: String = "托管已停止", tag: String = "managed") {
            val reason = message.trim().lineSequence().lastOrNull { it.isNotBlank() }.orEmpty().take(120)
            val title = profile?.let { context.getString(R.string.notification_profile_failed, it) } ?: context.getString(R.string.managed_stopped)
            val status = if (result.contains("挂起")) context.getString(R.string.notification_suspended) else result.removePrefix("已暂缓，")
            val text = if (profile == null) reason else "$status · $reason"
            try {
                context.getSystemService(NotificationManager::class.java).notify(tag, ERROR_ID,
                    TaskNotifications(context).build(title to text, ongoing = false, error = true))
            } catch (error: SecurityException) {
                android.util.Log.w("BaasWorkflow", "Managed notification permission denied", error)
            }
        }
        fun clearManagedWaiting(context: Context) {
            context.getSystemService(NotificationManager::class.java).cancel(WAITING_ID)
        }
        fun requestPermission(activity: Activity): Boolean {
            if (Build.VERSION.SDK_INT < 33 || activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return false
            val prefs = activity.getSharedPreferences("notifications", Context.MODE_PRIVATE)
            if (prefs.getBoolean("requested", false)) return false
            prefs.edit().putBoolean("requested", true).apply()
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 103)
            return true
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
            "starting" -> context.getString(R.string.status_starting)
            "pausing" -> context.getString(R.string.status_pausing)
            "paused" -> context.getString(R.string.status_paused)
            "stopping" -> context.getString(R.string.status_stopping)
            else -> if (progress.optString("phase") == "idle") context.getString(R.string.status_wait_tasks) else context.getString(R.string.status_executing)
        }
        val path = progress.optString("profile").takeIf { it.isNotBlank() && it != "null" } ?: initialProfile
        val info = path?.let { profile(it) }
        val task = progress.optString("currentTask").takeIf { it.isNotBlank() && it != "null" }
        val label = task?.let { ManualTasks.displayName(it, info?.second ?: emptyMap()) }
            ?: if (value.optString("state") == "starting") context.getString(R.string.status_initializing) else context.getString(R.string.status_wait_tasks)
        val total = progress.optInt("totalTasks", 0)
        val completed = progress.optInt("completedTasks", 0)
        val idle = progress.optString("phase") == "idle"
        val next = progress.optDouble("nextWakeAt", Double.NaN)
        val waiting = if (next.isFinite() && next > 0) context.getString(R.string.notification_wait_next_task, nextExecution(context, (next * 1000).toLong()))
            else context.getString(R.string.status_wait_enabled_tasks)
        val status = (if (idle && state == context.getString(R.string.status_wait_tasks)) waiting else if (state == context.getString(R.string.status_executing) && task != null) label else "$state · $label") +
            if (!idle && total > 0) context.getString(R.string.notification_completed, completed, total) else ""
        val text = status
        return context.getString(R.string.notification_profile_title,
            context.getString(if (managed) R.string.managed_active else R.string.status_running),
            info?.first ?: context.getString(if (managed) R.string.notification_multiple_profiles else R.string.notification_test_task)) to text
    }
    fun build(content: Pair<String, String>, ongoing: Boolean = true, error: Boolean = false): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.notification_channel_workflow), NotificationManager.IMPORTANCE_LOW))
        val channel = if (error) ERROR_CHANNEL else CHANNEL
        if (error) manager.createNotificationChannel(NotificationChannel(ERROR_CHANNEL, context.getString(R.string.notification_channel_errors), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 0, Intent(context, HomeActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return androidx.core.app.NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification).setColor(UiTheme.blue)
            .setLargeIcon(android.graphics.BitmapFactory.decodeResource(context.resources, R.drawable.baas_logo))
            .setContentTitle(content.first).setContentText(content.second.lines().joinToString(" · "))
            .apply { if (content.second.contains("\n")) setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(content.second)) }
            .setContentIntent(open).setOngoing(ongoing).setAutoCancel(error).setOnlyAlertOnce(!error).setSilent(!error)
            .setShowWhen(error)
            .setForegroundServiceBehavior(androidx.core.app.NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
    }
}
