package moe.baas.aio.companion

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Handler
import android.os.Looper
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
        SystemAccess.userActivity(id)
        handler.postDelayed(keepAlive, 4000)
        Log.i("BaasRuntime", "Virtual display activity started: display=$id intervalMs=4000")
        return id
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
