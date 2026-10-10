package moe.baas.aio

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.Executors

/** Native Surface preview. Screenshots remain on their independent socket. */
open class PreviewActivity : ComponentActivity(), SurfaceHolder.Callback {
    protected open val fullscreenPreview = true
    protected open val createPreviewIfMissing = true
    protected open val landscapeCutoutPadding: Int? = null
    protected open fun previewMessage(message: String) = message
    protected open fun onPreviewState(available: Boolean) {}
    protected open fun onPreviewGameState(running: Boolean) {}
    protected open fun onShizukuState(state: ShizukuAccess.State) {}
    protected open val autoRequestShizukuPermission get() = true
    private var permissionPending = false
    private var connectionCheckPending = false
    private val connectionHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val permissionTimeout = Runnable {
        if (permissionPending && !closed) {
            permissionPending = false
            showAccessState()
            UiNotice.show(this, "Shizuku 授权未完成，请重新检查", error = true)
        }
    }
    private val connectionTimeout: Runnable = Runnable {
        if (binding && !closed) {
            connectionCheckPending = false
            binding = false
            try { Shizuku.unbindUserService(args, connection, false) } catch (_: Exception) { }
            statusText = "Companion 连接超时，点击重试"
            UiNotice.show(this, "Companion 连接超时", "重试", error = true) { requestShizukuAccess() }
            TaskRuntime.appendLog(3, "Companion connection timed out")
        }
    }
    private val previewOwner = android.os.Binder()
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var surface: SurfaceView
    /** FrameLayout parent of the SurfaceView (layoutSurface letterboxes inside it); hosted by Compose. */
    protected lateinit var surfaceHost: FrameLayout
    /** Former `status` TextView and `takeover` Button, now observable Compose state. */
    protected var statusText by mutableStateOf("")
    protected var takeoverVisible by mutableStateOf(false)
    protected var takeoverEnabled by mutableStateOf(false)
    protected var takeoverText by mutableStateOf("")
    @Volatile private var resuming = false
    private var broker: ISocketBroker? = null
    private var bridge: CompanionBridge? = null
    private var binding = false
    private var ready = false
    private var width = 1280
    private var height = 720
    private var closed = false
    private var foreground = false
    private var displayDiagnostics: DisplayDiagnostics? = null
    @Volatile private var acceptingTouch = false
    private var active = false
    private var verified = false
    private var connectedDisplay = -1
    private var taskLabelProfile: String? = null
    private var taskLabels: Map<String, String> = ManualTasks.names
    private val taskChanged: (String) -> Unit = {
        if (TaskRuntime.state != "paused") resuming = false
        updateScreenAwake()
        updateControls()
        if (!closed && fullscreenPreview && TaskRuntime.isActive) updateStatus("")
        if (!closed && broker != null) refreshDisplay(false)
    }
    private val args by lazy { SocketBroker.serviceArgs(this) }
    private val permission = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == 102) runOnUiThread {
            permissionPending = false
            connectionHandler.removeCallbacks(permissionTimeout)
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
                statusText = "Companion 已断开，点击重试"
            else showAccessState()
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (fullscreenPreview) window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        surface = SurfaceView(this)
        surfaceHost = FrameLayout(this).apply {
            addView(surface, FrameLayout.LayoutParams(1, 1, android.view.Gravity.CENTER))
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutSurface() }
        }
        statusText = previewMessage("Connecting Companion…")
        val layout = createContentView()
        if (fullscreenPreview) setContentView(layout) else UiTheme.setContentView(this, layout, landscapeCutoutPadding)
        showAccessState()
        surface.holder.addCallback(this)
        surface.setOnTouchListener { _, event -> forward(event) }
        TaskRuntime.observe(taskChanged)
        Shizuku.addRequestPermissionResultListener(permission)
        Shizuku.addBinderDeadListener(dead)
        Shizuku.addBinderReceivedListenerSticky(received)
    }
    /** Fullscreen layout: black frame, letterboxed Surface, status strip with the takeover button. */
    protected open fun createContentView(): android.view.View = androidx.compose.ui.platform.ComposeView(this).apply {
        setContent { FullscreenPreview(surfaceHost, statusText, takeoverVisible, takeoverEnabled, takeoverText, ::onTakeover) }
    }
    /** Former takeover click listener. */
    protected fun onTakeover() {
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
    /** Former closePhysicalDisplayButton click listener. */
    protected fun closePhysicalDisplay() {
        if (!ensureShizukuReady()) return
        val service = broker
        if (service == null) {
            requestShizukuAccess()
        } else worker.execute {
            DebugTrace.log("User requested physical panel off")
            try {
                service.beginManagedScreen()
                if (!TaskRuntime.needsScreenAwake) service.finishManagedScreen()
            } catch (error: Throwable) { failure(error) }
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
    private fun updateScreenAwake() {
        // Keep manual fullscreen preview usable; managed waiting follows the system timeout.
        val keepOn = foreground && (TaskRuntime.needsScreenAwake ||
            (fullscreenPreview && !ManagedMode.enabled && !TaskRuntime.isActive))
        if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    override fun onResume() {
        super.onResume()
        foreground = true; updateScreenAwake()
        if (BuildConfig.DEBUG && displayDiagnostics == null) {
            displayDiagnostics = DisplayDiagnostics(this, javaClass.simpleName, { broker }) {
                "fullscreen=$fullscreenPreview ready=$ready display=$connectedDisplay surfaceValid=${surface.holder.surface.isValid}"
            }.also { it.start() }
        }
        connect()
        if (broker != null) refreshDisplay(false)
    }
    override fun onPause() {
        foreground = false; updateScreenAwake()
        super.onPause()
    }
    override fun onStop() {
        displayDiagnostics?.stop(); displayDiagnostics = null
        super.onStop()
    }
    protected fun requestShizukuAccess() {
        if (permissionPending && ShizukuAccess.state(this) == ShizukuAccess.State.UNAUTHORIZED) {
            UiNotice.show(this, "正在等待 Shizuku 授权，请在授权窗口中确认")
            return
        }
        if (ShizukuAccess.state(this) == ShizukuAccess.State.READY) {
            connectionCheckPending = true
            UiNotice.show(this, "正在检查 Shizuku 连接…")
        }
        connect(userInitiated = true)
        checkShizukuConnection()
    }
    protected fun refreshShizukuAccess() = connect()
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
            onPreviewState(false); updateControls(); statusText = state.label
        }
        return state
    }
    private fun connect(userInitiated: Boolean = false, autoRequest: Boolean = true) {
        if (closed) return
        val state = showAccessState()
        if (state != ShizukuAccess.State.UNAUTHORIZED) {
            permissionPending = false
            connectionHandler.removeCallbacks(permissionTimeout)
        }
        if (state != ShizukuAccess.State.READY) {
            val prefs = getSharedPreferences("shizuku-access", MODE_PRIVATE)
            if (state == ShizukuAccess.State.UNAUTHORIZED && !permissionPending &&
                (userInitiated || (autoRequest && foreground && autoRequestShizukuPermission && !prefs.getBoolean("requested", false)))) {
                permissionPending = true
                prefs.edit().putBoolean("requested", true).apply()
                try {
                    UiNotice.show(this, "正在请求 Shizuku 授权…")
                    connectionHandler.postDelayed(permissionTimeout, 15000)
                    Shizuku.requestPermission(102)
                } catch (error: Exception) {
                    permissionPending = false; connectionHandler.removeCallbacks(permissionTimeout)
                    showAccessState(); failure(error)
                }
            } else if (userInitiated && !permissionPending) ShizukuAccess.guide(this, state) { connect(userInitiated = true) }
            return
        }
        if (binding || broker != null) return
        try {
            binding = true
            statusText = previewMessage("Connecting Companion…")
            connectionHandler.postDelayed(connectionTimeout, 15000)
            Shizuku.bindUserService(args, connection)
        } catch (error: Exception) {
            binding = false; connectionHandler.removeCallbacks(connectionTimeout)
            failure(error)
        }
    }
    protected fun refreshPreviewState() {
        if (!closed && broker != null) refreshDisplay(false)
    }
    private fun refreshDisplay(create: Boolean) {
        worker.execute { try { updateDisplay(checkNotNull(broker).preparePreview(create)) } catch (e: Throwable) { failure(e) } }
    }
    private fun updateControls() {
        if (closed) return
        takeoverVisible = TaskRuntime.isActive
        takeoverEnabled = ready && !resuming && TaskRuntime.state in setOf("running", "paused")
        takeoverText = previewMessage(when {
            resuming -> "Resuming…"
            TaskRuntime.state == "paused" -> "Resume task"
            TaskRuntime.state == "pausing" -> "Pausing…"
            else -> "Take control"
        })
    }
    private fun updateStatus(message: String) {
        if (!fullscreenPreview || !TaskRuntime.isActive) {
            statusText = previewMessage(message); return
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
        statusText = listOfNotNull(state, current?.let { ManualTasks.displayName(it, taskLabels) }).joinToString(" · ")
    }
    private fun updateDisplay(json: String) {
        val state = JSONObject(json)
        runOnUiThread {
            if (closed) return@runOnUiThread
            onPreviewGameState(state.optBoolean("gameRunning"))
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
                if (!verified && PreviewDiagnostics.requested(this)) {
                    verified = true
                    PreviewDiagnostics.run(this, surface, checkNotNull(broker), checkNotNull(bridge), width, height, previewOwner) {
                        val done = java.util.concurrent.CountDownLatch(1)
                        worker.execute { done.countDown() }
                        check(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    }
                }
            } catch (e: Throwable) { failure(e) }
        }
    }
    private fun forward(event: MotionEvent): Boolean {
        if (PreviewDiagnostics.consumesManualInput(this)) return true
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
    override fun surfaceCreated(holder: SurfaceHolder) {
        DebugTrace.log("Preview surface created display=$connectedDisplay"); attach()
    }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        DebugTrace.log("Preview surface changed display=$connectedDisplay size=${w}x$h"); attach()
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        DebugTrace.log("Preview surface destroyed display=$connectedDisplay closed=$closed task=${TaskRuntime.state}")
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
                statusText = "操作失败，请查看日志"
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
