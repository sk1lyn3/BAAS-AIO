package moe.baas.aio

import android.content.Context
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.HorizontalScrollView
import android.view.View
import android.view.MotionEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import moe.baas.aio.UiTheme.item

/** Help page for one config key; same container, cards, type scale and 关闭 button as [AboutDialog]; no external links. */
object HelpDialog {
    fun show(context: Context, key: String) {
        val entry = HelpCatalog[context, key] ?: return
        val content = UiTheme.column(context).apply {
            val padding = UiTheme.dp(context, 12)
            setPadding(padding, 0, padding, 0)
        }
        content.item(UiTheme.card(context).apply {
            addView(body(context, entry.title, 18f, UiTheme.blue).apply { setTypeface(null, Typeface.BOLD) })
            addView(body(context, entry.summary, 13f).apply { setPadding(0, UiTheme.dp(context, 6), 0, 0); setLineSpacing(0f, 1.15f) })
        })
        entry.sections.forEach { (title, lines) ->
            content.item(UiTheme.card(context).apply {
                addView(body(context, title, 13f).apply { setTypeface(null, Typeface.BOLD) })
                lines.forEach { line ->
                    addView(body(context, "· $line", 12f, UiTheme.muted).apply { setPadding(UiTheme.dp(context, 4), UiTheme.dp(context, 4), 0, 0) })
                }
            })
        }
        entry.tables.forEach { (title, rows) ->
            content.item(UiTheme.card(context).apply {
                addView(body(context, title, 13f).apply { setTypeface(null, Typeface.BOLD) })
                rows.forEach { (left, right) ->
                    addView(UiTheme.row(context).apply {
                        minimumHeight = 0; isBaselineAligned = false
                        val v = UiTheme.dp(context, 3)
                        addView(body(context, left, 12f).apply { setPadding(UiTheme.dp(context, 4), v, UiTheme.dp(context, 8), v) }, LinearLayout.LayoutParams(UiTheme.dp(context, 120), -2))
                        addView(body(context, right, 12f, UiTheme.muted).apply { setPadding(0, v, UiTheme.dp(context, 4), v) }, LinearLayout.LayoutParams(-1, -2))
                    }, LinearLayout.LayoutParams(-1, -2))
                }
            })
        }
        if (entry.examples.isNotEmpty()) content.item(UiTheme.card(context).apply {
            addView(body(context, "示例", 13f).apply { setTypeface(null, Typeface.BOLD) })
            entry.examples.forEach { (code, meaning) ->
                addView(body(context, code, 13f, UiTheme.blue).apply {
                    typeface = Typeface.MONOSPACE
                    background = UiTheme.background(context, UiTheme.pale, 8)
                    val p = UiTheme.dp(context, 8); setPadding(p, p / 2, p, p / 2)
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = UiTheme.dp(context, 6) })
                addView(body(context, meaning, 12f, UiTheme.muted).apply { setPadding(UiTheme.dp(context, 4), UiTheme.dp(context, 4), 0, 0) })
            }
        })
        entry.texts.forEach { (title, text) ->
            content.item(UiTheme.card(context).apply {
                addView(body(context, title, 13f).apply { setTypeface(null, Typeface.BOLD) })
                addView(body(context, text, 12f, UiTheme.muted).apply { setPadding(UiTheme.dp(context, 4), UiTheme.dp(context, 4), 0, 0); setLineSpacing(0f, 1.15f) })
            })
        }
        entry.wideTables.forEach { table -> content.item(wideTableCard(context, table)) }
        UiTheme.dialog(context)
            .setView(ScrollView(context).apply { addView(content) })
            .setPositiveButton("关闭", null)
            .show()
    }


