package moe.baas.aio.companion

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

class VirtualDisplayController {
    private var virtualDisplay: VirtualDisplay? = null
    private val handler = Handler(Looper.getMainLooper())
    private val keepAlive = object : Runnable {
        override fun run() = synchronized(this@VirtualDisplayController) {
            val id = virtualDisplay?.display?.displayId ?: return@synchronized
            try { SystemAccess.userActivity(id) }
            catch (error: Exception) { Log.e("BaasRuntime", "Virtual display activity failed: display=$id", error) }
            handler.postDelayed(this, 4000)
            Unit
        }
    }

    @Synchronized fun start(width: Int, height: Int, dpi: Int): Int {
        require(width in 1..4096 && height in 1..4096 && dpi in 1..1000) {
            "Invalid display size or density"
        }
        stop()
        val surface = NativeCaptureBridge.setup(width, height)
            ?: error("Native capture setup failed")
        try {
            virtualDisplay = SystemAccess.displayManager().createVirtualDisplay(
                "baas-aio", width, height, dpi, surface, buildFlags())
        } finally {
            surface.release()
        }
        val id = virtualDisplay?.display?.displayId ?: error("Virtual display creation failed")
        refreshInitialActivity(id)
        handler.postDelayed(keepAlive, 4000)
        Log.i("BaasRuntime", "Virtual display activity started: display=$id intervalMs=4000")
        return id
    }

    private fun refreshInitialActivity(id: Int) {
        // Virtual-display power management may lag behind display creation: Android can
        // expose a display ID before its PowerGroup is ready, causing userActivity to NPE
        // at PowerGroup.getGroupId(). Retry only this initial call, on the same display,
        // at most three times (300 ms total); other errors retain normal failure cleanup.
        for (attempt in 0..3) {
            try {
                SystemAccess.userActivity(id)
                if (attempt > 0) Log.i("BaasRuntime", "Virtual display power group ready: display=$id retries=$attempt")
                return
            } catch (error: IllegalStateException) {
                val cause = error.cause
                val pendingPowerGroup = cause is NullPointerException &&
                    cause.message.orEmpty().contains("com.android.server.power.PowerGroup.getGroupId()")
                if (!pendingPowerGroup || attempt == 3) throw error
                Log.w("BaasRuntime", "Virtual display power group not ready: display=$id retry=${attempt + 1}/3 waitMs=100", error)
                SystemClock.sleep(100)
            }
        }
    }

    @Synchronized fun stop() {
        handler.removeCallbacks(keepAlive)
        try {
            val previous = virtualDisplay
            virtualDisplay = null
            previous?.release()
        } finally {
            NativeCaptureBridge.release()
        }
    }

    @Synchronized fun isAlive() = virtualDisplay != null

    private fun buildFlags(): Int {
        var flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            (1 shl 6) or (1 shl 8) // SUPPORTS_TOUCH, DESTROY_CONTENT_ON_REMOVAL
        if (Build.VERSION.SDK_INT >= 33) {
            flags = flags or (1 shl 10) or (1 shl 11) or (1 shl 12) or (1 shl 13)
            // TRUSTED, OWN_DISPLAY_GROUP, ALWAYS_UNLOCKED, TOUCH_FEEDBACK_DISABLED
        }
        if (Build.VERSION.SDK_INT >= 34) {
            flags = flags or (1 shl 14) or (1 shl 16) // OWN_FOCUS, STEAL_TOP_FOCUS_DISABLED
        }
        return flags
    }
}
