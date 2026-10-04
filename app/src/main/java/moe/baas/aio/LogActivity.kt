package moe.baas.aio

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import moe.baas.aio.UiTheme.item

class LogActivity : Activity() {
    private lateinit var output: TextView
    private lateinit var count: TextView
    private val listener: (String) -> Unit = { if (!isDestroyed) render(it) }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(UiTheme.pale)
            val p = UiTheme.dp(this@LogActivity, 12); setPadding(p, p, p, p)
        }
        root.item(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            item(UiTheme.text(this@LogActivity, "运行日志", 20f), 1f)
            item(UiTheme.button(this@LogActivity, "返回") { finish() }, 1f)
        })
        count = UiTheme.text(this, "", 12f, UiTheme.muted); root.item(count)
        output = UiTheme.text(this, "", 12f, Color.rgb(203, 213, 225)).apply {
            typeface = Typeface.MONOSPACE; setTextIsSelectable(true)
        }
        root.addView(ScrollView(this).apply {
            background = UiTheme.background(this@LogActivity, Color.rgb(15, 23, 42))
            val p = UiTheme.dp(this@LogActivity, 8); setPadding(p, p, p, p); addView(output)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root); TaskRuntime.observeLogs(listener)
    }
    private fun render(value: String) {
        val result = UiLogs.render(this, value)
        count.text = "共 ${result.total} 条"; output.text = result.text
    }
    override fun onDestroy() { TaskRuntime.removeLogListener(listener); super.onDestroy() }
}
