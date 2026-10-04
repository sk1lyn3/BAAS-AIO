package moe.baas.aio

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.LinearLayout

/** In-app messages share the existing palette and need no overlay permission. */
object UiNotice {
    private const val TAG = "baas-notice"
    fun show(activity: Activity, message: String, actionLabel: String? = null,
             error: Boolean = false, action: (() -> Unit)? = null) = activity.runOnUiThread {
        if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
        val host = activity.findViewById<FrameLayout>(android.R.id.content)
        host.findViewWithTag<View>(TAG)?.let { it.animate().cancel(); host.removeView(it) }
        val accent = if (error) UiTheme.danger else UiTheme.blue
        val bar = LinearLayout(activity).apply {
            tag = TAG; orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = UiTheme.background(activity, Color.WHITE, 12).apply { setStroke(UiTheme.dp(activity, 1), UiTheme.border) }
            elevation = UiTheme.dp(activity, 6).toFloat()
            setPadding(UiTheme.dp(activity, 10), UiTheme.dp(activity, 6), UiTheme.dp(activity, 4), UiTheme.dp(activity, 6))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        fun dismiss() {
            if (bar.parent != host) return
            bar.animate().cancel()
            bar.animate().alpha(0f).translationY(UiTheme.dp(activity, 8).toFloat()).setDuration(140)
                .withEndAction { host.removeView(bar) }.start()
        }
        bar.addView(UiTheme.text(activity, if (error) "!" else "i", 17f, accent).apply {
            gravity = Gravity.CENTER; setTypeface(null, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(UiTheme.dp(activity, 24), -2))
        bar.addView(UiTheme.text(activity, message, 13f).apply { maxLines = 4 }, LinearLayout.LayoutParams(0, -2, 1f))
        if (actionLabel != null && action != null) bar.addView(UiTheme.flatButton(activity, actionLabel) {
            dismiss(); action()
        })
        bar.addView(UiTheme.text(activity, "×", 20f, UiTheme.muted).apply {
            gravity = Gravity.CENTER; contentDescription = "关闭提示"
            isClickable = true; isFocusable = true
            background = UiTheme.quietRipple(activity)
            setOnClickListener { dismiss() }
        }, LinearLayout.LayoutParams(UiTheme.dp(activity, 40), UiTheme.dp(activity, 44)))
        host.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = UiTheme.dp(activity, 12); rightMargin = UiTheme.dp(activity, 12)
            bottomMargin = UiTheme.dp(activity, if (activity is HomeActivity) 72 else 16)
        })
        bar.alpha = 0f; bar.translationY = UiTheme.dp(activity, 12).toFloat()
        bar.animate().alpha(1f).translationY(0f).setDuration(180).start()
        val duration = if (action != null) 8000 else 4500
        val timeout = if (Build.VERSION.SDK_INT >= 29) activity.getSystemService(AccessibilityManager::class.java)
            .getRecommendedTimeoutMillis(duration, AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_CONTROLS)
        else duration
        bar.postDelayed({ dismiss() }, timeout.toLong())
    }
}
