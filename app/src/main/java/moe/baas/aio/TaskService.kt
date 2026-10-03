package moe.baas.aio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.view.InputDevice
import android.view.MotionEvent
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
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
    private var cycles = 0
    private var destroyed = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val bindTimeout = Runnable {
        if (binding && !launched) failStart("Shizuku connection timed out")
    }
    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(this, SocketBroker::class.java)).daemon(false)
            .processNameSuffix("aio-broker").debuggable(true).version(SocketBroker.SERVICE_VERSION).tag("baas-aio-broker")
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            main.removeCallbacks(bindTimeout)
            if (destroyed) { Shizuku.unbindUserService(args, this, false); return }
            binding = false
            if (launched) return
            launched = true; broker = ISocketBroker.Stub.asInterface(binder)
            runTask(checkNotNull(broker))
        }
        override fun onServiceDisconnected(name: ComponentName) { broker = null; if (TaskRuntime.isActive) gate.requestStop() }
    }
    override fun onCreate() { super.onCreate(); TaskRuntime.storeIn(filesDir) }
    override fun onBind(intent: Intent): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> if (!TaskRuntime.isActive) {
                gate = TaskGate(); cycles = intent.getIntExtra("cycles", 0)
                launched = false
                TaskRuntime.publish("starting", "Connecting Shizuku")
                showNotification()
                if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    TaskRuntime.publish("failed", "Grant Shizuku permission first")
                    stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                } else {
                    binding = true
                    main.postDelayed(bindTimeout, 15000)
                    try { Shizuku.bindUserService(args, connection) }
                    catch (error: Exception) { failStart("Shizuku connection failed: ${error.message}") }
                }
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
        main.removeCallbacks(bindTimeout)
        TaskRuntime.publish("failed", message)
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    private fun showNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("workflow", "BAAS workflow", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, TaskActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(41, Notification.Builder(this, "workflow").setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("BAAS workflow").setContentText("On-device automation is active")
            .setContentIntent(open).setOngoing(true).build())
    }
    private fun runTask(client: ISocketBroker) = thread(name = "baas-workflow") {
        val report = try {
            client.attachClient(clientOwner)
            for (name in listOf("baas-source.zip", "ocr-resources.zip")) {
                assets.open(name).use { input -> File(filesDir, name).outputStream().use { input.copyTo(it) } }
            }
            if (!Python.isStarted()) Python.start(AndroidPlatform(applicationContext))
            OcrSession(applicationContext).use { ocr ->
                CompanionBridge(client).use { bridge ->
                    Python.getInstance().getModule("workflow_task").callAttr("run", filesDir.absolutePath, bridge, ocr, gate, cycles).toString()
                }
            }
        } catch (error: Throwable) {
            JSONObject().put("state", "failed").put("error", android.util.Log.getStackTraceString(error)).toString(2)
        }
        File(filesDir, "task-report.json").writeText(report)
        val result = JSONObject(report)
        TaskRuntime.publish(result.getString("state"), "Task finished", report)
        android.util.Log.i("BaasWorkflow", report)
        android.os.Handler(mainLooper).post { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }
    override fun onDestroy() {
        destroyed = true
        main.removeCallbacks(bindTimeout)
        if (TaskRuntime.isActive) gate.requestStop()
        if (broker != null || binding) Shizuku.unbindUserService(args, connection, false)
        super.onDestroy()
    }
}
