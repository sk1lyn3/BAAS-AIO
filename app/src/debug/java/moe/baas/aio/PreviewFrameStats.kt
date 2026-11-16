package moe.baas.aio

import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import moe.baas.aio.companion.NativeCaptureBridge
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Runs in Shizuku: read SF's existing layer history once a second, without a shell process. */
internal object PreviewFrameStats {
    private val whitespace = Regex("\\s+")
    private val surfaceFlinger by lazy {
        Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "SurfaceFlinger") as IBinder
    }
    @Volatile private var cachedLayer: Pair<String, String>? = null
    @Volatile private var unavailable = false
    @Volatile private var lastSource = -1
    @Volatile var lastSample = longArrayOf()
        private set

    fun invalidate() {
        cachedLayer = null
        unavailable = false
    }

    // [source (1=presented, 0=submitted), sampleTimeNs, layerKey, submittedCount,
    //  readCostNs, ...sorted unique actual-present timestamps]. All times are MONOTONIC.
    fun read(component: String): LongArray {
        require(component.startsWith(BuildConfig.APPLICATION_ID + "/") && component.length <= 256)
        val started = System.nanoTime()
        if (!unavailable) try {
            val layer = cachedLayer?.takeIf { it.first == component }?.second ?: run {
                val matches = dump(arrayOf("--list")).lineSequence()
                    .filter { it.startsWith("SurfaceView") && it.contains(component) }.toList()
                val candidates = matches.filter { it.contains("(BLAST)") }.ifEmpty { matches }
                candidates.maxByOrNull { it.substringAfterLast('#').toLongOrNull() ?: 0L }
                    ?.also { cachedLayer = component to it }
            }
            if (layer != null) {
                val before = System.nanoTime()
                val text = dump(arrayOf("--latency", layer))
                val after = System.nanoTime()
                var rows = 0
                val times = text.lineSequence().mapNotNull { line ->
                    val parts = line.trim().split(whitespace)
                    if (parts.size != 3 || parts.any { it.toLongOrNull() == null }) null
                    else {
                        rows++
                        parts[1].toLong().takeIf { it > 0 && it < Long.MAX_VALUE }
                    }
                }.distinct().sorted().toList()
                if (rows > 0) {
                    if (lastSource != 1) Log.i("BaasPreviewFps", "Using SurfaceFlinger actual-present history: $layer")
                    lastSource = 1
                    return (longArrayOf(1, before + (after - before) / 2,
                        layer.hashCode().toLong(), 0, after - started) + times.toLongArray())
                        .also { lastSample = it }
                }
                // A replaced Surface no longer has a history; rediscover it on the next sample.
                cachedLayer = null
            }
        } catch (error: Exception) {
            unavailable = true
            Log.i("BaasPreviewFps", "Presentation history unavailable; using submission rate", error)
        }
        if (lastSource != 0) Log.i("BaasPreviewFps", "Using preview submission rate")
        lastSource = 0
        val count = NativeCaptureBridge.previewFrameCount()
        val now = System.nanoTime()
        return longArrayOf(0, now, 0, count, now - started).also { lastSample = it }
    }

    private fun dump(arguments: Array<String>): String {
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            surfaceFlinger.dumpAsync(pipe[1].fileDescriptor, arguments)
            pipe[1].close()
            val poll = StructPollfd().apply {
                fd = pipe[0].fileDescriptor
                events = OsConstants.POLLIN.toShort()
            }
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            val deadline = System.nanoTime() + 250_000_000L
            while (true) {
                val remaining = ((deadline - System.nanoTime()) / 1_000_000).toInt()
                if (remaining <= 0 || Os.poll(arrayOf(poll), remaining) <= 0)
                    throw IOException("SurfaceFlinger statistics timed out")
                val count = Os.read(poll.fd, buffer, 0, buffer.size)
                if (count <= 0) break
                output.write(buffer, 0, count)
                check(output.size() <= 256 * 1024) { "SurfaceFlinger statistics exceed limit" }
            }
            return output.toString(Charsets.UTF_8.name())
        } finally {
            pipe[0].close()
            pipe[1].close()
        }
    }
}
