package moe.baas.aio

import android.content.Context
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Debug observations of display/game lifetime; never changes runtime behavior. */
class DisplayDiagnostics(
    private val context: Context,
    private val source: String,
    private val broker: () -> ISocketBroker?,
    private val details: () -> String = { "" }
) {
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private var signature = ""
    private var capturedFrame = ""
    private var stalledFrame = ""
    private var stallSamples = 0
    @Volatile private var stopped = false
    private var followup: java.util.concurrent.ScheduledFuture<*>? = null
    fun start() {
        DebugTrace.attach(context.filesDir)
        worker.scheduleWithFixedDelay({ capture("sample", false) }, 0, 15, TimeUnit.SECONDS)
    }
    private fun capture(reason: String, detailed: Boolean) {
        try {
            val client = broker()
            val state = if (client != null) JSONObject(client.debugRuntimeState(false)) else JSONObject()
            state.put("taskState", TaskRuntime.state)
                .put("managedExecuting", ManagedMode.executionActive)
                .put("view", details())
            val stale = state.optBoolean("alive") && state.optLong("frameAgeMs", -1) > 5000
            state.put("frameStale", stale)
            val frameKey = "${state.optInt("displayId", -1)}/${state.optLong("frameId")}"
            if (!stale || frameKey != stalledFrame) { stalledFrame = if (stale) frameKey else ""; stallSamples = 0 }
            val sampleStall = stale && stallSamples < 2
            if (client != null && state.optBoolean("alive") && state.optLong("frameId") > 0 && frameKey != capturedFrame) runCatching {
                // A separate transport observes capture content without touching workflow channels.
                val frame = CompanionBridge(client).use { it.screenshot() }
                val directory = File(context.filesDir, "display-diagnostics").apply { mkdirs() }
                val latest = File(directory, "$source-latest.png")
                val previous = File(directory, "$source-previous.png")
                if (latest.exists()) latest.copyTo(previous, overwrite = true)
                latest.writeBytes(frame.png)
                val metadata = JSONObject(frame.metadata)
                capturedFrame = "${metadata.optInt("displayId", -1)}/${metadata.optLong("frameId")}"
                state.put("capturePath", latest.absolutePath).put("captureMetadata", JSONObject(frame.metadata))
                val image = BitmapFactory.decodeByteArray(frame.png, 0, frame.png.size,
                    BitmapFactory.Options().apply { inSampleSize = 8 })
                if (image != null) {
                    try {
                        var dark = 0; var samples = 0
                        for (y in 0 until image.height step maxOf(1, image.height / 12)) {
                            for (x in 0 until image.width step maxOf(1, image.width / 24)) {
                                val pixel = image.getPixel(x, y)
                                if ((pixel shr 16 and 255) < 12 && (pixel shr 8 and 255) < 12 && (pixel and 255) < 12) dark++
                                samples++
                            }
                        }
                        state.put("darkPixelFraction", dark.toDouble() / samples)
                    } finally { image.recycle() }
                }
            }.onFailure { state.put("captureError", it.toString()) }
            val current = listOf(state.optBoolean("alive"), state.optBoolean("systemDisplayPresent"), state.optInt("displayId", -1), state.optString("currentPackage"), state.optJSONObject("gamePids"), stale).joinToString("/")
            if (client != null && (detailed || current != signature || sampleStall)) {
                state.put("diagnostic", JSONObject(if (sampleStall) client.debugStallState() else client.debugRuntimeState(true)))
            }
            if (sampleStall) {
                stallSamples++
                val directory = File(context.filesDir, "display-diagnostics").apply { mkdirs() }
                val latest = File(directory, "$source-stall.json")
                if (latest.exists()) latest.copyTo(File(directory, "$source-stall.previous.json"), overwrite = true)
                latest.writeText(state.toString(2))
                if (stallSamples == 1 && !stopped) followup = worker.schedule({
                    if (!stopped) capture("stale-followup", false)
                }, 3, TimeUnit.SECONDS)
            }
            signature = current
            DebugTrace.log("Display diagnostic source=$source reason=$reason state=$state")
        } catch (error: Exception) {
            DebugTrace.log("Display diagnostic unavailable source=$source reason=$reason error=$error")
        }
    }
    @Synchronized fun stop() {
        if (stopped) return
        stopped = true
        followup?.cancel(false)
        worker.execute { capture("observer-stopped", true) }
        worker.shutdown()
    }
}
