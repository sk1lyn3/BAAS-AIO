package moe.baas.aio

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Button
import moe.baas.aio.UiTheme.item

class LogActivity : Activity() {
    private lateinit var output: LogOutput
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
        val root = UiTheme.page(this)
        output = LogOutput(this)
        root.item(UiTheme.row(this).apply {
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
        root.item(UiTheme.row(this, android.view.Gravity.CENTER_VERTICAL).apply {
            item(filters.levels, 1f)
            addView(output.count, LinearLayout.LayoutParams(UiTheme.dp(this@LogActivity, 80), -2))
        })
        root.addView(output, LinearLayout.LayoutParams(-1, 0, 1f))
        UiTheme.setContentView(this, root); updateFollow(); TaskRuntime.observeLogs(listener)
    }
    private fun render(value: String) {
        output.follows = follows
        output.render(value, query, level)
    }
    private fun latest() { output.follows = follows; output.latest() }
    private fun updateFollow() {
        output.follows = follows
        UiTheme.setToggleSelected(follow, follows)
    }
    override fun onSaveInstanceState(state: Bundle) {
        state.putBoolean("follow", follows); state.putString("query", query); state.putInt("level", level)
        super.onSaveInstanceState(state)
    }
    override fun onDestroy() { TaskRuntime.removeLogListener(listener); super.onDestroy() }
}
