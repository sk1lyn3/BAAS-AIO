package moe.baas.aio

import android.app.Activity
import android.content.ComponentName
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
import android.widget.TextView
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.Executors

/** Native Surface preview. Screenshots remain on their independent socket. */
class PreviewActivity : Activity(), SurfaceHolder.Callback {
    private val previewOwner = android.os.Binder()
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var surface: SurfaceView
    private lateinit var status: TextView
    private var broker: ISocketBroker? = null
    private var bridge: CompanionBridge? = null
    private var binding = false
    private var ready = false
    private var width = 1280
    private var height = 720
    private var closed = false
    @Volatile private var acceptingTouch = false
    private var active = false
    private var verified = false
    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(this, SocketBroker::class.java))
            .daemon(false).processNameSuffix("aio-broker").debuggable(true).version(SocketBroker.SERVICE_VERSION).tag("baas-aio-broker")
    }
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
                    val b = CompanionBridge(checkNotNull(broker))
                    bridge = b
                    val state = JSONObject(b.command("{\"cmd\":\"status\"}"))
                    val body = state.optJSONObject("state") ?: state
                    if (!body.optBoolean("alive")) {
                        b.command("{\"cmd\":\"start\",\"width\":1280,\"height\":720,\"dpi\":240}")
                        b.command("{\"cmd\":\"launch\",\"package\":\"moe.baas.companion\",\"activity\":\"moe.baas.companion.ProbeActivity\"}")
                    } else { width = body.getInt("width"); height = body.getInt("height") }
                    runOnUiThread {
                        if (!closed) { ready = true; layoutSurface(); attach(); status.text = "Native preview · drag / pinch · Back to close" }
                    }
                } catch (e: Throwable) { failure(e) }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) { broker = null; ready = false; status.text = "Companion disconnected" }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        surface = SurfaceView(this)
        status = TextView(this).apply { setTextColor(-1); setBackgroundColor(0x99000000.toInt()); text = "Connecting Companion…" }
        val layout = FrameLayout(this).apply {
            setBackgroundColor(0xff000000.toInt())
            addView(surface, FrameLayout.LayoutParams(1, 1, android.view.Gravity.CENTER))
            addView(status, FrameLayout.LayoutParams(-2, -2, android.view.Gravity.TOP))
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutSurface() }
        }
        setContentView(layout)
        surface.holder.addCallback(this)
        surface.setOnTouchListener { _, event -> forward(event) }
        Shizuku.addRequestPermissionResultListener(permission)
        Shizuku.addBinderReceivedListenerSticky(received)
    }
    private fun connect() {
        if (closed || binding || broker != null || !Shizuku.pingBinder()) return
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) { Shizuku.requestPermission(102); return }
        binding = true
        Shizuku.bindUserService(args, connection)
    }
    private fun layoutSurface() {
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
                if (intent.getBooleanExtra("verify", false) && !verified) {
                    verified = true
                    PreviewVerifier.run(this, surface, checkNotNull(broker), checkNotNull(bridge), width, height, previewOwner, {
                        val done = java.util.concurrent.CountDownLatch(1)
                        worker.execute { done.countDown() }
                        check(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    }, intent.getBooleanExtra("hold", false))
                }
            } catch (e: Throwable) { failure(e) }
        }
    }
    private fun forward(event: MotionEvent): Boolean {
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
        worker.execute { try { check(broker?.touch(mapped) == true) { "Touch injection failed" } } catch (e: Throwable) { failure(e) } finally { mapped.recycle() } }
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
