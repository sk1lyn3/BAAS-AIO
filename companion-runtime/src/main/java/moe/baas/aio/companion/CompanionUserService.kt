package moe.baas.aio.companion

import android.os.Build
import android.os.Parcel
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.InputDevice
import android.view.Surface
import org.json.JSONObject
import kotlin.system.exitProcess

class CompanionUserService : ICompanionService.Stub(), AutoCloseable {
    private val state = CompanionState()
    private val display = VirtualDisplayController()
    private val screen = ScreenController()
    private val socketServer: CompanionSocketServer
    private var previewOwner: IBinder? = null
    private var lastTouch: MotionEvent? = null
    private var previewDeath: IBinder.DeathRecipient? = null
    private val launchedGames = linkedSetOf<String>()

    init {
        socketServer = CompanionSocketServer(this)
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == 16777115) { // Shizuku UserService destroy transaction.
            try { close() } finally { exitProcess(0) }
        }
        return super.onTransact(code, data, reply, flags)
    }

    override fun close() {
        try { stopVirtualDisplay() } finally { socketServer.close() }
    }

    override fun ping() = "ok uid=${android.os.Process.myUid()} sdk=${Build.VERSION.SDK_INT}"

    @Synchronized override fun startVirtualDisplay(width: Int, height: Int, dpi: Int): Int {
        if (width !in 1..4096 || height !in 1..4096 || dpi !in 1..1000) {
            state.setError(IllegalArgumentException("Invalid display size or density"))
            return SystemAccess.DISPLAY_NONE
        }
        stopVirtualDisplay()
        return try {
            val id = display.start(width, height, dpi)
            state.setDisplay(id, width, height, dpi)
            id
        } catch (t: Throwable) {
            state.setError(t)
            stopVirtualDisplay()
            SystemAccess.DISPLAY_NONE
        }
    }

    @Synchronized override fun stopVirtualDisplay() {
        setPreviewSurface(null, null)
        state.clearDisplay()
        try { display.stop() } finally { screen.release() }
    }

    @Synchronized override fun getDisplayId() = state.displayId

    @Synchronized override fun getStatus(): String {
        val info = NativeCaptureBridge.frameInfo()
        val age = if (info[3] == 0L) -1 else (SystemClock.elapsedRealtimeNanos() - info[3]) / 1_000_000
        return "${state.status(display.isAlive())} frames=${info[2]} frameAgeMs=$age"
    }

    @Synchronized override fun launchApp(packageName: String, activityName: String?): Boolean {
        val ok = SystemAccess.launchOnDisplay(packageName, activityName, state.displayId)
        if (ok && packageName in setOf("com.YostarJP.BlueArchive", "com.nexon.bluearchive", "com.RoamingStar.BlueArchive", "com.RoamingStar.BlueArchive.bilibili")) {
            launchedGames.add(packageName)
        }
        return ok
    }

    @Synchronized fun launchedGamePackages(): List<String> = launchedGames.toList()
    @Synchronized fun forgetGame(packageName: String) { launchedGames.remove(packageName) }

    @Synchronized override fun click(x: Int, y: Int) = clickResult(x, y).getBoolean("clicked")

    // The socket receives one atomic result; the existing Binder boolean API stays compatible.
    @Synchronized internal fun clickResult(x: Int, y: Int): JSONObject {
        val reason = when {
            lastTouch != null -> "Preview touch is still active"
            !display.isAlive() -> "Virtual display is not alive"
            !validPoint(x, y) -> "Point outside display: ($x,$y)"
            else -> null
        }
        val ok = reason == null && SystemAccess.click(state.displayId, x, y)
        return JSONObject().put("clicked", ok).put("ok", ok).apply {
            if (!ok) {
                put("error", "Click failed: ${reason ?: SystemAccess.lastInjectionError()}")
                put("retryable", lastTouch != null && reason != null)
                put("delivery", if (reason != null) "not_sent" else "unknown")
            }
        }
    }

    @Synchronized override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) =
        lastTouch == null && validPoint(x1, y1) && validPoint(x2, y2) && durationMs in 1..10000 &&
            SystemAccess.swipe(state.displayId, x1, y1, x2, y2, durationMs)

    @Synchronized override fun keyEvent(keyCode: Int) =
        display.isAlive() && keyCode in 0..KeyEvent.getMaxKeyCode() && SystemAccess.keyEvent(state.displayId, keyCode)

    @Synchronized override fun capturePng(path: String) = if (display.isAlive() && NativeCaptureBridge.saveLatestPng(path)) path else ""
    @Synchronized override fun cleanupStaleServices() = 0 // The AIO broker owns this runtime; never kill external services.

    @Synchronized override fun getStateJson(): String {
        try {
            val info = NativeCaptureBridge.frameInfo()
            val power = SystemAccess.context().getSystemService("power") as PowerManager
            return JSONObject().put("displayId", state.displayId).put("width", state.width)
                .put("ownerPackage", "moe.baas.aio").put("runtimePid", android.os.Process.myPid())
                .put("height", state.height).put("dpi", state.dpi).put("alive", display.isAlive())
                .put("error", state.lastError).put("frameId", info[2]).put("timestampNs", info[3])
                .put("frameAgeMs", if (info[3] == 0L) -1 else (SystemClock.elapsedRealtimeNanos() - info[3]) / 1_000_000)
                .put("panelOff", screen.isPanelOff()).put("panelGuardError", screen.getGuardError())
                .put("interactive", power.isInteractive).toString()
        } catch (e: Exception) { throw IllegalStateException(e) }
    }

    @Synchronized override fun wakeDevice(): Boolean = try { screen.wake() }
        catch (e: Exception) { state.setError(e); false }

    @Synchronized override fun setPhysicalScreenOff(off: Boolean): Boolean {
        return try {
            if (off && !screen.wake()) false else screen.setPanelOff(off)
        } catch (e: Exception) { state.setError(e); false }
    }

    // Capture stays outside the service monitor: input must not block frame transfer.
    fun captureFrame(): NativeFrame {
        val id = state.displayId
        check(id >= 0) { "No active virtual display" }
        val frame = NativeCaptureBridge.captureFrame() ?: error("No frame available yet")
        check(state.displayId == id) { "Display changed during capture" }
        frame.displayId = id
        return frame
    }

    private fun validPoint(x: Int, y: Int) =
        display.isAlive() && x >= 0 && y >= 0 && x < state.width && y < state.height

    @Synchronized override fun pinch(cx: Int, cy: Int, start: Int, end: Int, durationMs: Int): Boolean {
        val spread = maxOf(start, end)
        return lastTouch == null && start >= 0 && end >= 0 && durationMs in 80..10000 && spread <= state.width / 2 &&
            validPoint(cx - spread, cy) && validPoint(cx + spread, cy) &&
            SystemAccess.pinch(state.displayId, cx, cy, start, end, durationMs)
    }

    @Synchronized override fun setPreviewSurface(surface: Surface?, owner: IBinder?) {
        try { updatePreviewSurface(surface, owner) } finally { surface?.release() }
    }

    private fun updatePreviewSurface(surface: Surface?, owner: IBinder?) {
        if (surface != null) check(surface.isValid && display.isAlive() && owner != null && owner.isBinderAlive) { "Preview requires a live display and owner" }
        // A previous UI may finish its asynchronous teardown after a new UI attaches.
        if (surface == null && owner != null && previewOwner != owner) return
        cancelTouch()
        previewDeath?.let { death ->
            try { previewOwner?.unlinkToDeath(death, 0) } catch (_: java.util.NoSuchElementException) { }
        }
        previewOwner = null
        previewDeath = null
        NativeCaptureBridge.setPreviewSurface(surface)
        if (surface != null && owner != null) {
            val death = IBinder.DeathRecipient { synchronized(this) { setPreviewSurface(null, owner) } }
            try {
                owner.linkToDeath(death, 0)
                previewDeath = death
                previewOwner = owner
            } catch (error: android.os.RemoteException) {
                NativeCaptureBridge.setPreviewSurface(null)
                throw error
            }
        }
    }

    @Synchronized override fun touch(event: MotionEvent): Boolean = try { streamTouch(event) } finally { event.recycle() }

    private fun streamTouch(event: MotionEvent): Boolean {
        if (previewOwner == null || !display.isAlive()) return false
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) { cancelTouch(); return true }
        val action = event.actionMasked
        if (action !in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP)) return false
        if (event.pointerCount !in 1..10 || event.actionIndex !in 0 until event.pointerCount ||
            (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP) && event.pointerCount != 1) ||
            (0 until event.pointerCount).any {
                !event.getX(it).isFinite() || !event.getY(it).isFinite() ||
                event.getX(it) < 0 || event.getY(it) < 0 ||
                !validPoint(event.getX(it).toInt(), event.getY(it).toInt()) }) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) cancelTouch()
        else if (lastTouch == null) return false
        val copy = MotionEvent.obtain(event)
        copy.source = InputDevice.SOURCE_TOUCHSCREEN
        val ok = try { SystemAccess.inject(copy, state.displayId, if (event.actionMasked == MotionEvent.ACTION_MOVE) 0 else 2) }
            finally { copy.recycle() }
        if (!ok) { cancelTouch(); return false }
        lastTouch?.recycle()
        lastTouch = when (action) {
            MotionEvent.ACTION_UP -> null
            MotionEvent.ACTION_POINTER_UP -> {
                val indices = (0 until event.pointerCount).filter { it != event.actionIndex }
                val props = indices.map { i -> MotionEvent.PointerProperties().also { event.getPointerProperties(i, it) } }.toTypedArray()
                val coords = indices.map { i -> MotionEvent.PointerCoords().also { event.getPointerCoords(i, it) } }.toTypedArray()
                MotionEvent.obtain(event.downTime, event.eventTime, MotionEvent.ACTION_MOVE, indices.size,
                    props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            }
            else -> MotionEvent.obtain(event)
        }
        return true
    }

    private fun cancelTouch() {
        val last = lastTouch ?: return
        lastTouch = null
        val properties = Array(last.pointerCount) { MotionEvent.PointerProperties().also { p -> last.getPointerProperties(it, p) } }
        val coords = Array(last.pointerCount) { MotionEvent.PointerCoords().also { p -> last.getPointerCoords(it, p) } }
        val cancel = MotionEvent.obtain(last.downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL,
            last.pointerCount, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { SystemAccess.inject(cancel, state.displayId, 2) }
        finally { cancel.recycle(); last.recycle() }
    }
}
