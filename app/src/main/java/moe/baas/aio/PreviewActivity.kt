package moe.baas.aio

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
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
    private val taskChanged: (String) -> Unit = {
        if (TaskRuntime.state != "paused") resuming = false
        updateControls()
        if (!closed && broker != null) refreshDisplay(false)
    }
    private val args by lazy { SocketBroker.serviceArgs(this) }
    private val permission = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == 102 && result == PackageManager.PERMISSION_GRANTED) connect()
    }
    private val received = Shizuku.OnBinderReceivedListener { runOnUiThread { connect() } }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            binding = false
            if (closed) { Shizuku.unbindUserService(args, this, false); return }
            if (broker?.asBinder() == binder) return
            broker = ISocketBroker.Stub.asInterface(binder)
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
        override fun onServiceDisconnected(name: ComponentName) { broker = null; ready = false; onPreviewState(false); updateControls(); status.text = previewMessage("Companion disconnected") }
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
        setContentView(buildPreviewLayout(surface, status, takeover))
        surface.holder.addCallback(this)
        surface.setOnTouchListener { _, event -> forward(event) }
        TaskRuntime.observe(taskChanged)
        Shizuku.addRequestPermissionResultListener(permission)
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
                addView(closePhysicalDisplayButton())
            }, FrameLayout.LayoutParams(-1, -2, android.view.Gravity.TOP))
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutSurface() }
        }
    protected fun closePhysicalDisplayButton(): Button = UiTheme.flatButton(this, "关闭显示") {
        val service = broker
        if (service == null) {
            status.text = previewMessage("Connecting Companion…"); connect()
        } else worker.execute {
            try { service.beginManagedScreen() } catch (error: Throwable) { failure(error) }
        }
    }
    protected fun launchPreviewGame(game: String) {
        if (TaskRuntime.isActive) return
        val service = broker
        if (service == null) { status.text = "Start Shizuku and grant permission first"; connect(); return }
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
        if (broker != null) refreshDisplay(false)
    }
    private fun connect() {
        if (closed || binding || broker != null || !Shizuku.pingBinder()) return
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) { Shizuku.requestPermission(102); return }
        binding = true
        Shizuku.bindUserService(args, connection)
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
    private fun updateDisplay(json: String) {
        val state = JSONObject(json)
        runOnUiThread {
            if (closed) return@runOnUiThread
            onPreviewState(state.getBoolean("alive"))
            if (!state.getBoolean("alive")) {
                ready = false; connectedDisplay = -1
                updateControls()
                status.text = previewMessage(if (TaskRuntime.isActive) "Waiting for task display…" else "Preview idle · Start a game or task")
                return@runOnUiThread
            }
            ready = true
            updateControls()
            status.text = previewMessage(when (TaskRuntime.state) {
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
        val text = android.util.Log.getStackTraceString(error)
        android.util.Log.e("BaasPreview", text)
        File(filesDir, "preview-error.txt").writeText(text)
        runOnUiThread { if (!closed) status.text = error.toString() }
    }
    override fun onDestroy() {
        closed = true; acceptingTouch = false; active = false
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
