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
    private var pending: Triple<String, String, Int>? = null
    private var rendered: Triple<String, String, Int>? = null
    private var queued = false
    private var lastRenderAt = 0L
    private val redraw = Runnable {
        queued = false
        pending?.let { request ->
            pending = null
            // Empty live logs may fall back to a newly written task report.
            if (request != rendered || request.first == "[]") {
                val result = UiLogs.render(context, request.first, request.second, request.third)
                count.text = "${result.count}/${result.total} 条"
                output.text = result.text
                rendered = request
            }
            lastRenderAt = android.os.SystemClock.uptimeMillis()
            if (follows) latest()
        }
    }
    fun render(value: String, query: String, level: Int) {
        val request = Triple(value, query, level)
        if (request == rendered && pending == null && value != "[]") {
            if (follows) latest()
            return
        }
        pending = request
        if (!queued) {
            queued = true
            postDelayed(redraw, maxOf(0L, 200L - (android.os.SystemClock.uptimeMillis() - lastRenderAt)))
        }
    }
    override fun onDetachedFromWindow() {
        removeCallbacks(redraw); queued = false; pending = null
        super.onDetachedFromWindow()
    }
    fun latest() = post {
        if (follows && isAttachedToWindow) scrollTo(0, output.height + paddingBottom)
    }
}
