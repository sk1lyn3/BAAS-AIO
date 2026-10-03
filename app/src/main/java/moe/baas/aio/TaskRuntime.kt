package moe.baas.aio

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/** Task state is app-local; the Python thread acknowledges pause at safe checkpoints. */
object TaskRuntime {
    private val listeners = CopyOnWriteArraySet<(String) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var snapshotFile: java.io.File? = null
    fun storeIn(directory: java.io.File) { snapshotFile = java.io.File(directory, "task-state.json") }
    @Volatile var state = "idle"
        private set
    @Volatile var snapshot = JSONObject().put("state", "idle").toString()
        private set
    val isActive get() = state in setOf("starting", "running", "pausing", "paused", "stopping")
    val allowsManualInput get() = !isActive || state == "paused"
    fun observe(listener: (String) -> Unit) { listeners.add(listener); main.post { listener(snapshot) } }
    fun remove(listener: (String) -> Unit) { listeners.remove(listener) }
    @Synchronized fun publish(next: String, phase: String, progress: String = "{}") {
        state = next
        snapshot = JSONObject().put("state", next).put("phase", phase)
            .put("progress", JSONObject(progress)).toString(2)
        val value = snapshot
        snapshotFile?.let { target ->
            val file = android.util.AtomicFile(target)
            val stream = file.startWrite()
            try { stream.write(value.toByteArray()); file.finishWrite(stream) }
            catch (error: Throwable) { file.failWrite(stream); throw error }
        }
        main.post { listeners.forEach { it(value) } }
    }
}

class TaskGate {
    private val lock = Object()
    private var pause = false
    private var stop = false
    private var resumeGeneration = 0
    fun generation(): Int = synchronized(lock) { resumeGeneration }
    private var progress = "{}"
    fun requestPause() = synchronized(lock) {
        if (!stop) { pause = true; TaskRuntime.publish("pausing", "Waiting for the current operation", progress); lock.notifyAll() }
    }
    fun resume() = synchronized(lock) {
        if (!stop) { if (pause) resumeGeneration++; pause = false; TaskRuntime.publish("running", "Resuming", progress); lock.notifyAll() }
    }
    fun requestStop() = synchronized(lock) {
        stop = true; TaskRuntime.publish("stopping", "Releasing task resources", progress); lock.notifyAll()
    }
    fun checkpoint(phase: String): Boolean = synchronized(lock) {
        while (pause && !stop) { TaskRuntime.publish("paused", phase, progress); lock.wait() }
        if (stop) return false
        TaskRuntime.publish("running", phase, progress)
        true
    }
    fun progress(json: String) = synchronized(lock) {
        progress = json; TaskRuntime.publish(if (pause) "pausing" else "running", "Cycle completed", progress)
    }
    fun waitForNext(milliseconds: Long): Boolean {
        val end = android.os.SystemClock.elapsedRealtime() + milliseconds
        while (true) {
            if (!checkpoint("Waiting for next cycle")) return false
            synchronized(lock) {
                val remaining = end - android.os.SystemClock.elapsedRealtime()
                if (remaining <= 0) return true
                if (!pause && !stop) lock.wait(remaining)
            }
        }
    }
}
