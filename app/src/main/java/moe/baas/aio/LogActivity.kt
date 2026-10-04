package moe.baas.aio

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Button
import moe.baas.aio.UiTheme.item

class LogActivity : Activity() {
    private lateinit var output: TextView
    private lateinit var count: TextView
    private lateinit var scroll: ScrollView
    private lateinit var follow: Button
    private var follows = true
    private var query = ""
    private var level = 0
    private val listener: (String) -> Unit = { if (!isDestroyed) render(it) }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        follows = state?.getBoolean("follow") ?: intent.getBooleanExtra("follow", true)
        query = state?.getString("query") ?: intent.getStringExtra("query") ?: ""
        level = state?.getInt("level") ?: intent.getIntExtra("level", 0)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(UiTheme.pale)
            val p = UiTheme.dp(this@LogActivity, 12); setPadding(p, p, p, p)
        }
        root.item(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            item(UiTheme.text(this@LogActivity, "运行日志", 20f), 1f)
            follow = UiTheme.flatButton(this@LogActivity, "自动滚动") {
                follows = !follows; updateFollow(); if (follows) latest()
            }; addView(follow, LinearLayout.LayoutParams(-2, -2))
            addView(UiTheme.flatButton(this@LogActivity, "返回") { finish() }, LinearLayout.LayoutParams(-2, -2))
        })
        val filters = LogFilters(this, query, level) { search, selected ->
            query = search; level = selected; render(TaskRuntime.logSnapshot())
        }
        root.item(filters.search)
        root.item(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            item(filters.levels, 1f)
            count = UiTheme.text(this@LogActivity, "", 10f, UiTheme.muted).apply { gravity = android.view.Gravity.END }
            addView(count, LinearLayout.LayoutParams(UiTheme.dp(this@LogActivity, 80), -2))
        })
        output = UiLogs.textView(this)
        scroll = ScrollView(this).apply {
            background = UiTheme.background(this@LogActivity, Color.rgb(15, 23, 42))
            val p = UiTheme.dp(this@LogActivity, 8); setPadding(p, p, p, p); addView(output)
        }; root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root); updateFollow(); TaskRuntime.observeLogs(listener)
    }
    private fun render(value: String) {
        val result = UiLogs.render(this, value, query, level)
        count.text = "${result.count}/${result.total} 条"; output.text = result.text
        if (follows) latest()
    }
    private fun latest() = scroll.post { if (follows) scroll.scrollTo(0, output.height + scroll.paddingBottom) }
    private fun updateFollow() = UiTheme.setToggleSelected(follow, follows)
    override fun onSaveInstanceState(state: Bundle) {
        state.putBoolean("follow", follows); state.putString("query", query); state.putInt("level", level)
        super.onSaveInstanceState(state)
    }
    override fun onDestroy() { TaskRuntime.removeLogListener(listener); super.onDestroy() }
}
