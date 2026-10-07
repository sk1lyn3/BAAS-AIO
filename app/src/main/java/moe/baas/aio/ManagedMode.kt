package moe.baas.aio

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

/** Android owns wake-up times; BAAS still owns task selection and execution. */
object ManagedMode {
    const val TRIGGER = "moe.baas.aio.managed.TRIGGER"
    private lateinit var context: Context
    private val main = Handler(Looper.getMainLooper())
    @Volatile var executionActive = false
    fun attach(value: Context) { context = value.applicationContext; DebugTrace.attach(context.filesDir) }
    private fun prefs() = context.getSharedPreferences("managed", Context.MODE_PRIVATE)
    val enabled get() = BuildConfig.DEBUG && ::context.isInitialized && prefs().getBoolean("enabled", false)
    val generation get() = prefs().getLong("generation", 0)
    val nextWakeAt get() = prefs().getLong("nextWakeAt", 0)
    val phase get() = prefs().getString("phase", "未开启托管")!!
    fun selectedIds(): List<String> {
        val values = JSONArray(prefs().getString("profiles", "[]"))
        return (0 until values.length()).map { values.getString(it) }
    }
    @JvmStatic fun selectedPaths(): String {
        val ids = selectedIds().toSet()
        return JSONArray(ProfileStore(context).list().filter { it.id in ids && it.runnable }
            .map { it.path.absolutePath }).toString()
    }
    @JvmStatic fun sessionToken() = generation
    @Synchronized fun recovery(id: String): JSONObject =
        JSONObject(prefs().getString("recovery", "{}")!!).optJSONObject(id) ?: JSONObject()
    @JvmStatic fun profileRecovery(path: String): String = recovery(java.io.File(path).name).toString()
    @JvmStatic @JvmOverloads fun saveRecovery(path: String, value: String, token: Long, cleared: String = "重试成功"): Boolean {
        synchronized(this) {
            val id = java.io.File(path).name
            if (!enabled || token != generation || id !in selectedIds()) return false
            val states = JSONObject(prefs().getString("recovery", "{}")!!)
            val previous = states.optJSONObject(id) ?: JSONObject()
            val state = JSONObject(value)
            val history = failureHistory()
            if (state.length() != 0 || previous.length() != 0) {
                val name = runCatching { ProfileStore(context).get(id).name }.getOrDefault(id)
                val suspended = state.optInt("failures") >= 2
                val result = when {
                    state.length() == 0 -> cleared
                    suspended -> "重试失败，已挂起"
                    else -> "已暂缓，${retryTime(state)} 重试"
                }
                val entry = JSONObject().put("time", System.currentTimeMillis()).put("profile", name).put("profileId", id)
                    .put("result", result).put("error", state.optString("error", previous.optString("error")))
                    .put("task", state.optString("task", previous.optString("task")))
                    .put("phase", state.optString("phase", previous.optString("phase")))
                    .put("context", state.optString("context", previous.optString("context")))
                val timing = if (state.length() == 0) previous else state
                for (key in listOf("startedAt", "failedAt", "elapsedSeconds", "taskStartedAt", "taskElapsedSeconds")) {
                    if (timing.has(key)) entry.put(key, timing.get(key))
                }
                history.put(entry)
                while (history.length() > 40) history.remove(0)
            }
            if (state.length() == 0) states.remove(id) else states.put(id, state)
            check(prefs().edit().putString("recovery", states.toString()).putString("failureHistory", history.toString()).commit()) { "Cannot save managed recovery state" }
            if (state.length() != 0) {
                val entry = history.getJSONObject(history.length() - 1)
                TaskNotifications.showManagedFailure(context, entry.optString("error"), entry.optString("profile"),
                    entry.optString("result"), id)
            }
            return true
        }
    }
    fun retryProfile(id: String) {
        check(enabled && id in selectedIds()) { "请先开启该配置的托管" }
        check(!TaskRuntime.isActive) { "请等待当前任务结束再重试" }
        saveRecovery(ProfileStore(context).get(id).path.absolutePath, "{}", generation, "手动重试")
        scheduleChanged(context)
    }
    fun recoverySummary(): String {
        val states = selectedIds().map { recovery(it) }
        val delayed = states.count { it.optInt("failures") == 1 }
        val suspended = states.count { it.optInt("failures") >= 2 }
        return listOfNotNull(if (delayed > 0) "$delayed 个暂缓" else null,
            if (suspended > 0) "$suspended 个挂起" else null).joinToString(" · ")
    }
    private fun retryTime(state: JSONObject): String = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date((state.optDouble("retryAt") * 1000).toLong()))
    fun recoveryNotice(): String = selectedIds().mapNotNull { id ->
        val state = recovery(id)
        if (state.length() == 0) null else {
            val name = runCatching { ProfileStore(context).get(id).name }.getOrDefault(id)
            if (state.optInt("failures") >= 2) "$name 已挂起 · 请手动重试"
            else "$name 暂缓 · ${retryTime(state)} 重试"
        }
    }.joinToString("\n")
    @Synchronized fun failureHistory(): JSONArray = JSONArray(prefs().getString("failureHistory", "[]"))
    @Synchronized fun clearFailureHistory() {
        val history = failureHistory()
        val names = ProfileStore(context).list().associate { it.name to it.id }
        val seen = mutableSetOf<String>()
        val retained = mutableListOf<JSONObject>()
        for (index in history.length() - 1 downTo 0) {
            val entry = history.getJSONObject(index)
            if (entry.optBoolean("simulated")) continue
            val id = entry.optString("profileId").ifBlank { names[entry.optString("profile")] ?: "" }
            if (id.isNotEmpty() && seen.add(id) && recovery(id).length() > 0) retained.add(entry)
        }
        check(prefs().edit().putString("failureHistory", JSONArray(retained.reversed()).toString()).commit()) { "Cannot clear managed failure history" }
    }
    @JvmStatic fun isEnabled() = enabled
    @JvmStatic fun setExecuting(value: Boolean) { executionActive = value }
    @JvmStatic fun deviceInteractive(): Boolean =
        context.getSystemService(android.os.PowerManager::class.java).isInteractive
    @JvmStatic fun deviceReady(): Boolean =
        deviceInteractive() &&
            !context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
    @Synchronized fun select(id: String, selected: Boolean) {
        val ids = selectedIds().toMutableList()
        if (selected && id !in ids) ids.add(id)
        if (!selected) {
            ids.remove(id)
            val states = JSONObject(prefs().getString("recovery", "{}")!!).apply { remove(id) }
            prefs().edit().putString("recovery", states.toString()).commit()
        }
        prefs().edit().putString("profiles", JSONArray(ids).toString()).commit()
        scheduleChanged(context)
    }
    fun canSchedule(): Boolean = Build.VERSION.SDK_INT < 31 ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    @Synchronized fun start() {
        check(!enabled && !TaskRuntime.isActive && !TaskRuntime.isProbeRunning) { "请等待当前任务结束" }
        check(selectedIds().isNotEmpty()) { "请先选择托管配置" }
        check(canSchedule()) { "请允许闹钟和提醒权限" }
        prefs().edit().putBoolean("enabled", true).putLong("generation", generation + 1)
            .putString("phase", "准备托管").putString("recovery", "{}").commit()
        DebugTrace.log("Managed enabled generation=$generation profiles=${selectedIds()}")
        kick()
    }
    @Synchronized fun disable(value: Context) {
        attach(value)
        prefs().edit().putBoolean("enabled", false).putLong("generation", generation + 1)
            .putLong("nextWakeAt", 0).putString("phase", "已停止托管").putString("recovery", "{}").commit()
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent())
        TaskNotifications.clearManagedWaiting(context)
        main.removeCallbacks(replan)
        DebugTrace.log("Managed disabled generation=$generation")
    }
    fun stop() {
        disable(context)
        if (TaskRuntime.isActive) context.startService(Intent(context, TaskService::class.java).setAction(TaskService.STOP))
        else TaskRuntime.publish("stopped", "Managed mode stopped")
    }
    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(context, 76,
        Intent(context, ManagedReceiver::class.java).setAction(TRIGGER).putExtra("generation", generation),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun kick() {
        if (!enabled || TaskRuntime.isActive || TaskRuntime.isProbeRunning) return
        TaskNotifications.clearManagedWaiting(context)
        try {
            context.getSystemService(AlarmManager::class.java).cancel(alarmIntent())
            prefs().edit().putLong("nextWakeAt", 0).putString("phase", "准备托管").commit()
            context.startForegroundService(Intent(context, TaskService::class.java).setAction(TaskService.START)
                .putExtra("task", "core_once").putExtra("managed", true).putExtra("managedGeneration", generation))
        } catch (error: Exception) {
            fail(error.message ?: "Cannot start managed service")
        }
    }
    fun fire(value: Context, token: Long) {
        attach(value)
        DebugTrace.log("Managed alarm fired token=$token generation=$generation enabled=$enabled active=${TaskRuntime.isActive}")
        if (!enabled || token != generation) return
        if (TaskRuntime.isActive || TaskRuntime.isProbeRunning) arm(System.currentTimeMillis() + 60_000L)
        else kick()
    }
    fun restore(value: Context) {
        attach(value)
        if (enabled) arm(maxOf(System.currentTimeMillis() + 15_000L, nextWakeAt))
    }
    fun finish(value: Context, token: Long, report: JSONObject) {
        attach(value); executionActive = false
        if (!enabled || token != generation) return
        if (report.optString("state") != "completed") {
            fail(report.optString("error", "Managed task failed; stopped")); return
        }
        val next = report.optDouble("nextWakeAt", Double.NaN)
        if (next.isFinite()) arm(maxOf(System.currentTimeMillis() + 15_000L, (next * 1000).toLong()))
        else {
            context.getSystemService(AlarmManager::class.java).cancel(alarmIntent())
            prefs().edit().putLong("nextWakeAt", 0).putString("phase", if (selectedIds().any { recovery(it).optInt("failures") >= 2 }) "等待手动重试" else "等待启用任务").commit()
            TaskRuntime.publish("waiting", "Managed mode waiting", report.toString())
            TaskNotifications.showManagedWaiting(context, 0, selectedIds().size)
        }
    }
    private fun arm(time: Long) {
        try {
            check(canSchedule()) { "Exact alarm permission is required" }
            val show = PendingIntent.getActivity(context, 76, Intent(context, HomeActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            context.getSystemService(AlarmManager::class.java).setAlarmClock(AlarmManager.AlarmClockInfo(time, show), alarmIntent())
            DebugTrace.log("Managed alarm registered generation=$generation wakeAtMs=$time delayMs=${time - System.currentTimeMillis()}")
            prefs().edit().putLong("nextWakeAt", time).putString("phase", "等待下次任务").commit()
            if (!TaskRuntime.isActive && !TaskRuntime.isProbeRunning) {
                TaskRuntime.publish("waiting", "Managed mode waiting", JSONObject().put("nextWakeAt", time / 1000.0).toString())
                TaskNotifications.showManagedWaiting(context, time, selectedIds().size)
            }
        } catch (error: Exception) { fail(error.message ?: "Cannot register managed alarm") }
    }
    private fun recentRunContext(): String {
        val logs = JSONArray(TaskRuntime.logSnapshot())
        return (0 until logs.length()).map { logs.getJSONObject(it) }
            .filter { it.optInt("level") in 1..2 }.takeLast(10)
            .joinToString("\n") { it.optString("message").take(500) }.takeLast(3000)
    }
    @Synchronized private fun fail(message: String) {
        val history = failureHistory()
        history.put(JSONObject().put("time", System.currentTimeMillis()).put("profile", "托管")
            .put("result", "托管已停止").put("error", message).put("phase", "managed").put("context", recentRunContext()))
        while (history.length() > 40) history.remove(0)
        prefs().edit().putString("failureHistory", history.toString()).commit()
        disable(context)
        val summary = if (message.contains("\n")) "托管停止，请查看日志" else "托管停止：${message.take(160)}"
        prefs().edit().putString("phase", summary).commit()
        TaskRuntime.appendLog(3, message)
        TaskRuntime.publish("failed", message)
        TaskNotifications.showManagedFailure(context, message)
    }
    private val replan = Runnable { kick() }
    fun scheduleChanged(value: Context) {
        attach(value)
        if (!enabled || TaskRuntime.isActive) return
        main.removeCallbacks(replan); main.postDelayed(replan, 500)
    }
}

class ManagedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ManagedMode.TRIGGER) {
            ManagedMode.attach(context)
            if (!ManagedMode.enabled || intent.getLongExtra("generation", -1) != ManagedMode.generation) return
            context.getSystemService(android.os.PowerManager::class.java)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "BAAS:managed-handoff").acquire(10_000L)
            ManagedMode.fire(context, intent.getLongExtra("generation", -1))
        }
        else ManagedMode.restore(context)
    }
}
