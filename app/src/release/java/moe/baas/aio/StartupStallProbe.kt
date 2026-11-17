package moe.baas.aio

import org.json.JSONObject

/** Release contains no process/window/SurfaceFlinger diagnostic commands. */
internal object StartupStallProbe {
    fun capture(state: JSONObject) = JSONObject()
}
