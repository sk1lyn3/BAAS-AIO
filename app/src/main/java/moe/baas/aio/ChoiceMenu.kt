package moe.baas.aio

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView

/** Compact anchored choices matching the shared white and blue controls. */
object ChoiceMenu {
    /** Shared look (also used by TilePicker.dropdown list mode); dp unless noted. */
    const val ROW_HEIGHT = 40
    const val MAX_HEIGHT = 288
    const val PADDING = 4
    const val CORNER = 8
    const val ROW_CORNER = 6
    const val ELEVATION = 6
    const val GAP = 4
    const val FALLBACK_WIDTH = 132
    const val CHECK_WIDTH = 24
    const val TEXT_SP = 12f

    /** Popup card background: surface + 1dp border. */
    fun panelBackground(context: Context) =
        UiTheme.background(context, UiTheme.surface, CORNER).apply { setStroke(UiTheme.dp(context, 1), UiTheme.border) }
    fun panelHeight(context: Context, rows: Int) = minOf(UiTheme.dp(context, rows * ROW_HEIGHT + 2 * PADDING), UiTheme.dp(context, MAX_HEIGHT))

    /** One 40dp row: label (bold blue when selected), ✓ in a 24dp end slot; disabled rows are faded and inert. */
    fun row(context: Context, label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit): LinearLayout {
        fun dp(value: Int) = UiTheme.dp(context, value)
        return UiTheme.row(context, Gravity.CENTER_VERTICAL).apply {
            isSelected = selected; isClickable = enabled; isFocusable = enabled; isEnabled = enabled
            if (!enabled) alpha = 0.4f
            background = UiTheme.quietRipple(context, if (selected) UiTheme.pale else UiTheme.surface, ROW_CORNER)
            contentDescription = label + (if (selected) "，已选择" else "") + (if (!enabled) "，不可用" else "")
            addView(UiTheme.text(context, label, TEXT_SP, if (selected) UiTheme.blue else UiTheme.navy).apply {
                gravity = Gravity.CENTER_VERTICAL; maxLines = 2; setPadding(dp(8), 0, dp(4), 0)
                if (selected) setTypeface(null, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, -1, 1f))
            if (selected) addView(UiTheme.text(context, "✓", 13f, UiTheme.blue).apply {
                gravity = Gravity.CENTER; setPadding(0, 0, 0, 0)
            }, LinearLayout.LayoutParams(dp(CHECK_WIDTH), -1))
            setOnClickListener { if (enabled) onClick() }
        }
    }

    fun show(anchor: View, labels: List<String>, selected: Int, indicator: View? = null, choose: (Int) -> Unit) {
        if (labels.isEmpty()) return
        val context = anchor.context
        fun dp(value: Int) = UiTheme.dp(context, value)
        val content = UiTheme.column(context).apply { setPadding(dp(PADDING), dp(PADDING), dp(PADDING), dp(PADDING)) }
        val popup = PopupWindow(ScrollView(context).apply { addView(content) },
            anchor.width.takeIf { it > 0 } ?: dp(FALLBACK_WIDTH), panelHeight(context, labels.size), true).apply {
            setBackgroundDrawable(panelBackground(context))
            elevation = dp(ELEVATION).toFloat(); isOutsideTouchable = true
            setOnDismissListener { indicator?.animate()?.rotation(0f)?.setDuration(140)?.start() }
        }
        labels.forEachIndexed { index, label ->
            content.addView(row(context, label, index == selected) { popup.dismiss(); choose(index) }, LinearLayout.LayoutParams(-1, dp(ROW_HEIGHT)))
        }
        popup.showAsDropDown(anchor, 0, dp(GAP), Gravity.END)
        indicator?.animate()?.rotation(90f)?.setDuration(140)?.start()
    }
}
