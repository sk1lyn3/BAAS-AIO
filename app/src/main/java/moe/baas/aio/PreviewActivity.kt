package moe.baas.aio

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Matrix
import android.os.Bundle
import android.os.IBinder
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.Executors

/** Native Surface preview. Screenshots remain on their independent socket. */
open class PreviewActivity : Activity(), SurfaceHolder.Callback {
    protected open val fullscreenPreview = true
    protected open val createPreviewIfMissing = true
    protected open fun previewMessage(message: String) = message
    protected open fun onPreviewState(available: Boolean) {}
    protected open fun onShizukuState(state: ShizukuAccess.State) {}
    private var permissionPending = false
    private var connectionCheckPending = false
    private val connectionHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val connectionTimeout: Runnable = Runnable {
        if (binding && !closed) {
            connectionCheckPending = false
            binding = false
            try { Shizuku.unbindUserService(args, connection, false) } catch (_: Exception) { }
            status.text = "Companion 连接超时，点击重试"
            UiNotice.show(this, "Companion 连接超时", "重试", error = true) { requestShizukuAccess() }
            TaskRuntime.appendLog(3, "Companion connection timed out")
        }
    }
    private val previewOwner = android.os.Binder()
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var surface: SurfaceView
    private lateinit var status: TextView
    private lateinit var takeover: Button
    @Volatile private var resuming = false
    private var broker: ISocketBroker? = null
    private var bridge: CompanionBridge? = null
    private var binding = false
    private var ready = false
    private var width = 1280
    private var height = 720
    private var closed = false
    @Volatile private var acceptingTouch = false
    private var active = false
    private var connectedDisplay = -1
    private var taskLabelProfile: String? = null
    private var taskLabels: Map<String, String> = ManualTasks.names
    private val taskChanged: (String) -> Unit = {
        if (TaskRuntime.state != "paused") resuming = false
        updateControls()
        if (!closed && fullscreenPreview && TaskRuntime.isActive) updateStatus("")
        if (!closed && broker != null) refreshDisplay(false)
    }
    private val args by lazy { SocketBroker.serviceArgs(this) }
    private val permission = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == 102) runOnUiThread {
            permissionPending = false
            connect(autoRequest = false)
            if (result == android.content.pm.PackageManager.PERMISSION_GRANTED) UiNotice.show(this, "Shizuku 已授权")
            else {
                val state = ShizukuAccess.state(this)
                UiNotice.show(this, "未获得 Shizuku 授权", state.action) { requestShizukuAccess() }
            }
        }
    }
    private val received = Shizuku.OnBinderReceivedListener { runOnUiThread { connect() } }
    private val dead = Shizuku.OnBinderDeadListener { runOnUiThread {
        connectionHandler.removeCallbacks(connectionTimeout)
        if (connectionCheckPending) UiNotice.show(this, "Shizuku 已断开", error = true)
        connectionCheckPending = false
        binding = false; broker = null; ready = false; permissionPending = false
        onPreviewState(false); updateControls(); showAccessState()
    } }
    private val connection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            connectionHandler.removeCallbacks(connectionTimeout)
            binding = false
            if (closed) { Shizuku.unbindUserService(args, this, false); return }
            if (broker?.asBinder() == binder) return
            broker = ISocketBroker.Stub.asInterface(binder)
            checkShizukuConnection()
            worker.execute {
                try {
                    checkNotNull(broker).attachClient(previewOwner)
                    if (intent.getBooleanExtra("verify", false)) bridge = CompanionBridge(checkNotNull(broker))
                    val game = intent.getStringExtra("gamePackage")
                    if (!game.isNullOrEmpty()) {
                        check(!TaskRuntime.isActive) { "Stop the workflow task before launching a game preview" }
                        updateDisplay(checkNotNull(broker).launchPreviewGame(game))
                    } else updateDisplay(checkNotNull(broker).preparePreview(createPreviewIfMissing && !TaskRuntime.isActive))
                } catch (e: Throwable) { failure(e) }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            connectionHandler.removeCallbacks(connectionTimeout)
            binding = false; broker = null; ready = false
            onPreviewState(false); updateControls()
            if (ShizukuAccess.state(this@PreviewActivity) == ShizukuAccess.State.READY)
                status.text = "Companion 已断开，点击重试"
            else showAccessState()
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (fullscreenPreview) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        surface = SurfaceView(this)
        status = TextView(this).apply { setTextColor(-1); setBackgroundColor(0x99000000.toInt()); text = previewMessage("Connecting Companion…") }
        takeover = Button(this).apply {
            setOnClickListener {
                if (TaskRuntime.state == "paused") {
                    // Drain queued manual events before the service cancels the gesture and resumes.
                    resuming = true; active = false; updateControls()
                    worker.execute { runOnUiThread {
                        if (!closed && TaskRuntime.state == "paused")
                            startService(Intent(this@PreviewActivity, TaskService::class.java).setAction(TaskService.RESUME))
                    } }
                } else if (TaskRuntime.state == "running") {
                    startService(Intent(this@PreviewActivity, TaskService::class.java).setAction(TaskService.PAUSE))
                }
            }
        }
        val layout = buildPreviewLayout(surface, status, takeover)
        if (fullscreenPreview) setContentView(layout) else UiTheme.setContentView(this, layout)
        // The fullscreen hint is visual only: unhandled touches reach the Surface below.
        if (!fullscreenPreview) status.setOnClickListener { requestShizukuAccess() }
        showAccessState()
        surface.holder.addCallback(this)
        surface.setOnTouchListener { _, event -> forward(event) }
        TaskRuntime.observe(taskChanged)
        Shizuku.addRequestPermissionResultListener(permission)
        Shizuku.addBinderDeadListener(dead)
        Shizuku.addBinderReceivedListenerSticky(received)
    }
    protected open fun buildPreviewLayout(surface: SurfaceView, status: TextView, takeover: Button): android.view.View =
        FrameLayout(this).apply {
            setBackgroundColor(0xff000000.toInt())
            addView(surface, FrameLayout.LayoutParams(1, 1, android.view.Gravity.CENTER))
            addView(LinearLayout(this@PreviewActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(status, LinearLayout.LayoutParams(0, -2, 1f))
                addView(takeover)
            }, FrameLayout.LayoutParams(-1, -2, android.view.Gravity.TOP))
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutSurface() }
        }
    protected fun closePhysicalDisplayButton(reference: Button): Button = Button(this).apply {
        text = "关闭显示"; isAllCaps = false
        textSize = reference.textSize / resources.displayMetrics.scaledDensity
        setTextColor(reference.textColors)
        minHeight = reference.minHeight; minimumHeight = reference.minimumHeight
        setPadding(reference.paddingLeft, reference.paddingTop, reference.paddingRight, reference.paddingBottom)
        setOnClickListener {
            if (!ensureShizukuReady()) return@setOnClickListener
            val service = broker
            if (service == null) {
                requestShizukuAccess()
            } else worker.execute {
                try { service.beginManagedScreen() } catch (error: Throwable) { failure(error) }
            }
        }
    }
    protected fun launchPreviewGame(game: String) {
        if (TaskRuntime.isActive || !ensureShizukuReady()) return
        val service = broker
        if (service == null) { requestShizukuAccess(); return }
        worker.execute { try { updateDisplay(service.launchPreviewGame(game)) } catch (e: Throwable) { failure(e) } }
    }
    protected fun closePreviewGame() {
        if (TaskRuntime.isActive) return
        val service = broker ?: return
        acceptingTouch = false; active = false
        worker.execute { try { updateDisplay(service.closePreviewGame()) } catch (e: Throwable) { failure(e) } }
    }
    override fun onResume() {
        super.onResume()
        connect()
        if (broker != null) refreshDisplay(false)
    }
    protected fun requestShizukuAccess() {
        if (ShizukuAccess.state(this) == ShizukuAccess.State.READY) {
            connectionCheckPending = true
            UiNotice.show(this, "正在检查 Shizuku 连接…")
        }
        connect(userInitiated = true)
        checkShizukuConnection()
    }
    private fun checkShizukuConnection() {
        val service = broker ?: return
        if (!connectionCheckPending) return
        connectionCheckPending = false
        worker.execute {
            try {
                val state = service.preparePreview(false)
                JSONObject(state).getBoolean("alive")
                updateDisplay(state)
                UiNotice.show(this, "Shizuku 已授权，中间件连接正常")
            } catch (error: Throwable) { failure(error) }
        }
    }
    protected fun ensureShizukuReady(): Boolean {
        if (ShizukuAccess.state(this) == ShizukuAccess.State.READY) return true
        requestShizukuAccess(); return false
    }
    private fun showAccessState(): ShizukuAccess.State {
        val state = ShizukuAccess.state(this)
        onShizukuState(state)
        if (state != ShizukuAccess.State.READY) {
            ready = false; acceptingTouch = false
            onPreviewState(false); updateControls(); status.text = state.label
        }
        return state
    }
    private fun connect(userInitiated: Boolean = false, autoRequest: Boolean = true) {
        if (closed) return
        val state = showAccessState()
        if (state != ShizukuAccess.State.READY) {
            val prefs = getSharedPreferences("shizuku-access", MODE_PRIVATE)
            if (state == ShizukuAccess.State.UNAUTHORIZED && !permissionPending &&
                (userInitiated || (autoRequest && !prefs.getBoolean("requested", false)))) {
                permissionPending = true
                prefs.edit().putBoolean("requested", true).apply()
                try { Shizuku.requestPermission(102) }
                catch (error: Exception) { permissionPending = false; showAccessState(); failure(error) }
            } else if (userInitiated && !permissionPending) ShizukuAccess.guide(this, state) { connect(userInitiated = true) }
            return
        }
        if (binding || broker != null) return
        try {
            binding = true
            status.text = previewMessage("Connecting Companion…")
            connectionHandler.postDelayed(connectionTimeout, 15000)
            Shizuku.bindUserService(args, connection)
        } catch (error: Exception) {
            binding = false; connectionHandler.removeCallbacks(connectionTimeout)
            failure(error)
        }
    }
    private fun refreshDisplay(create: Boolean) {
        worker.execute { try { updateDisplay(checkNotNull(broker).preparePreview(create)) } catch (e: Throwable) { failure(e) } }
    }
    private fun updateControls() {
        if (closed) return
        takeover.visibility = if (TaskRuntime.isActive) android.view.View.VISIBLE else android.view.View.GONE
        takeover.isEnabled = ready && !resuming && TaskRuntime.state in setOf("running", "paused")
        takeover.text = previewMessage(when {
            resuming -> "Resuming…"
            TaskRuntime.state == "paused" -> "Resume task"
            TaskRuntime.state == "pausing" -> "Pausing…"
            else -> "Take control"
        })
    }
    private fun updateStatus(message: String) {
        if (!fullscreenPreview || !TaskRuntime.isActive) {
            status.text = previewMessage(message); return
        }
        val snapshot = JSONObject(TaskRuntime.snapshot)
        val progress = snapshot.optJSONObject("progress")
        val current = progress?.optString("currentTask")?.takeIf { it.isNotBlank() && it != "null" }
        val profile = progress?.optString("profile")?.takeIf { it.isNotBlank() && it != "null" }
        if (current != null && profile != taskLabelProfile) {
            taskLabels = ManualTasks.names + runCatching {
                val events = org.json.JSONArray(File(profile ?: "", "event.json").readText())
                (0 until events.length()).associate {
                    val event = events.getJSONObject(it)
                    event.getString("func_name") to event.getString("event_name")
                }
            }.getOrDefault(emptyMap())
            taskLabelProfile = profile
        }
        val state = when (snapshot.optString("state")) {
            "starting" -> "启动中"; "pausing" -> "等待暂停"; "paused" -> "已暂停"
            "stopping" -> "正在停止"
            else -> if (progress?.optString("phase") == "idle") "等待下次任务" else "执行中"
        }
        status.text = listOfNotNull(state, current?.let { taskLabels[it] ?: it }).joinToString(" · ")
    }
    private fun updateDisplay(json: String) {
        val state = JSONObject(json)
        runOnUiThread {
            if (closed) return@runOnUiThread
            onPreviewState(state.getBoolean("alive"))
            if (!state.getBoolean("alive")) {
                ready = false; connectedDisplay = -1
                updateControls()
                updateStatus(if (TaskRuntime.isActive) "Waiting for task display…" else "Preview idle · Start a game or task")
                return@runOnUiThread
            }
            ready = true
            updateControls()
            updateStatus(when (TaskRuntime.state) {
                "paused" -> "Manual control · Resume when finished"
                "pausing" -> "Waiting for the current operation…"
                else -> if (TaskRuntime.isActive) "Task running · Take control to interact" else "Native preview · drag / pinch · Back to close"
            })
            val id = state.getInt("displayId")
            if (connectedDisplay != id) {
                connectedDisplay = id; width = state.getInt("width"); height = state.getInt("height")
                layoutSurface(); attach()
            }
        }
    }
    protected fun layoutSurface() {
        val parent = surface.parent as? FrameLayout ?: return
        if (parent.width == 0 || parent.height == 0) return
        val scale = minOf(parent.width.toFloat() / width, parent.height.toFloat() / height)
        val w = (width * scale).toInt(); val h = (height * scale).toInt()
        if (surface.width != w || surface.height != h) surface.layoutParams = FrameLayout.LayoutParams(w, h, android.view.Gravity.CENTER)
    }
    private fun attach() {
        if (!ready || closed || !surface.holder.surface.isValid) return
        val target = surface.holder.surface
        worker.execute {
            try {
                checkNotNull(broker).setPreviewSurface(target, previewOwner)
                acceptingTouch = true

            } catch (e: Throwable) { failure(e) }
        }
    }
    private fun forward(event: MotionEvent): Boolean {
        if (resuming || !TaskRuntime.allowsManualInput) return true
        if (!acceptingTouch || !ready || closed) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) active = true
        if (!active) return false
        val copy = MotionEvent.obtain(event)
        copy.transform(Matrix().apply { setScale(width.toFloat() / surface.width, height.toFloat() / surface.height) })
        val properties = Array(copy.pointerCount) { MotionEvent.PointerProperties().also { p -> copy.getPointerProperties(it, p) } }
        val coords = Array(copy.pointerCount) { MotionEvent.PointerCoords().also { p ->
            copy.getPointerCoords(it, p); p.x = p.x.coerceIn(0f, width - 1f); p.y = p.y.coerceIn(0f, height - 1f)
        } }
        val mapped = MotionEvent.obtain(copy.downTime, copy.eventTime, copy.action, copy.pointerCount, properties, coords,
            copy.metaState, copy.buttonState, 1f, 1f, copy.deviceId, copy.edgeFlags, copy.source, copy.flags)
        copy.recycle()
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) active = false
        worker.execute { try { if (resuming || !TaskRuntime.allowsManualInput) return@execute; check(broker?.touch(mapped) == true) { "Touch injection failed" } } catch (e: Throwable) { failure(e) } finally { mapped.recycle() } }
        return true
    }
    override fun surfaceCreated(holder: SurfaceHolder) { attach() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) { attach() }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        acceptingTouch = false; active = false
        if (closed) return
        worker.execute { try { broker?.setPreviewSurface(null, previewOwner) } catch (e: Throwable) { failure(e) } }
    }
    private fun failure(error: Throwable) {
        connectionCheckPending = false
        val text = android.util.Log.getStackTraceString(error)
        android.util.Log.e("BaasPreview", text)
        File(filesDir, "preview-error.txt").writeText(text)
        runOnUiThread {
            if (!closed) {
                status.text = "操作失败，请查看日志"
                UiNotice.show(this, error.message ?: "操作失败", error = true)
            }
        }
    }
    override fun onDestroy() {
        closed = true; acceptingTouch = false; active = false
        connectionHandler.removeCallbacksAndMessages(null)
        Shizuku.removeBinderDeadListener(dead)
        TaskRuntime.remove(taskChanged)
        Shizuku.removeRequestPermissionResultListener(permission)
        Shizuku.removeBinderReceivedListener(received)
        worker.execute {
            try {
                // Release sockets before the potentially slower native Surface teardown.
                bridge?.close()
                broker?.setPreviewSurface(null, previewOwner)
                // The daemon owns the display; closing a preview never stops background work.
            } catch (e: Throwable) { failure(e) }
            finally {
                runOnUiThread { if (broker != null || binding) Shizuku.unbindUserService(args, connection, false) }
            }
        }
        worker.shutdown()
        super.onDestroy()
    }
}
