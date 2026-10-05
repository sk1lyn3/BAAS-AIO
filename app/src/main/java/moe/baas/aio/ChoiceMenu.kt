package moe.baas.aio

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView

/** Compact anchored choices matching the shared white and blue controls. */
object ChoiceMenu {
    fun show(anchor: View, labels: List<String>, selected: Int, choose: (Int) -> Unit) {
        if (labels.isEmpty()) return
        val context = anchor.context
        fun dp(value: Int) = UiTheme.dp(context, value)
        val content = UiTheme.column(context).apply { setPadding(dp(4), dp(4), dp(4), dp(4)) }
        val popup = PopupWindow(ScrollView(context).apply { addView(content) },
            maxOf(anchor.width, dp(176)), minOf(dp(labels.size * 40 + 8), dp(288)), true).apply {
            setBackgroundDrawable(UiTheme.background(context, Color.WHITE, 8).apply { setStroke(dp(1), UiTheme.border) })
            elevation = dp(6).toFloat(); isOutsideTouchable = true
        }
        labels.forEachIndexed { index, label ->
            content.addView(UiTheme.row(context, Gravity.CENTER_VERTICAL).apply {
                isSelected = index == selected; isClickable = true; isFocusable = true
                background = UiTheme.quietRipple(context, if (isSelected) UiTheme.pale else Color.WHITE, 6)
                contentDescription = label + if (isSelected) "，已选择" else ""
                addView(UiTheme.text(context, label, 12f, if (isSelected) UiTheme.blue else UiTheme.navy).apply {
                    gravity = Gravity.CENTER_VERTICAL; maxLines = 2; setPadding(dp(8), 0, dp(4), 0)
                    if (index == selected) setTypeface(null, Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, -1, 1f))
                if (isSelected) addView(UiTheme.text(context, "✓", 13f, UiTheme.blue).apply {
                    gravity = Gravity.CENTER; setPadding(0, 0, 0, 0)
                }, LinearLayout.LayoutParams(dp(24), -1))
                setOnClickListener { popup.dismiss(); choose(index) }
            }, LinearLayout.LayoutParams(-1, dp(40)))
        }
        popup.showAsDropDown(anchor, 0, dp(4), Gravity.END)
    }
}
