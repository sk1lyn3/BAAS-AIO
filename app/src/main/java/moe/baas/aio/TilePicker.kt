package moe.baas.aio

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import androidx.core.graphics.ColorUtils

/** One tile. [caption] is the small bottom line (e.g. "#1 · 12.5k"); [tag] is free for the caller. */
/** [enabled] = false: dimmed, cannot be turned on (can still be turned off if preselected), skipped by 全选. */
data class TileItem(val label: String, val caption: String = "", val tag: Any? = null, val enabled: Boolean = true)

/**
 * Generic tiled picker (View-based, help/About dialog style).
 * Selection is a BooleanArray owned by the caller and mutated in place; only tiles whose state
 * changed are repainted, so nothing re-lays out or flashes.
 */
object TilePicker {
    enum class Mode { SINGLE, MULTI }
    /** Dropdown list width: WRAP = longest label + padding + ✓ slot (min [WRAP_MIN_DP]); ANCHOR = anchor width (min ChoiceMenu.FALLBACK_WIDTH), like ChoiceMenu. */
    enum class WidthMode { WRAP, ANCHOR }
    const val WRAP_MIN_DP = 80

    class Options(
        val mode: Mode = Mode.MULTI,
        /** MULTI only: at most this many selected; null = unlimited. */
        val maxSelect: Int? = null,
        val columns: Int = 4,
        /** Header text from current selected indices; null hides the summary text. */
        val summary: ((List<Int>) -> String)? = { "已选 ${it.size}" },
        /** MULTI only: show 全选 / 全不选 (全选 hidden when maxSelect is set). */
        val bulkActions: Boolean = true,
        /** Minimum tile height in dp. */
        val tileMinHeight: Int = 66,
    )

    /** Handle returned by [build], for embedding: read/replace the selection without rebuilding. */
    class Handle internal constructor(val view: LinearLayout, val selected: BooleanArray, private val apply: (BooleanArray) -> Unit) {
        fun indices() = selected.indices.filter { selected[it] }
        fun set(values: BooleanArray) = apply(values)
    }

    fun build(context: Context, items: List<TileItem>, selected: BooleanArray, options: Options = Options(), onChange: (List<Int>) -> Unit = {}): Handle {
        require(selected.size == items.size) { "selected.size must equal items.size" }
        // SINGLE with several preselected: keep the first (mutates the caller's array, like every other change here).
        if (options.mode == Mode.SINGLE && selected.count { it } > 1) { val first = selected.indexOf(true); selected.fill(false); selected[first] = true }
        val dp = { v: Int -> UiTheme.dp(context, v) }
        val multi = options.mode == Mode.MULTI
        val root = UiTheme.column(context).apply { setPadding(dp(12), 0, dp(12), 0) }
        val summary = UiTheme.text(context, "", 12f, UiTheme.muted)
        val painters = arrayOfNulls<() -> Unit>(items.size)
        val dimmed = BooleanArray(items.size)
        fun atLimit() = multi && options.maxSelect != null && selected.count { it } >= options.maxSelect
        fun dimFor(i: Int) = !selected[i] && (!items[i].enabled || atLimit())
        /** Repaint only tiles whose dimmed state flipped (e.g. limit reached / released). */
        fun refreshDim() { for (i in items.indices) if (dimmed[i] != dimFor(i)) painters[i]?.invoke() }
        fun indices() = selected.indices.filter { selected[it] }
        fun changed() { refreshDim(); val sel = indices(); options.summary?.let { summary.text = it(sel) }; onChange(sel) }
        fun assign(i: Int, v: Boolean) { if (selected[i] != v) { selected[i] = v; painters[i]?.invoke() } }
        fun setAll(values: BooleanArray) {
            var room = if (multi) options.maxSelect ?: Int.MAX_VALUE else 1
            values.forEachIndexed { i, v -> if (!v) assign(i, false) else if (selected[i]) room-- }
            values.forEachIndexed { i, v -> if (v && !selected[i] && items[i].enabled && room > 0) { assign(i, true); room-- } }
            changed()
        }

        val head = UiTheme.row(context, Gravity.CENTER_VERTICAL).apply {
            addView(summary, LinearLayout.LayoutParams(0, -2, 1f))
            fun action(label: String, value: Boolean) = UiTheme.text(context, label, 13f, UiTheme.blue).apply {
                setTypeface(null, Typeface.BOLD); setPadding(dp(10), dp(6), dp(4), dp(6))
                setOnClickListener { setAll(BooleanArray(items.size) { value }) }
            }
            if (multi && options.bulkActions) {
                if (options.maxSelect == null) addView(action("全选", true))
                addView(action("全不选", false))
            }
        }
        if (options.summary != null || head.childCount > 1) root.addView(head, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })

