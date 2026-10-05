package moe.baas.aio

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.ScrollView

/** Shared log rendering, counts and follow behavior for inline and fullscreen views. */
class LogOutput(context: Context, padding: Int = 8) : ScrollView(context) {
    private val output = UiLogs.textView(context)
    val count = UiTheme.text(context, "", 10f, UiTheme.muted).apply { gravity = Gravity.END }
    var follows = true
    init {
        background = UiTheme.background(context, Color.rgb(15, 23, 42))
        val p = UiTheme.dp(context, padding)
        setPadding(p, p, p, p)
        addView(output)
    }
    fun render(value: String, query: String, level: Int) {
        val result = UiLogs.render(context, value, query, level)
        count.text = "${result.count}/${result.total} 条"
        output.text = result.text
        if (follows) latest()
    }
    fun latest() = post {
        if (follows && isAttachedToWindow) scrollTo(0, output.height + paddingBottom)
    }
}
