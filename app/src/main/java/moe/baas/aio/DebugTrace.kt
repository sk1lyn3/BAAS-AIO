package moe.baas.aio

import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File

/** Bounded debug history survives process death; diagnostics must never fail a task. */
object DebugTrace {
    private var directory: File? = null
    private val clock = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    @Synchronized fun attach(value: File) { if (BuildConfig.DEBUG) directory = value }
    fun log(message: String, level: Int = 0) {
        if (!BuildConfig.DEBUG) return
        Log.d("BaasRuntime", message)
        TaskRuntime.appendLog(level, "[runtime ${java.time.LocalTime.now().format(clock)}] $message")
    }
    @Synchronized fun persist(level: Int, message: String) {
        if (!BuildConfig.DEBUG) return
        val root = directory ?: return
        try {
            val file = File(root, "debug-runtime.jsonl")
            if (file.length() >= 1024 * 1024) {
                val previous = File(root, "debug-runtime.previous.jsonl")
                if (previous.exists()) check(previous.delete())
                check(file.renameTo(previous))
            }
            file.appendText(JSONObject().put("timeMs", System.currentTimeMillis())
                .put("uptimeMs", SystemClock.elapsedRealtime()).put("pid", android.os.Process.myPid())
                .put("thread", Thread.currentThread().name).put("level", level)
                .put("message", message).toString() + "\n")
        } catch (error: Exception) { Log.w("BaasRuntime", "Cannot persist debug history", error) }
    }
}
