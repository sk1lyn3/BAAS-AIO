package moe.baas.aio.companion

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build

class VirtualDisplayController {
    private var virtualDisplay: VirtualDisplay? = null

    fun start(width: Int, height: Int, dpi: Int): Int {
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
        return virtualDisplay?.display?.displayId ?: error("Virtual display creation failed")
    }

    fun stop() {
        try {
            val previous = virtualDisplay
            virtualDisplay = null
            previous?.release()
        } finally {
            NativeCaptureBridge.release()
        }
    }

    fun isAlive() = virtualDisplay != null

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
