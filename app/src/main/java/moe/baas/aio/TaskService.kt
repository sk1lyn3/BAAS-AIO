package moe.baas.aio

import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.view.InputDevice
import android.view.MotionEvent
import com.chaquo.python.Python
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File
import kotlin.concurrent.thread

/** A user-started task owns its Python worker, OCR session and device descriptors. */
class TaskService : Service() {
    companion object {
        const val START = "moe.baas.aio.task.START"
        const val PAUSE = "moe.baas.aio.task.PAUSE"
        const val RESUME = "moe.baas.aio.task.RESUME"
        const val STOP = "moe.baas.aio.task.STOP"
    }
    private val clientOwner = android.os.Binder()
    private var gate = TaskGate()
    private var broker: ISocketBroker? = null
    private var binding = false
    private var launched = false
    private var managedBatch = false
    private var managedGeneration = 0L
    private var connectionRequested = false
    private var cycles = 0
    private var taskName = "fixture"
    private var profilePath: String? = null
    private var manualTask: String? = null
    private var taskOptions = "{}"
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var destroyed = false
    private var displayDiagnostics: DisplayDiagnostics? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val notifications by lazy { TaskNotifications(this) }
    private var notificationActive = false
    private var notificationRefreshPending = false
    private var notificationContent: Pair<String, String>? = null
    private val refreshNotification = Runnable {
        notificationRefreshPending = false
        if (notificationActive && TaskRuntime.isActive) {
            val content = notifications.describe(TaskRuntime.snapshot, managedBatch, profilePath)
            if (content != notificationContent) {
                try { getSystemService(NotificationManager::class.java).notify(TaskNotifications.ID, notifications.build(content)) }
                catch (error: SecurityException) { android.util.Log.w("BaasWorkflow", "Task notification permission denied", error) }
                notificationContent = content
            }
        }
    }
    private var screenTaskActive = false
    private val notificationListener: (String) -> Unit = {
        val executing = TaskRuntime.needsScreenAwake
        if (screenTaskActive && !executing) {
            try { broker?.finishManagedScreen() }
            catch (error: Exception) { TaskRuntime.appendLog(2, "Cannot release display keepalive: ${error.message}") }
        }
        screenTaskActive = executing
        if (notificationActive && !notificationRefreshPending) {
            notificationRefreshPending = true
            main.postDelayed(refreshNotification, 500)
        }
    }
    private val screenEvents = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            val interactive = getSystemService(android.os.PowerManager::class.java).isInteractive
            val locked = getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
            DebugTrace.log("Screen event=${intent.action} interactive=$interactive keyguardLocked=$locked task=$taskName managed=$managedBatch state=${TaskRuntime.state}")
        }
    }
    private val bindTimeout = Runnable {
        if (binding && !launched) failStart("Shizuku connection timed out")
    }
    private val received = Shizuku.OnBinderReceivedListener { main.post { if (binding && !connectionRequested) connectBroker() } }
    private val activityPulse = object : Runnable {
        override fun run() {
            if (destroyed || !managedBatch) return
            if (ManagedMode.executionActive) try { broker?.managedActivityPulse() }
            catch (error: Exception) { TaskRuntime.appendLog(3, "Managed awake pulse failed: ${error.message}"); gate.requestStop() }
            main.postDelayed(this, 4000)
        }
    }
    private fun connectBroker() {
        if (!Shizuku.pingBinder() || connectionRequested || destroyed) return
        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            failStart("Grant Shizuku permission first"); return
        }
        connectionRequested = true
        try { Shizuku.bindUserService(args, connection) }
        catch (error: Exception) { failStart("Shizuku connection failed: ${error.message}") }
    }
    private val args by lazy { SocketBroker.serviceArgs(this) }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            main.removeCallbacks(bindTimeout)
            if (destroyed) {
                // Do not modify Shizuku's connection set from inside its callback.
                main.post { Shizuku.unbindUserService(args, this, false) }
                return
            }
            binding = false
            if (launched) return
            launched = true; broker = ISocketBroker.Stub.asInterface(binder)
            DebugTrace.log("Shizuku broker connected managed=$managedBatch generation=$managedGeneration")
            if (managedBatch) main.post(activityPulse)
            runTask(checkNotNull(broker))
        }
        override fun onServiceDisconnected(name: ComponentName) { broker = null; if (TaskRuntime.isActive) gate.requestStop() }
    }
    override fun onCreate() {
        super.onCreate(); TaskRuntime.storeIn(filesDir); ManagedMode.attach(this)
        if (BuildConfig.DEBUG) displayDiagnostics = DisplayDiagnostics(this, "TaskService", { broker }) {
            "task=$taskName managed=$managedBatch wakeLockHeld=${wakeLock?.isHeld == true}"
        }.also { it.start() }
        if (BuildConfig.DEBUG) {
            val filter = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
            }
            if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(screenEvents, filter, RECEIVER_NOT_EXPORTED)
            else registerReceiver(screenEvents, filter)
        }
        TaskRuntime.observe(notificationListener)
        Shizuku.addBinderReceivedListenerSticky(received)
    }
    override fun onBind(intent: Intent): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DebugTrace.log("Service command=${intent?.action} startId=$startId task=${intent?.getStringExtra("task")} managed=${intent?.getBooleanExtra("managed", false)}")
        when (intent?.action) {
            START -> if (!TaskRuntime.isActive) synchronized(CoreUpdater) {
                if (!CoreUpdater.canStart && intent.getBooleanExtra("managed", false)) {
                    ManagedMode.restore(this)
                    stopSelf(); return START_NOT_STICKY
                }
                managedBatch = intent.getBooleanExtra("managed", false)
                managedGeneration = intent.getLongExtra("managedGeneration", -1)
                if (managedBatch && (!ManagedMode.enabled || managedGeneration != ManagedMode.generation)) {
                    stopSelf(); return START_NOT_STICKY
                }
                if (!managedBatch && ManagedMode.enabled) { stopSelf(); return START_NOT_STICKY }
                gate = TaskGate(); cycles = intent.getIntExtra("cycles", 0)
                taskName = intent.getStringExtra("task") ?: "core_profile"
                profilePath = null
                manualTask = null; taskOptions = "{}"
                try {
                    CoreUpdater.requireCanStart()
                    val coreTask = taskName in setOf("core_once", "core_managed", "core_profile", "core_task")
                    check(coreTask || (BuildConfig.DEBUG && taskName in setOf("fixture", "jp_login", "jp_cafe", "jp_daily", "jp_managed_batch", "jp_managed_test", "lesson_probe", "shop_refresh_probe"))) {
                        "Unsupported task entry"
                    }
                    if (!managedBatch && (coreTask || taskName in setOf("lesson_probe", "shop_refresh_probe"))) {
                        val store = ProfileStore(this)
                        val profile = intent.getStringExtra("profileId")?.let { store.get(it) } ?: store.selected()
                        check(profile.runnable) { "This server is not supported by the runtime yet" }
                        profilePath = profile.path.absolutePath
                        if (taskName == "core_task") {
                            manualTask = requireNotNull(intent.getStringExtra("manualTask"))
                            taskOptions = intent.getStringExtra("taskOptions") ?: "{}"
                            ManualTasks.validate(requireNotNull(manualTask), JSONObject(taskOptions))
                        }
                    }
                } catch (error: Exception) {
                    TaskRuntime.publish("failed", error.message ?: "Invalid configuration")
                    stopSelf(); return START_NOT_STICKY
                }
                launched = false
                TaskRuntime.publish("starting", "Connecting Shizuku")
                showNotification()
                if (managedBatch || taskName.startsWith("jp_managed") || taskName in setOf("core_managed", "core_profile", "core_task")) {
                    wakeLock = getSystemService(android.os.PowerManager::class.java)
                        .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "BAAS:managed").apply {
                            setReferenceCounted(false); acquire()
                        }
                }
                binding = true
                main.postDelayed(bindTimeout, 15000)
                if (!managedBatch && !Shizuku.pingBinder()) failStart("Start Shizuku first")
                else connectBroker()
            }
            PAUSE -> gate.requestPause()
            RESUME -> {
                // End any paused manual gesture before allowing automatic input again.
                val now = android.os.SystemClock.uptimeMillis()
                val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
                cancel.source = InputDevice.SOURCE_TOUCHSCREEN
                try { broker?.touch(cancel) } catch (_: Exception) { } finally { cancel.recycle() }
                gate.resume()
            }
            STOP -> {
                if (managedBatch) ManagedMode.disable(this)
                gate.requestStop()
                if (!launched) {
                    TaskRuntime.publish("stopped", "Stopped before task startup")
                    stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }
    private fun failStart(message: String) {
        DebugTrace.log("Task startup failed: $message", 3)
        main.removeCallbacks(bindTimeout)
        TaskRuntime.publish("failed", message)
        if (managedBatch) ManagedMode.finish(this, managedGeneration, JSONObject().put("state", "failed").put("error", message))
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    private fun showNotification() {
        val content = notifications.describe(TaskRuntime.snapshot, managedBatch, profilePath)
        startForeground(TaskNotifications.ID, notifications.build(content))
        notificationContent = content
        notificationActive = true
    }
    private fun runtimeState(client: ISocketBroker): String =
        if (!BuildConfig.DEBUG) "{}" else try { client.debugRuntimeState(false) }
        catch (error: Exception) { "Diagnostic unavailable: $error" }

    private fun runTask(client: ISocketBroker) = thread(name = "baas-workflow") {
        var report = try {
            client.attachClient(clientOwner)
            BundledAssets.sync(this, "baas-source.zip", "ocr-resources.zip")
            if (BuildConfig.DEBUG) assets.open("probe-fixtures.zip").use { input ->
                File(filesDir, "probe-fixtures.zip").outputStream().use { input.copyTo(it) }
            }
            CoreUpdater.prepare(this)
            WorkflowOcr(applicationContext).use { ocr ->
                CompanionBridge(client).use { bridge ->
                    val core = taskName in setOf("core_once", "core_managed", "core_profile", "core_task")
                    check(core || applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                        "Unsupported task entry"
                    }
                    val module = when {
                        managedBatch -> "managed_schedule"
                        taskName == "lesson_probe" -> "lesson_probe"
                        taskName == "shop_refresh_probe" -> "shop_refresh_probe"
                        core -> "core_task"
                        taskName.startsWith("jp_managed") -> "managed_task"
                        else -> "workflow_task"
                    }
                    val runtimeArgs = arrayListOf<Any?>(filesDir.absolutePath, bridge, ocr, gate, cycles, taskName, profilePath)
                    if (core) runtimeArgs.addAll(listOf(manualTask, taskOptions))
                    Python.getInstance().getModule(module).callAttr("run", *runtimeArgs.toTypedArray()).toString()
                }
            }
        } catch (error: Throwable) {
            JSONObject().put("state", "failed").put("error", android.util.Log.getStackTraceString(error)).toString(2)
        }
        if (managedBatch) {
            main.removeCallbacks(activityPulse)
            try {
                DebugTrace.log("Managed game/display cleanup requested; " + runtimeState(client))
                client.closePreviewGame()
            }
            catch (error: Exception) {
                val message = "Managed display cleanup failed: ${error.message}"
                TaskRuntime.appendLog(3, message)
                report = JSONObject(report).put("state", "failed").put("error", message).toString()
            }
        }
        run {
            try {
                client.finishManagedScreen()
                DebugTrace.log("Managed screen keepalive stopped; display state left unchanged; " + runtimeState(client))
            } catch (error: Exception) {
                try { client.endManagedScreen(false) } catch (restore: Exception) {
                    TaskRuntime.appendLog(3, "Physical panel recovery failed: ${restore.message}")
                }
                val message = "Screen keepalive cleanup failed: ${error.message}"
                TaskRuntime.appendLog(3, message)
                report = JSONObject(report).put("state", "failed").put("error", message).toString()
            }
        }
        File(filesDir, "task-report.json").writeText(report)
        val result = JSONObject(report)
        DebugTrace.log("Task finished task=$taskName managed=$managedBatch state=${result.optString("state")} error=${result.optString("error")}")
        android.util.Log.i("BaasWorkflow", report)
        main.post {
            TaskRuntime.publish(result.getString("state"), "Task finished", report)
            if (managedBatch) ManagedMode.finish(this, managedGeneration, result)
            wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
    }
    override fun onDestroy() {
        destroyed = true
        notificationActive = false
        main.removeCallbacks(refreshNotification)
        TaskRuntime.remove(notificationListener)
        displayDiagnostics?.stop(); displayDiagnostics = null
        if (BuildConfig.DEBUG) unregisterReceiver(screenEvents)
        DebugTrace.log("Task service destroyed state=${TaskRuntime.state}")
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        main.removeCallbacks(bindTimeout); main.removeCallbacks(activityPulse)
        Shizuku.removeBinderReceivedListener(received)
        if (TaskRuntime.isActive) gate.requestStop()
        if (broker != null || connectionRequested) Shizuku.unbindUserService(args, connection, false)
        super.onDestroy()
    }
}
