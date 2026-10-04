package moe.baas.aio

import android.content.Context
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Presentation only; keeps original core log messages intact. */
object UiLogs {
    data class Result(val text: CharSequence, val total: Int, val count: Int)
    private val labels = arrayOf("LOG", "INFO", "WARNING", "ERROR", "CRITICAL")
    private val colors = intArrayOf(UiTheme.muted, Color.rgb(96, 165, 250), Color.rgb(245, 158, 11), Color.rgb(248, 113, 113), Color.rgb(192, 132, 252))
    fun render(context: Context, value: String, query: String = "", filter: Int = 0): Result {
        var entries = JSONArray(value)
        if (entries.length() == 0) {
            val report = File(context.filesDir, "task-report.json")
            if (report.isFile) entries = runCatching { JSONObject(report.readText()).optJSONArray("log") }.getOrNull() ?: entries
        }
        val output = SpannableStringBuilder()
        var count = 0
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            val level = entry.optInt("level", 1).coerceIn(0, 4)
            val message = entry.optString("message")
            if ((filter != 0 && level != filter) || !message.contains(query, ignoreCase = true)) continue
            if (count++ > 0) output.append("\n")
            val start = output.length
            output.append("[${labels[level]}] ")
            output.setSpan(ForegroundColorSpan(colors[level]), start, output.length, 0)
            output.append(message)
        }
        val text = if (output.isEmpty()) (if (query.isBlank() && filter == 0) "暂无日志" else "没有匹配的日志") else output
        return Result(text, entries.length(), count)
    }
}
