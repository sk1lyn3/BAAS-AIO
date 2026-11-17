package moe.baas.aio

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/** Two bounded observations per stalled frame; no input, tracing or recovery actions. */
internal object StartupStallProbe {
    fun capture(state: JSONObject): JSONObject {
        val started = SystemClock.elapsedRealtime()
        val result = JSONObject().put("startedUptimeMs", started)
        val packageName = state.optString("currentPackage")
        val pid = state.optJSONObject("gamePids")?.optString(packageName)?.split(' ')
            ?.firstOrNull()?.toIntOrNull()
        if (pid != null) {
            val root = File("/proc/$pid")
            val threads = root.resolve("task").listFiles().orEmpty().map { dir -> dir to read(dir.resolve("comm")).trim() }
                .filter { (dir, name) -> dir.name == pid.toString() || name.contains("Unity") || name == "RenderThread" }
                .sortedBy { (dir, name) -> when { dir.name == pid.toString() -> 0; name == "UnityMain" -> 1; name.startsWith("UnityGfx") -> 2; else -> 3 } }
                .take(8).map { (dir, _) -> thread(dir) }
            result.put("gameProcess", JSONObject().put("pid", pid).put("stat", read(root.resolve("stat")))
                .put("status", read(root.resolve("status"))).put("threads", JSONArray(threads)))
        }
        val captureTid = state.optJSONObject("captureProbe")?.optInt("workerTid", 0) ?: 0
        if (captureTid > 0) result.put("captureThread", thread(File("/proc/self/task/$captureTid")))
        if (packageName.isNotBlank()) {
            result.put("activity", context(command("dumpsys", "-t", "1", "activity", "activities"), packageName))
            result.put("windows", context(command("dumpsys", "-t", "1", "window", "windows"), packageName))
            val layerList = command("dumpsys", "-t", "1", "SurfaceFlinger", "--list")
            val layers = layerList.lineSequence()
                .filter { it.contains(packageName) && it.contains("SurfaceView") }.take(4).toList()
            result.put("gameLayers", JSONArray(layers))
            if (layers.isEmpty()) result.put("layerQueryNote", layerList.take(1500))
            val samples = layers.filter { it.contains("BLAST") }.ifEmpty { layers }.take(2).map { layer ->
                JSONObject().put("layer", layer)
                    .put("latency", command("dumpsys", "-t", "1", "SurfaceFlinger", "--latency", layer).take(12000))
            }
            result.put("gamePresentation", JSONArray(samples))
        }
        return result.put("elapsedMs", SystemClock.elapsedRealtime() - started)
    }

    private fun thread(root: File) = JSONObject().put("tid", root.name)
        .put("name", read(root.resolve("comm")).trim()).put("stat", read(root.resolve("stat")))
        .put("waitChannel", read(root.resolve("wchan")).trim())

    private fun read(file: File): String = try { file.bufferedReader().use { it.readText().take(4000) } }
        catch (error: Exception) { "unavailable: ${error.javaClass.simpleName}" }

    private fun context(dump: String, packageName: String): String {
        val lines = dump.lines()
        val selected = sortedSetOf<Int>()
        lines.forEachIndexed { i, line ->
            if (line.contains(packageName) || line.contains("ResumedActivity") || line.contains("mCurrentFocus") || line.contains("mFocusedApp"))
                selected.addAll(maxOf(0, i - 2)..minOf(lines.lastIndex, i + 12))
        }
        return if (selected.isEmpty()) dump.take(1500) else selected.joinToString("\n") { lines[it] }.take(18000)
    }

    private fun command(vararg arguments: String): String {
        var process: Process? = null
        return try {
            process = ProcessBuilder(*arguments).redirectErrorStream(true).start()
            val output = ByteArrayOutputStream()
            process.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (output.size() < 256 * 1024) {
                    val size = input.read(buffer, 0, minOf(buffer.size, 256 * 1024 - output.size()))
                    if (size < 0) break
                    output.write(buffer, 0, size)
                }
            }
            if (!process.waitFor(200, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            output.toString("UTF-8")
        } catch (error: Exception) { "unavailable: ${error.javaClass.simpleName}: ${error.message}" }
        finally { process?.destroy() }
    }
}