    /** Reusable horizontal-scroll table for help: swipe left/right; every cell long-press selectable. */
    private fun wideTableCard(context: Context, table: HelpWideTable): LinearLayout {
        val card = UiTheme.card(context)
        val title = body(context, table.title, 13f).apply { setTypeface(null, Typeface.BOLD) }
        // Collapsible: a plain (non-selectable) header row toggles the body; LayoutTransition animates the height.
        val bodyViews = mutableListOf<View>()
        if (table.collapsed) {
            val arrow = UiTheme.text(context, "展开 ▾", 12f, UiTheme.blue)
            val header = UiTheme.row(context, android.view.Gravity.CENTER_VERTICAL).apply {
                addView(UiTheme.text(context, table.title, 13f).apply { setTypeface(null, Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(arrow)
                minimumHeight = UiTheme.dp(context, 36)
                isClickable = true
                contentDescription = "${table.title}，点按展开或收起"
                setOnClickListener {
                    val open = bodyViews.first().visibility != View.VISIBLE
                    bodyViews.forEach { it.visibility = if (open) View.VISIBLE else View.GONE }
                    arrow.text = if (open) "收起 ▴" else "展开 ▾"
                }
            }
            card.layoutTransition = android.animation.LayoutTransition().apply { enableTransitionType(android.animation.LayoutTransition.CHANGING) }
            card.addView(header)
        } else card.addView(title)
        val cols = table.headers.size
        val paint = android.graphics.Paint().apply { textSize = 11f * context.resources.displayMetrics.scaledDensity }
        val headerPaint = android.graphics.Paint(paint).apply { textSize = 12f * context.resources.displayMetrics.scaledDensity; isFakeBoldText = true }
        val pad = UiTheme.dp(context, 12)
        val widths = (0 until cols).map { c ->
            val headerW = headerPaint.measureText(table.headers[c])
            val cellW = table.rows.maxOfOrNull { paint.measureText(it.getOrElse(c) { "" }) } ?: 0f
            (maxOf(headerW, cellW).toInt() + pad).coerceAtLeast(UiTheme.dp(context, 72))
        }
        fun cell(text: String, header: Boolean, width: Int, stripe: Boolean): TextView {
            val color = when {
                header -> UiTheme.navy
                else -> UiTheme.muted
            }
            return body(context, text.ifEmpty { " " }, if (header) 12f else 11f, color).apply {
                if (header) setTypeface(null, Typeface.BOLD)
                val v = UiTheme.dp(context, 4); val h = UiTheme.dp(context, 6)
                setPadding(h, v, h, v)
                if (stripe) setBackgroundColor(androidx.core.graphics.ColorUtils.setAlphaComponent(UiTheme.blue, 0x12))
                // Single line so wide columns scroll horizontally instead of wrapping.
                setSingleLine(true)
                maxLines = 1
                ellipsize = null
                minWidth = width
                // Exact width keeps columns aligned across rows.
                layoutParams = LinearLayout.LayoutParams(width, -2)
            }
        }
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun addRow(cells: List<String>, header: Boolean, stripe: Boolean) {
            grid.addView(UiTheme.row(context).apply {
                minimumHeight = 0; isBaselineAligned = false
                for (c in 0 until cols) {
                    addView(cell(cells.getOrElse(c) { "" }, header, widths[c], stripe && !header),
                        LinearLayout.LayoutParams(widths[c], -2))
                }
            }, LinearLayout.LayoutParams(-2, -2))
        }
        addRow(table.headers, header = true, stripe = false)
        table.rows.forEachIndexed { i, row -> addRow(row, header = false, stripe = i % 2 == 1) }
        // Wide tables never widen the dialog: unless the width is already fixed, report no width of their own, so the
        // dialog keeps its platform width (Material alert: wrap content, min 65%/95% of the screen) and the table scrolls.
        val hScroll = object : HorizontalScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = super.onMeasure(
                if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) widthMeasureSpec
                else MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY), heightMeasureSpec)
        }.apply {
            isHorizontalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            // Let horizontal swipes win over the outer vertical ScrollView.
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
            addView(grid)
        }
        card.addView(hScroll, LinearLayout.LayoutParams(-1, -2))
        bodyViews += hScroll
        if (table.collapsed) bodyViews.forEach { it.visibility = View.GONE }
        return card
    }

    /** Help body text: long-press selectable with system handles + copy toolbar, tinted with the v3 accent. */
    internal fun body(context: Context, value: String, size: Float = 14f, color: Int = UiTheme.navy) =
        UiTheme.text(context, value, size, color).apply {
            setTextIsSelectable(true)
            highlightColor = androidx.core.graphics.ColorUtils.setAlphaComponent(UiTheme.blue, 0x55)
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                textSelectHandle?.mutate()?.setTint(UiTheme.blue)
                textSelectHandleLeft?.mutate()?.setTint(UiTheme.blue)
                textSelectHandleRight?.mutate()?.setTint(UiTheme.blue)
            }
        }
}

/** ⓘ trigger placed right after a label. Renders nothing for keys without help, so rows without help never shift. */
@Composable
fun HelpTip(key: String, modifier: Modifier = Modifier, label: String = "设置") {
    val context = LocalContext.current
    if (!HelpCatalog.has(context, key)) return
    Ui.Quiet {
        Box(modifier.size(24.dp).clickable(role = Role.Button) { HelpDialog.show(context, key) }
            .semantics { contentDescription = "${label}说明" }, contentAlignment = Alignment.Center) {
            BText("ⓘ", 14f, Ui.Blue.copy(alpha = .75f), padding = NoPadding, align = TextAlign.Center)
        }
    }
}
