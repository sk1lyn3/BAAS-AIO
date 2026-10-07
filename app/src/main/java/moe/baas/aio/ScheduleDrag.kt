package moe.baas.aio

import android.content.ClipData
import android.graphics.Rect
import android.graphics.Point
import android.view.DragEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView

/** Local visual ordering; commit only on a drop, restore on cancellation. */
class ScheduleDrag(
    private val list: LinearLayout,
    private val scroll: ScrollView,
    private val enabled: () -> Boolean,
    private val save: (List<View>) -> Unit,
    private val error: (Exception) -> Unit,
    private val canMove: (View, View) -> Boolean = { _, _ -> true }
) {
    private var source: View? = null
    val isDragging get() = source != null
    private var before = emptyList<View>()
    private var dropped = false
    private var pendingLayout = false
    private var pointerY = 0f
    private var inside = false
    private fun children() = (0 until list.childCount).map { list.getChildAt(it) }
    private val edgeScroll = object : Runnable {
        override fun run() {
            if (source == null) return
            if (inside && enabled()) {
                val bounds = Rect(); scroll.getGlobalVisibleRect(bounds)
                val edge = UiTheme.dp(list.context, 48)
                val direction = when {
                    pointerY < bounds.top + edge -> -1
                    pointerY > bounds.bottom - edge -> 1
                    else -> 0
                }
                if (direction != 0 && scroll.canScrollVertically(direction)) {
                    scroll.scrollBy(0, direction * UiTheme.dp(list.context, 6)); move()
                }
            }
            list.postOnAnimation(this)
        }
    }
    init {
        list.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> event.localState === this && enabled()
                DragEvent.ACTION_DRAG_ENTERED -> { inside = true; true }
                DragEvent.ACTION_DRAG_EXITED -> { inside = false; true }
                DragEvent.ACTION_DRAG_LOCATION -> {
                    val location = IntArray(2); list.getLocationOnScreen(location)
                    pointerY = location[1] + event.y; inside = true; move(); true
                }
                DragEvent.ACTION_DROP -> {
                    if (!enabled()) false else try {
                        val order = children()
                        if (order != before) save(order)
                        dropped = true; true
                    } catch (failure: Exception) { error(failure); false }
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    list.removeCallbacks(edgeScroll)
                    if (!dropped) {
                        list.removeAllViews(); before.forEach { list.addView(it) }
                    }
                    children().forEach { it.animate().cancel(); it.translationY = 0f; it.alpha = 1f }
                    source = null; inside = false; true
                }
                else -> true
            }
        }
    }
    fun start(card: View, handle: View): Boolean {
        if (!enabled() || source != null) return false
        before = children(); dropped = false; source = card
        val cardPosition = IntArray(2); card.getLocationOnScreen(cardPosition)
        val handlePosition = IntArray(2); handle.getLocationOnScreen(handlePosition)
        val shadow = object : View.DragShadowBuilder(card) {
            override fun onProvideShadowMetrics(size: Point, touch: Point) {
                size.set(card.width, card.height)
                touch.set(handlePosition[0] - cardPosition[0] + handle.width / 2,
                    handlePosition[1] - cardPosition[1] + handle.height / 2)
            }
        }
        if (!card.startDragAndDrop(ClipData.newPlainText("task", card.tag as String), shadow, this, 0)) {
            source = null; return false
        }
        card.alpha = .18f
        list.postOnAnimation(edgeScroll)
        return true
    }
    private fun move() {
        val card = source ?: return
        if (pendingLayout || !enabled()) return
        val order = children(); val from = order.indexOf(card)
        val location = IntArray(2); list.getLocationOnScreen(location)
        val y = pointerY - location[1]
        var target = from
        order.forEachIndexed { i, view ->
            if (!canMove(card, view)) return@forEachIndexed
            val center = view.top + view.height / 2f
            if (i < from && y < center) target = minOf(target, i)
            if (i > from && y > center) target = maxOf(target, i)
        }
        if (target == from) return
        val positions = order.associateWith { it.y }
        order.forEach { it.animate().cancel() }
        list.removeView(card); list.addView(card, target)
        pendingLayout = true
        list.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                list.viewTreeObserver.removeOnPreDrawListener(this); pendingLayout = false
                if (source != null) children().filter { it !== source }.forEach { view ->
                    view.translationY = positions.getValue(view) - view.top
                    view.animate().translationY(0f).setDuration(160).setInterpolator(DecelerateInterpolator()).start()
                }
                return true
            }
        })
    }
}
