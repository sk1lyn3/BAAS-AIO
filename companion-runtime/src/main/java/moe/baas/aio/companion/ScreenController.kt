package moe.baas.aio.companion

import android.os.IBinder
import android.os.PowerManager
import java.util.concurrent.TimeUnit

/** Explicit one-shot commands, without a background screen policy. */
class ScreenController {
    private var panelOff = false
    private var displayControl: Class<*>? = null

    @Synchronized fun wake(): Boolean {
        val power = SystemAccess.context().getSystemService("power") as PowerManager
        if (panelOff) {
            applyPanelPower(false)
            panelOff = false
        }
        if (!SystemAccess.keyEvent(0, 224)) return false
        val dismiss = ProcessBuilder("wm", "dismiss-keyguard").redirectErrorStream(true).start()
        if (!dismiss.waitFor(5, TimeUnit.SECONDS)) dismiss.destroy()
        return power.isInteractive // Does not unlock a PIN-protected keyguard.
    }

    @Synchronized fun setPanelOff(off: Boolean): Boolean {
        applyPanelPower(off)
        panelOff = off
        return true
    }

    private fun applyPanelPower(off: Boolean) {
        val surfaceControl = Class.forName("android.view.SurfaceControl")
        val control = displayControl ?: run {
            var candidate = surfaceControl
            try {
                candidate.getMethod("getPhysicalDisplayIds")
            } catch (_: NoSuchMethodException) {
                val factory = Class.forName("com.android.internal.os.ClassLoaderFactory")
                val create = factory.getDeclaredMethod("createClassLoader", String::class.java,
                    String::class.java, String::class.java, ClassLoader::class.java,
                    Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, String::class.java)
                val loader = create.invoke(null, System.getenv("SYSTEMSERVERCLASSPATH"),
                    null, null, ClassLoader.getSystemClassLoader(), 0, true, null) as ClassLoader
                candidate = loader.loadClass("com.android.server.display.DisplayControl")
                val load = Runtime::class.java.getDeclaredMethod(
                    "loadLibrary0", Class::class.java, String::class.java)
                load.isAccessible = true
                load.invoke(Runtime.getRuntime(), candidate, "android_servers")
            }
            candidate.also { displayControl = it }
        }
        val ids = control.getMethod("getPhysicalDisplayIds").invoke(null) as? LongArray
        check(ids != null && ids.isNotEmpty()) { "No physical displays" }
        val token = control.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
        val mode = surfaceControl.getMethod("setDisplayPowerMode", IBinder::class.java, Int::class.javaPrimitiveType)
        for (id in ids) {
            val binder = token.invoke(null, id) as? IBinder ?: error("No physical display token")
            mode.invoke(null, binder, if (off) 0 else 2)
        }
    }

    @Synchronized fun isPanelOff() = panelOff
    @Synchronized fun getGuardError() = "" // Kept for protocol compatibility.
    @Synchronized fun release() { panelOff = false }
}
