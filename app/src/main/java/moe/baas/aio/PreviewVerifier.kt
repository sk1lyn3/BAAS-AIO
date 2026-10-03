package moe.baas.aio

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceView
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Explicit debug probe: exercises the same view listener as real manual touches. */
internal object PreviewVerifier {
    fun run(activity: Activity, surface: SurfaceView, broker: ISocketBroker, bridge: CompanionBridge,
            width: Int, height: Int, owner: android.os.IBinder, idle: () -> Unit, hold: Boolean) = thread(name = "preview-verifier") {
        val checks = JSONObject()
        val report = JSONObject().put("ok", false).put("checks", checks)
        val directory = File(activity.filesDir, "preview-probe").apply { mkdirs() }
        fun state() = JSONObject(broker.readProbeState())
        fun picture(): Bitmap {
            val bitmap = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
            val done = CountDownLatch(1)
            var result = -1
            activity.runOnUiThread { PixelCopy.request(surface, bitmap, { result = it; done.countDown() }, Handler(Looper.getMainLooper())) }
            check(done.await(5, TimeUnit.SECONDS) && result == PixelCopy.SUCCESS) { "PixelCopy failed: $result" }
            return bitmap
        }
        var downTime = 0L
        fun event(action: Int, points: List<Pair<Float, Float>>) {
            if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
            val properties = Array(points.size) { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(points.size) { MotionEvent.PointerCoords().apply {
                x = points[it].first * surface.width; y = points[it].second * surface.height; pressure = 1f; size = .1f
            } }
            val e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, points.size, properties, coords,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            val done = CountDownLatch(1)
            var accepted = false
            activity.runOnUiThread { try { accepted = surface.dispatchTouchEvent(e) } finally { e.recycle(); done.countDown() } }
            check(done.await(5, TimeUnit.SECONDS) && accepted) { "Preview view rejected touch" }
            idle()
        }
        try {
            Thread.sleep(700)
            val first = picture()
            val color = first.getPixel(first.width / 10, first.height * 4 / 5)
            check(kotlin.math.abs(android.graphics.Color.red(color) - 24) < 4 &&
                kotlin.math.abs(android.graphics.Color.green(color) - 40) < 4 &&
                kotlin.math.abs(android.graphics.Color.blue(color) - 64) < 4) { "Surface does not contain probe pixels: $color" }
            File(directory, "surface.png").outputStream().use { first.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Thread.sleep(350)
            val second = picture()
            check(second.getPixel(second.width / 10, second.height * 4 / 5) == color) { "Preview became blank or changed orientation" }
            val captureFrame = bridge.screenshot()
            val png = checkNotNull(android.graphics.BitmapFactory.decodeByteArray(captureFrame.png, 0, captureFrame.png.size))
            check(png.width == width && png.height == height && png.getPixel(width / 10, height * 4 / 5) == color)
            png.recycle()
            checks.put("independent_screenshot", JSONObject(captureFrame.metadata))
            val a = IntArray(first.width * first.height); val b = IntArray(a.size)
            first.getPixels(a, 0, first.width, 0, 0, first.width, first.height)
            second.getPixels(b, 0, second.width, 0, 0, second.width, second.height)
            val changed = a.indices.count { a[it] != b[it] }
            check(changed > 10 && changed < a.size / 4) { "Preview frame did not advance stably: $changed" }
            checks.put("native_surface", JSONObject().put("width", first.width).put("height", first.height).put("changedPixels", changed))
            first.recycle(); second.recycle()
            val before = state()
            event(MotionEvent.ACTION_DOWN, listOf(.3f to .4f))
            event(MotionEvent.ACTION_UP, listOf(.3f to .4f))
            val clicked = state()
            check(clicked.getInt("downs") == before.getInt("downs") + 1 && clicked.getInt("ups") == before.getInt("ups") + 1)
            check(kotlin.math.abs(clicked.getDouble("x") - width * .3) < 2 && kotlin.math.abs(clicked.getDouble("y") - height * .4) < 2)
            checks.put("coordinate_mapping", clicked)
            val frames = mutableListOf<Long>()
            var captureError: Throwable? = null
            val capture = thread(name = "preview-concurrent-capture") {
                try { repeat(6) { val frame = bridge.screenshot(); frames.add(JSONObject(frame.metadata).getLong("frameId")); Thread.sleep(65) } }
                catch (e: Throwable) { captureError = e }
            }
            event(MotionEvent.ACTION_DOWN, listOf(.2f to .6f))
            repeat(25) { i -> event(MotionEvent.ACTION_MOVE, listOf((.2f + i * .02f) to .6f)); Thread.sleep(16) }
            event(MotionEvent.ACTION_UP, listOf(.7f to .6f))
            capture.join(8000)
            check(!capture.isAlive && captureError == null && frames.size == 6 && frames.distinct().size >= 4) { "Concurrent capture failed: $captureError / $frames" }
            val dragged = state()
            check(dragged.getInt("moves") >= clicked.getInt("moves") + 20 && dragged.getInt("cancels") == clicked.getInt("cancels"))
            checks.put("drag_with_independent_capture", JSONObject().put("frameIds", org.json.JSONArray(frames)).put("events", dragged))
            event(MotionEvent.ACTION_DOWN, listOf(.25f to .5f))
            event(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), listOf(.25f to .5f, .75f to .5f))
            repeat(10) { i -> event(MotionEvent.ACTION_MOVE, listOf((.25f + i * .02f) to .5f, (.75f - i * .02f) to .5f)); Thread.sleep(16) }
            event(MotionEvent.ACTION_POINTER_UP or (1 shl 8), listOf(.43f to .5f, .57f to .5f))
            event(MotionEvent.ACTION_UP, listOf(.43f to .5f))
            val pinched = state()
            check(pinched.getInt("pointerDowns") == dragged.getInt("pointerDowns") + 1 && pinched.getInt("pointerUps") == dragged.getInt("pointerUps") + 1 && pinched.getInt("maxPointers") == 2)
            check(pinched.getDouble("lastSeparation") < pinched.getDouble("firstSeparation"))
            checks.put("multi_pointer", pinched)
            event(MotionEvent.ACTION_DOWN, listOf(.5f to .5f))
            broker.setPreviewSurface(null, owner)
            val detached = state()
            check(detached.getInt("cancels") == pinched.getInt("cancels") + 1)
            broker.setPreviewSurface(surface.holder.surface, owner)
            event(MotionEvent.ACTION_DOWN, listOf(.4f to .5f))
            event(MotionEvent.ACTION_UP, listOf(.4f to .5f))
            val reattached = state()
            check(reattached.getInt("displayId") == before.getInt("displayId") && reattached.getInt("ups") == pinched.getInt("ups") + 1)
            check(bridge.screenshot().png.isNotEmpty())
            checks.put("detach_cancel_reattach", reattached)
            if (hold) { event(MotionEvent.ACTION_DOWN, listOf(.5f to .5f)); checks.put("holding_for_owner_death", state()) }
            report.put("ok", true)
        } catch (e: Throwable) { report.put("error", android.util.Log.getStackTraceString(e)) }
        File(activity.filesDir, "preview-report.json").writeText(report.toString(2))
        android.util.Log.i("BaasPreviewProbe", report.toString())
    }
}
