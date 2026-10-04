package moe.baas.aio

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Measures view-dispatched input to observable pixels on the real preview Surface. */
internal object LatencyVerifier {
    @Volatile private var owner: Activity? = null
    private var dispatching = false // Accessed only on the UI thread.
    fun consumesManualInput(activity: Activity) = owner === activity && !dispatching
    fun run(activity: Activity, surface: SurfaceView, idle: () -> Unit) = thread(name = "preview-latency") {
        owner = activity
        val result = JSONObject().put("ok", false)
        val samples = mutableListOf<Double>()
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        fun copy(x: Float, y: Float): Boolean {
            val cx = (x * surface.width).toInt(); val cy = (y * surface.height).toInt()
            val done = CountDownLatch(1)
            var status = -1
            activity.runOnUiThread {
                PixelCopy.request(surface, Rect(cx - 4, cy - 4, cx + 4, cy + 4), bitmap,
                    { status = it; done.countDown() }, Handler(Looper.getMainLooper()))
            }
            check(done.await(2, TimeUnit.SECONDS) && status == PixelCopy.SUCCESS) { "PixelCopy failed: $status" }
            val color = bitmap.getPixel(4, 4)
            return android.graphics.Color.red(color) > 220 && android.graphics.Color.green(color) > 220 && android.graphics.Color.blue(color) < 40
        }
        fun click(x: Float, y: Float) {
            val done = CountDownLatch(1)
            var accepted = false
            activity.runOnUiThread {
                val now = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x * surface.width, y * surface.height, 0)
                val up = MotionEvent.obtain(now, now + 1, MotionEvent.ACTION_UP, x * surface.width, y * surface.height, 0)
                down.source = InputDevice.SOURCE_TOUCHSCREEN; up.source = InputDevice.SOURCE_TOUCHSCREEN
                dispatching = true
                try { accepted = surface.dispatchTouchEvent(down) && surface.dispatchTouchEvent(up) }
                finally { dispatching = false; down.recycle(); up.recycle(); done.countDown() }
            }
            check(done.await(2, TimeUnit.SECONDS) && accepted) { "Touch was rejected" }
            idle()
        }
        try {
            Thread.sleep(700)
            repeat(24) { index ->
                val x = if (index % 2 == 0) .3125f else .6875f
                val y = .5f
                check(!copy(x, y)) { "Target already yellow before input" }
                val start = SystemClock.elapsedRealtimeNanos()
                click(x, y)
                while (!copy(x, y)) {
                    check(SystemClock.elapsedRealtimeNanos() - start < 2_000_000_000L) { "Preview response exceeded two seconds" }
                    Thread.sleep(2)
                }
                samples.add((SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0)
                Thread.sleep(25)
            }
            val sorted = samples.sorted()
            result.put("ok", true).put("samplesMs", JSONArray(samples))
                .put("medianMs", sorted[sorted.size / 2]).put("p90Ms", sorted[(sorted.size * .9).toInt()])
                .put("maxMs", sorted.last()).put("measurement", "Preview view touch dispatch to yellow marker in native Surface; includes PixelCopy overhead")
        } catch (error: Throwable) { result.put("error", android.util.Log.getStackTraceString(error)) }
        finally { bitmap.recycle(); if (owner === activity) owner = null }
        File(activity.filesDir, "latency-report.json").writeText(result.toString(2))
        android.util.Log.i("BaasPreviewLatency", result.toString())
    }
}
