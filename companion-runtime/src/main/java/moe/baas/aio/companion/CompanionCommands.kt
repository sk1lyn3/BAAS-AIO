package moe.baas.aio.companion

import android.os.SystemClock
import org.json.JSONObject

/** Shared command contract for ADB bootstrap and the persistent socket. */
object CompanionCommands {
    fun execute(service: ICompanionService, request: JSONObject): JSONObject {
        val cmd = request.optString("cmd", "status")
        val result = JSONObject().put("ok", true).put("cmd", cmd)
            .put("requestId", request.optString("requestId", "")).put("apiVersion", 1)
        fun checked(ok: Boolean, error: String) {
            if (!ok) result.put("ok", false).put("error", error)
        }
        when (cmd) {
            "ping" -> result.put("message", service.ping())
            "start" -> {
                val id = service.startVirtualDisplay(request.optInt("width", 1280),
                    request.optInt("height", 720), request.optInt("dpi", 240))
                result.put("displayId", id)
                checked(id >= 0, "Virtual display creation failed: ${service.status}")
            }
            "stop" -> { service.stopVirtualDisplay(); result.put("stopped", true) }
            "display" -> result.put("displayId", service.displayId)
            "status" -> result.put("status", service.status).put("displayId", service.displayId)
                .put("state", JSONObject(service.stateJson))
            "launch" -> {
                val ok = service.launchApp(request.optString("package", ""), request.optString("activity", ""))
                result.put("launched", ok); checked(ok, "App launch failed")
            }
            "click" -> {
                val x = request.optInt("x", 640)
                val y = request.optInt("y", 360)
                if (service is CompanionUserService) {
                    val detail = service.clickResult(x, y)
                    detail.keys().forEach { key -> result.put(key, detail.get(key)) }
                } else {
                    val ok = service.click(x, y)
                    result.put("clicked", ok); checked(ok, "Click failed (delivery unknown)")
                }
            }
            "swipe" -> {
                val duration = request.optInt("duration", 450)
                require(duration in 1..10000) { "Swipe duration must be 1..10000 ms" }
                val ok = service.swipe(request.optInt("x1", 640), request.optInt("y1", 600),
                    request.optInt("x2", 640), request.optInt("y2", 220), duration)
                result.put("swiped", ok); checked(ok, "Swipe failed")
            }
            "key" -> {
                val ok = service.keyEvent(request.optInt("keyCode", 4))
                result.put("sent", ok); checked(ok, "Key event failed")
            }
            "pinch" -> {
                val ok = service.pinch(request.getInt("cx"), request.getInt("cy"),
                    request.getInt("start"), request.getInt("end"), request.optInt("duration", 480))
                result.put("pinched", ok); checked(ok, "Pinch failed")
            }
            "capture" -> {
                val path = service.capturePng(request.optString("path", "/sdcard/Download/baas_companion_frame.png"))
                result.put("path", path); checked(path.isNotEmpty(), "No frame or PNG write failed")
            }
            "cleanup" -> result.put("cleaned", service.cleanupStaleServices())
            "wake" -> {
                val ok = service.wakeDevice()
                result.put("awake", ok); checked(ok, "Wake failed: ${service.status}")
            }
            "screen" -> {
                val off = request.optBoolean("off", true)
                val ok = service.setPhysicalScreenOff(off)
                result.put("panelOff", off); checked(ok, "Panel power change failed: ${service.status}")
            }
            else -> checked(false, "Unknown cmd: $cmd")
        }
        return result.put("completedNs", SystemClock.elapsedRealtimeNanos())
    }
}
