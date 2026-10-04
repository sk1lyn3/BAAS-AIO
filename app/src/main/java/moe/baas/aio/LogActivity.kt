package moe.baas.aio

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.File

class LogActivity : Activity() {
    private lateinit var output: TextView
    private val listener: (String) -> Unit = { if (!isDestroyed) render(it) }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        output = TextView(this).apply { textSize = 14f; setPadding(16, 16, 16, 16); setTextIsSelectable(true) }
        setContentView(ScrollView(this).apply { addView(output) })
        TaskRuntime.observeLogs(listener)
    }
    private fun render(value: String) {
        var entries = org.json.JSONArray(value)
        if (entries.length() == 0) {
            val file = File(filesDir, "task-report.json")
            if (file.exists()) entries = JSONObject(file.readText()).optJSONArray("log") ?: entries
        }
        output.text = (0 until entries.length()).joinToString("\n") { index ->
            val entry = entries.optJSONObject(index)
            entry?.optString("message") ?: entries.get(index).toString()
        }.ifBlank { "No task logs yet" }
    }
    override fun onDestroy() { TaskRuntime.removeLogListener(listener); super.onDestroy() }
}
