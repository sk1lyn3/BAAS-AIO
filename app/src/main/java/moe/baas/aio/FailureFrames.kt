package moe.baas.aio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Images are private to the app until the user explicitly exports one. */
object FailureFrames {
    fun file(context: Context, frame: JSONObject?): File? {
        val name = frame?.optString("file").orEmpty()
        if (!name.matches(Regex("[0-9a-f-]{36}\\.png"))) return null
        return File(context.filesDir, "managed-failure-frames/$name")
    }

    fun pruneRecovery(context: Context, states: JSONObject, history: JSONArray) {
        val entries = JSONArray()
        for (id in states.keys()) entries.put(states.getJSONObject(id))
        prune(context, entries, history, JSONObject())
    }

    fun prune(context: Context, before: JSONArray, after: JSONArray, states: JSONObject) {
        val retained = mutableSetOf<String>()
        for (i in 0 until after.length()) after.optJSONObject(i)?.optJSONObject("frame")
            ?.optString("file")?.let { retained.add(it) }
        for (id in states.keys()) states.optJSONObject(id)?.optJSONObject("frame")
            ?.optString("file")?.let { retained.add(it) }
        for (i in 0 until before.length()) {
            val frame = before.optJSONObject(i)?.optJSONObject("frame")
            if (frame != null && frame.optString("file") !in retained) file(context, frame)?.delete()
        }
    }
}
