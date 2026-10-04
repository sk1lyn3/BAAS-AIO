package moe.baas.aio.companion

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit

object SystemAccess {
    const val DISPLAY_NONE = -1
    private const val INJECT_ASYNC = 0
    private const val INJECT_WAIT_FOR_FINISH = 2
    private val injectionError = ThreadLocal<String>()
    internal fun lastInjectionError() = injectionError.get() ?: "Unknown injection failure"

    private var systemContext: Context? = null
    private var injectInputEventMethod: Method? = null
    private var setDisplayIdMethod: Method? = null

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    @Synchronized fun context(): Context {
        systemContext?.let { return it }
        try {
            val at = Class.forName("android.app.ActivityThread")
            val thread = at.getDeclaredMethod("currentActivityThread").invoke(null)
                ?: at.getDeclaredMethod("systemMain").invoke(null)
            val context = thread.javaClass.getDeclaredMethod("getSystemContext").invoke(thread) as Context
            systemContext = context
            return context
        } catch (e: Exception) {
            throw IllegalStateException("Unable to obtain system context", e)
        }
    }

    fun displayManager() = context().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    fun launchOnDisplay(packageName: String?, activityName: String?, displayId: Int): Boolean {
        if (displayId == DISPLAY_NONE || packageName.isNullOrBlank()) return false
        try {
            context().packageManager.getApplicationInfo(packageName.trim(), 0)
        } catch (_: PackageManager.NameNotFoundException) { return false }
        val component = resolveComponent(packageName, activityName)
            ?: return startViaAm(packageName, activityName, displayId)
        return try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            val options = ActivityOptions.makeBasic().apply { launchDisplayId = displayId }
            context().startActivity(intent, options.toBundle())
            true
        } catch (_: Throwable) { startViaAm(component.flattenToString(), displayId) }
    }

    private fun resolveComponent(packageName: String, activityName: String?): ComponentName? {
        val pkg = packageName.trim()
        if (!activityName.isNullOrBlank()) {
            val cls = activityName.trim()
            return ComponentName(pkg, if (cls.startsWith(".")) pkg + cls else cls)
        }
        try {
            val pm = context().packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
            pm.queryIntentActivities(intent, 0).firstOrNull()?.activityInfo?.let {
                return ComponentName(it.packageName, it.name)
            }
            return pm.getLaunchIntentForPackage(pkg)?.component
        } catch (_: Throwable) { return null }
    }

    private fun startViaAm(packageName: String, activityName: String?, displayId: Int): Boolean {
        if (!activityName.isNullOrBlank()) {
            val cls = if (activityName.startsWith(".")) packageName + activityName else activityName
            return startViaAm("$packageName/$cls", displayId)
        }
        return startViaAm(packageName, displayId)
    }

    private fun startViaAm(target: String, displayId: Int): Boolean = try {
        val args = if (target.contains('/')) {
            listOf("am", "start", "--display", displayId.toString(), "-n", target)
        } else {
            listOf("am", "start", "--display", displayId.toString(),
                "-a", Intent.ACTION_MAIN, "-c", Intent.CATEGORY_LAUNCHER, "-p", target)
        }
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroy()
            false
        } else {
            val text = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            process.exitValue() == 0 && !text.contains("Error:") && !text.contains("Exception")
        }
    } catch (_: Throwable) { false }

    fun click(displayId: Int, x: Int, y: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = motion(now, now, MotionEvent.ACTION_DOWN, x, y)
        var up: MotionEvent? = null
        var released = false
        try {
            if (!inject(down, displayId, INJECT_WAIT_FOR_FINISH)) return false
            SystemClock.sleep(50)
            up = motion(now, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y)
            // An accepted DOWN may dismiss its window; queue release without retrying the press.
            released = inject(up, displayId, INJECT_ASYNC)
            return released
        } finally {
            if (!released) {
                // A failed result does not prove DOWN was undelivered. Cancel, never replay it.
                val failure = lastInjectionError()
                val cancel = motion(now, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, x, y)
                try { inject(cancel, displayId, INJECT_ASYNC) }
                finally { cancel.recycle(); injectionError.set(failure) }
            }
            down.recycle()
            up?.recycle()
        }
    }

    fun swipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean {
        val duration = maxOf(80, durationMs)
        val downTime = SystemClock.uptimeMillis()
        val down = motion(downTime, downTime, MotionEvent.ACTION_DOWN, x1, y1)
        try {
            if (!inject(down, displayId, INJECT_WAIT_FOR_FINISH)) return false
        } finally { down.recycle() }
        val endTime = downTime + duration
        var success = true
        while (SystemClock.uptimeMillis() < endTime) {
            val p = minOf(1f, (SystemClock.uptimeMillis() - downTime) / duration.toFloat())
            val move = motion(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE,
                Math.round(x1 + (x2 - x1) * p), Math.round(y1 + (y2 - y1) * p))
            try {
                if (!inject(move, displayId, INJECT_ASYNC)) {
                    success = false
                    break
                }
            } finally { move.recycle() }
            SystemClock.sleep(minOf(16L, maxOf(1L, endTime - SystemClock.uptimeMillis())))
        }
        val up = motion(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x2, y2)
        try {
            val released = inject(up, displayId, INJECT_WAIT_FOR_FINISH)
            return success && released
        } finally { up.recycle() }
    }

    fun keyEvent(displayId: Int, keyCode: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
        if (!inject(down, displayId, INJECT_WAIT_FOR_FINISH)) return false
        SystemClock.sleep(20)
        val up = KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0)
        return inject(up, displayId, INJECT_WAIT_FOR_FINISH)
    }

    fun pinch(displayId: Int, cx: Int, cy: Int, start: Int, end: Int, duration: Int): Boolean {
        val downTime = SystemClock.uptimeMillis()
        var active = 0
        var spread = start.toFloat()
        try {
            if (!touchPointers(displayId, downTime, MotionEvent.ACTION_DOWN, cx, cy, spread, 1)) return false
            active = 1
            if (!touchPointers(displayId, downTime,
                    MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    cx, cy, spread, 2)) return false
            active = 2
            val deadline = downTime + duration
            while (SystemClock.uptimeMillis() < deadline) {
                val p = minOf(1f, (SystemClock.uptimeMillis() - downTime) / duration.toFloat())
                spread = start + (end - start) * p
                if (!touchPointers(displayId, downTime, MotionEvent.ACTION_MOVE, cx, cy, spread, 2)) return false
                SystemClock.sleep(minOf(16L, maxOf(1L, deadline - SystemClock.uptimeMillis())))
            }
            spread = end.toFloat()
            if (!touchPointers(displayId, downTime, MotionEvent.ACTION_MOVE, cx, cy, spread, 2)) return false
            if (!touchPointers(displayId, downTime,
                    MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    cx, cy, spread, 2)) return false
            active = 1
            if (!touchPointers(displayId, downTime, MotionEvent.ACTION_UP, cx, cy, spread, 1)) return false
            active = 0
            return true
        } finally {
            if (active > 0) touchPointers(displayId, downTime, MotionEvent.ACTION_CANCEL, cx, cy, spread, active)
        }
    }

    private fun touchPointers(displayId: Int, downTime: Long, action: Int,
                              cx: Int, cy: Int, spread: Float, count: Int): Boolean {
        val properties = Array(count) { i ->
            MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER }
        }
        val coords = Array(count) { i ->
            MotionEvent.PointerCoords().apply {
                x = cx + if (i == 0) -spread else spread
                y = cy.toFloat()
                pressure = 1f
                size = 1f
            }
        }
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, count,
            properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { return inject(event, displayId, INJECT_WAIT_FOR_FINISH) }
        finally { event.recycle() }
    }

    private fun motion(downTime: Long, eventTime: Long, action: Int, x: Int, y: Int) =
        MotionEvent.obtain(downTime, eventTime, action, x.toFloat(), y.toFloat(), 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }

    @SuppressLint("PrivateApi", "SoonBlockedPrivateApi")
    internal fun inject(event: InputEvent, displayId: Int, mode: Int): Boolean {
        injectionError.remove()
        val action = if (event is MotionEvent) MotionEvent.actionToString(event.action) else "key"
        val detail = "display=$displayId action=$action mode=$mode"
        if (displayId == DISPLAY_NONE) {
            injectionError.set("No display: $detail")
            return false
        }
        return try {
            val setDisplay = setDisplayIdMethod ?: InputEvent::class.java
                .getMethod("setDisplayId", Int::class.javaPrimitiveType).also { setDisplayIdMethod = it }
            setDisplay.invoke(event, displayId)
            val injectInput = injectInputEventMethod ?: InputManager::class.java
                .getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
                .also { injectInputEventMethod = it }
            val manager = context().getSystemService(Context.INPUT_SERVICE) as InputManager
            (injectInput.invoke(manager, event, mode) as Boolean).also { accepted ->
                if (!accepted) injectionError.set("Android rejected injection (delivery unknown): $detail")
            }
        } catch (error: Throwable) {
            val cause = if (error is InvocationTargetException) error.targetException else error
            injectionError.set("${cause.javaClass.simpleName}: ${cause.message}; $detail (delivery unknown)")
            false
        }
    }
}
