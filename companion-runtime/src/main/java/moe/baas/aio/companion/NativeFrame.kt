package moe.baas.aio.companion

import android.os.SystemClock
import org.json.JSONObject

// Keep this constructor's JVM signature stable: the native capture bridge calls it.
class NativeFrame(
    val png: ByteArray,
    val width: Int,
    val height: Int,
    val frameId: Long,
    val timestampNs: Long,
    val meanRgb: Double,
    val nonblackFraction: Double,
) {
    var displayId = -1

    fun metadata(): JSONObject = JSONObject()
        .put("displayId", displayId).put("width", width).put("height", height)
        .put("frameId", frameId).put("timestampNs", timestampNs)
        .put("ageMs", maxOf(0L, (SystemClock.elapsedRealtimeNanos() - timestampNs) / 1_000_000))
        .put("meanRgb", meanRgb).put("nonblackFraction", nonblackFraction)
        .put("format", "png").put("payloadBytes", png.size)
}