        fun paint(i: Int, tile: LinearLayout, name: TextView, sub: TextView, caption: String, on: Boolean) {
            val dim = dimFor(i); dimmed[i] = dim
            // Disabled look: same tile, faded to 40% and greyed text; both themes stay legible.
            tile.alpha = if (dim) 0.4f else 1f
            tile.isEnabled = !dim
            tile.background = UiTheme.background(context,
                if (on) ColorUtils.setAlphaComponent(UiTheme.blue, 0x2A) else UiTheme.pale, 10).apply {
                setStroke(dp(if (on) 2 else 1), if (on) UiTheme.blue else ColorUtils.setAlphaComponent(UiTheme.muted, 0x40))
            }
            name.setTextColor(if (on) UiTheme.blue else if (dim) UiTheme.muted else UiTheme.navy)
            if (caption.isEmpty()) {
                // Captionless tiles (e.g. dropdown lists): one line, ✓ goes after the label.
                sub.visibility = View.GONE
                name.text = if (on) "${items[i].label}  ✓" else items[i].label
            } else sub.text = (if (on) "✓ " else "") + caption
            // Accessibility: TalkBack reads label + caption and the selected / disabled state.
            tile.isSelected = on
            tile.contentDescription = listOf(items[i].label, caption).filter { it.isNotEmpty() }.joinToString("，")
        }
        val cols = options.columns.coerceAtLeast(1)
        var row: LinearLayout? = null
        items.forEachIndexed { i, item ->
            if (i % cols == 0) row = UiTheme.row(context).also { root.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) }) }
            val name = UiTheme.text(context, item.label, 11f).apply {
                setTypeface(null, Typeface.BOLD); maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
                setLineSpacing(0f, 1.05f)
            }
            val sub = UiTheme.text(context, "", 10f, UiTheme.muted).apply {
                maxLines = 1; setPadding(0, dp(2), 0, 0)
            }
            val tile = UiTheme.column(context).apply {
                setPadding(dp(6), dp(6), dp(6), dp(6)); minimumHeight = dp(options.tileMinHeight)
                gravity = Gravity.CENTER_VERTICAL
                addView(name, if (item.caption.isEmpty()) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(-1, 0, 1f)); addView(sub)
                isClickable = true
            }
            painters[i] = { paint(i, tile, name, sub, item.caption, selected[i]) }
            painters[i]!!()
            tile.setOnClickListener {
                if (multi) {
                    if (!selected[i] && dimFor(i)) return@setOnClickListener
                    assign(i, !selected[i])
                } else {
                    // Re-tapping the selected tile still reports (dropdowns close on it); disabled ones never select.
                    if (!selected[i]) {
                        if (!item.enabled) return@setOnClickListener
                        selected.indices.forEach { if (it != i) assign(it, false) }
                        assign(i, true)
                    }
                }
                changed()
            }
            row!!.addView(tile, LinearLayout.LayoutParams(0, -1, 1f).apply { if (i % cols != 0) leftMargin = dp(5) })
        }
        val rest = (cols - items.size % cols) % cols
        repeat(rest) { row?.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(5) }) }
        options.summary?.let { summary.text = it(indices()) }
        return Handle(root, selected) { setAll(it) }
    }

    /** Dialog helper: OK returns selected indices; Cancel discards (caller's array is not touched). */
    fun show(context: Context, title: String, items: List<TileItem>, initial: BooleanArray, options: Options = Options(), onDone: (List<Int>) -> Unit) {
        val working = initial.copyOf()
        val handle = build(context, items, working, options)
        UiTheme.dialog(context).setTitle(title)
            .setView(ScrollView(context).apply { addView(handle.view) })
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ -> onDone(handle.indices()) }
            .show()
    }

    /** Result of [dropdown]: [above] = flipped up because there was no room below; x/y = popup top-left on screen. */
    class Dropdown internal constructor(val popup: PopupWindow, val handle: Handle?, val above: Boolean, val x: Int, val y: Int, val width: Int, val height: Int) {
        fun dismiss() = popup.dismiss()
    }

    /**
     * Single-select list popup at [anchor], drawn exactly like [ChoiceMenu] (same rows, ✓ slot, colours, card,
     * elevation). Width per [widthMode]: WRAP (default) fits the longest label; ANCHOR = ChoiceMenu's anchor width.
     * Left edge aligns with the anchor's start, clamped inside the window. Tapping an enabled item calls [onPick]
     * (only when it differs from [selectedIndex]) and closes. Disabled items are faded and inert. Captions are not shown.
     */
    fun dropdown(anchor: View, items: List<TileItem>, selectedIndex: Int, anchorRect: Rect? = null, widthMode: WidthMode = WidthMode.WRAP, onPick: (Int) -> Unit): Dropdown {
        val context = anchor.context
        val dp = { v: Int -> UiTheme.dp(context, v) }
        val content = UiTheme.column(context).apply { val p = dp(ChoiceMenu.PADDING); setPadding(p, p, p, p) }
        val scroll = ScrollView(context).apply { addView(content) }
        val card = FrameLayout(context).apply {
            background = ChoiceMenu.panelBackground(context); clipToOutline = true; addView(scroll)
            if (android.os.Build.VERSION.SDK_INT >= 28) accessibilityPaneTitle = "选项"
        }
        val width = when (widthMode) {
            WidthMode.ANCHOR -> maxOf(anchorRect?.width() ?: anchor.width, dp(ChoiceMenu.FALLBACK_WIDTH))
            WidthMode.WRAP -> {
                // Longest label measured bold (the selected style), + row text padding 8+4, ✓ slot, panel padding both sides.
                val paint = UiTheme.text(context, "", ChoiceMenu.TEXT_SP).paint.apply { isFakeBoldText = true }
                val text = items.maxOfOrNull { paint.measureText(it.label) }?.let { kotlin.math.ceil(it).toInt() } ?: 0
                maxOf(text + dp(8 + 4 + ChoiceMenu.CHECK_WIDTH + 2 * ChoiceMenu.PADDING + 2), dp(WRAP_MIN_DP))
            }
        }
        lateinit var dd: Dropdown
        items.forEachIndexed { i, item ->
            content.addView(ChoiceMenu.row(context, item.label, i == selectedIndex, item.enabled) {
                dd.dismiss(); if (i != selectedIndex) onPick(i)
            }, LinearLayout.LayoutParams(-1, dp(ChoiceMenu.ROW_HEIGHT)))
        }
        dd = place(anchor, anchorRect, card, width, ChoiceMenu.panelHeight(context, items.size), dp(ChoiceMenu.ELEVATION), null) {}
        if (selectedIndex > 0) scroll.post { scroll.scrollTo(0, selectedIndex * dp(ChoiceMenu.ROW_HEIGHT) - dd.height / 3) }
        return dd
    }

    /**
     * Anchored popup version of [build] (grid or list of tiles): same items / modes / maxSelect / disabled / captions /
     * columns. SINGLE: tapping an enabled tile reports `[index]` and closes; outside tap closes without a callback.
     * MULTI: the selection is reported once, on dismiss (outside tap included).
     * For a plain single-select list prefer the ChoiceMenu-styled overload above.
     */
    fun dropdown(anchor: View, items: List<TileItem>, selected: BooleanArray, options: Options, anchorRect: Rect? = null, onDone: (List<Int>) -> Unit): Dropdown {
        val context = anchor.context
        val dp = { v: Int -> UiTheme.dp(context, v) }
        val cols = options.columns.coerceAtLeast(1)
        val single = options.mode == Mode.SINGLE
        var picked = false
        lateinit var dd: Dropdown
        // Like show(): work on a copy, so the caller's array only changes through onDone.
        val handle = build(context, items, selected.copyOf(), options) { sel -> if (single && sel.isNotEmpty()) { picked = true; dd.dismiss() } }
        handle.view.setPadding(dp(6), dp(2), dp(6), dp(8))
        val scroll = ScrollView(context).apply { addView(handle.view) }
        val card = FrameLayout(context).apply {
            background = ChoiceMenu.panelBackground(context); clipToOutline = true; addView(scroll)
            if (android.os.Build.VERSION.SDK_INT >= 28) accessibilityPaneTitle = "选项"
        }
        val width = if (cols == 1) maxOf(anchorRect?.width() ?: anchor.width, dp(ChoiceMenu.FALLBACK_WIDTH)) else cols * dp(84) + dp(24)
        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        dd = place(anchor, anchorRect, card, width, card.measuredHeight, dp(ChoiceMenu.ELEVATION), handle) {
            if (!single || picked) onDone(handle.indices())
        }
        handle.selected.indexOfFirst { it }.takeIf { it > 0 }?.let { i -> scroll.post { scroll.scrollTo(0, (i / cols) * dp(options.tileMinHeight + 5) - dd.height / 3) } }
        return dd
    }

    /** Shared placement: below the anchor, flipped above when it doesn't fit and there is more room there; capped + scrolls. */
    private fun place(anchor: View, anchorRect: Rect?, card: View, wantWidth: Int, wantHeight: Int, elevation: Int, handle: Handle?, onDismiss: () -> Unit): Dropdown {
        val context = anchor.context
        val dp = { v: Int -> UiTheme.dp(context, v) }
        val rect = anchorRect ?: IntArray(2).let { anchor.getLocationOnScreen(it); Rect(it[0], it[1], it[0] + anchor.width, it[1] + anchor.height) }
        val frame = Rect().also { anchor.getWindowVisibleDisplayFrame(it) }
        if (frame.isEmpty) frame.set(0, 0, context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels)
        val margin = dp(8); val gap = dp(ChoiceMenu.GAP)
        val width = wantWidth.coerceAtMost(frame.width() - 2 * margin)
        val below = frame.bottom - rect.bottom - gap - margin
        val aboveRoom = rect.top - frame.top - gap - margin
        val above = wantHeight > below && aboveRoom > below
        val height = wantHeight.coerceAtMost(if (above) aboveRoom else below).coerceAtLeast(dp(48))
        val x = rect.left.coerceIn(frame.left + margin, (frame.right - margin - width).coerceAtLeast(frame.left + margin))
        val y = if (above) rect.top - gap - height else rect.bottom + gap
        val window = PopupWindow(card, width, height, true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)) // required for outside-tap dismiss on older APIs
            animationStyle = 0 // appears in place: no slide/fade flash
            this.elevation = elevation.toFloat()
        }
        val detach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { window.dismiss() }
        }
        anchor.addOnAttachStateChangeListener(detach)
        window.setOnDismissListener {
            anchor.removeOnAttachStateChangeListener(detach)
            onDismiss()
        }
        window.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
        return Dropdown(window, handle, above, x, y, width, height)
    }
}
