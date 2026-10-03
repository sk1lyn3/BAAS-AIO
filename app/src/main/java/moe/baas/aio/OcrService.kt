package moe.baas.aio

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process

/** Native OCR has process lifetime, independent of the Python workflow and UI. */
class OcrService : Service() {
    private var closing = false
    private val binder = object : IOcrService.Stub() {
        @Synchronized override fun start(projectDir: String) {
            check(!closing) { "OCR service is closing" }
            NativeOcr.start(projectDir)
        }
        @Synchronized override fun stop() {
            if (closing) return
            closing = true
            try { NativeOcr.stop() }
            finally {
                // Finish the Binder reply before terminating this service process.
                Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 100)
            }
        }
        override fun identity() = "uid=${Process.myUid()} pid=${Process.myPid()}"
    }
    override fun onBind(intent: Intent) = binder
    override fun onDestroy() {
        super.onDestroy()
        Process.killProcess(Process.myPid())
    }
}
