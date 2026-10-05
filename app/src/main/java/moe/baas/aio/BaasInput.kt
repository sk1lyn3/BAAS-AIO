package moe.baas.aio

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.widget.EditText

/** BAAS text field: shared rounded surface, focus border and cursor styling. */
class BaasInput(context: Context) : EditText(context) {
    init {
        textSize = 13f; typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(UiTheme.muted, UiTheme.navy)))
        setHintTextColor(UiTheme.muted)
        includeFontPadding = false; highlightColor = 0x33008fd3
        stateListAnimator = null; defaultFocusHighlightEnabled = false
        if (Build.VERSION.SDK_INT >= 29) textCursorDrawable = GradientDrawable().apply {
            setColor(UiTheme.blue); setSize(UiTheme.dp(context, 2), UiTheme.dp(context, 20))
        }
        minHeight = UiTheme.dp(context, 36); minimumHeight = minHeight; minimumWidth = 0
        backgroundTintList = null
        background = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), UiTheme.fieldBackground(context))
            addState(intArrayOf(android.R.attr.state_focused), UiTheme.fieldBackground(context, focused = true))
            addState(intArrayOf(), UiTheme.fieldBackground(context))
        }
        setPadding(UiTheme.dp(context, 12), UiTheme.dp(context, 8), UiTheme.dp(context, 12), UiTheme.dp(context, 8))
    }
}
