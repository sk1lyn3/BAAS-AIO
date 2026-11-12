package moe.baas.aio

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Shared native layout for Debug diagnostics; follows the app's theme and insets. */
internal class DebugPage(private val activity: Activity, title: String) {
    private val content = UiTheme.column(activity)

    init {
        UiTheme.syncNight(activity)
        val root = UiTheme.page(activity)
        root.addView(UiTheme.row(activity, Gravity.CENTER_VERTICAL).apply {
            addView(UiTheme.text(activity, title, 20f).apply { setTypeface(null, Typeface.BOLD) },
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(Button(activity).apply {
                text = "Back"; UiTheme.styleButton(this, false); setOnClickListener { activity.finish() }
            })
        })
        root.addView(ScrollView(activity).apply { isFillViewport = true; addView(content) },
            LinearLayout.LayoutParams(-1, 0, 1f))
        UiTheme.setContentView(activity, root)
    }

    fun section(title: String): LinearLayout = UiTheme.card(activity).also { card ->
        card.addView(UiTheme.text(activity, title, 13f, UiTheme.muted).apply { setTypeface(null, Typeface.BOLD) })
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiTheme.dp(activity, 8) })
    }

    fun actions(parent: LinearLayout, vararg items: Pair<String, () -> Unit>): List<Button> {
        val row = UiTheme.row(activity)
        parent.addView(row)
        return items.mapIndexed { index, (label, action) ->
            Button(activity).apply {
                text = label; UiTheme.styleButton(this, false)
                minWidth = 0; minimumWidth = 0
                setOnClickListener { action() }
                row.addView(this, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    topMargin = UiTheme.dp(activity, 4)
                    if (index > 0) marginStart = UiTheme.dp(activity, 6)
                })
            }
        }
    }

    fun report(): TextView = UiTheme.text(activity, "Ready", 12f).apply {
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
        setLineSpacing(0f, 1.15f)
        section("Output").addView(this, LinearLayout.LayoutParams(-1, -2))
    }
}
