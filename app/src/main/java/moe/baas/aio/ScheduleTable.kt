package moe.baas.aio

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import org.json.JSONObject

/** ScheduleTaskRow category tags. */
private val categories = mapOf(
    "cafe_reward" to ("咖啡厅" to Color(0xffdb2777)), "group" to ("社团" to Color(0xff16a34a)),
    "lesson" to ("日程" to Color(0xff9333ea)), "collect_reward" to ("奖励" to Color(0xffd97706)),
    "mail" to ("邮箱" to Color(0xff0284c7)), "arena" to ("竞技" to Color(0xffdc2626)),
    "common_shop" to ("商店" to Color(0xff4f46e5)), "tactical_challenge_shop" to ("商店" to Color(0xff4f46e5)),
    "create" to ("制造" to Color(0xffea580c)), "rewarded_task" to ("通缉" to Color(0xffdc2626)),
    "total_assault" to ("总力战" to Ui.Blue), "scrimmage" to ("交流会" to Color(0xff0d9488)),
    "joint_firing_drill" to ("演习" to Color(0xffea580c)), "activity_sweep" to ("活动" to Color(0xffc026d3)),
)

class ScheduleRowModel(val event: JSONObject, val interval: String) {
    val name: String = event.getString("func_name")
    val enabled get() = event.getBoolean("enabled")
}

/**
 * The bordered schedule table (header + rows). Reordering mirrors ScheduleDrag: long-press the
 * handle, move only among rows with the same enabled state, animate neighbours (160ms decelerate),
 * auto-scroll within 48dp of the viewport edges, save only on a drop inside the list.
 */
@Composable
fun ScheduleTable(
    rows: List<ScheduleRowModel>,
    draggable: Boolean,
    scroll: ScrollState,
    viewport: () -> LayoutCoordinates?,
    tick: Int,
    nextText: (JSONObject) -> String,
    nextColor: (JSONObject) -> Color,
    onConfigure: (JSONObject) -> Unit,
    onToggle: (ScheduleRowModel, Boolean) -> Unit,
    onReorder: (List<String>) -> Unit,
    onDragging: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    // The old panel had a 1dp stroke, but its full-width children painted over it (clipToOutline), so the stroke never showed.
    Column(modifier.fillMaxWidth().softShadow(6.dp, shape, 0.10f).clip(shape).background(Ui.Card)) {
        Row(Modifier.fillMaxWidth().background(Ui.Pale).padding(horizontal = 6.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            if (draggable) Spacer(Modifier.width(24.dp))
            BText("#", 10f, Ui.Muted, Modifier.width(24.dp), padding = NoPadding, align = TextAlign.Center)
            BText("任务 / 间隔", 10f, Ui.Muted, Modifier.weight(1f))
            BText("下次执行", 10f, Ui.Muted, Modifier.width(78.dp))
            BText("启用", 10f, Ui.Muted, Modifier.width(42.dp), padding = NoPadding, align = TextAlign.Center)
            BText("配置", 10f, Ui.Muted, Modifier.width(30.dp), padding = NoPadding, align = TextAlign.Center)
        }
        val byName = rows.associateBy { it.name }
        val order = remember(rows.map { it.name }) { rows.map { it.name }.toMutableStateList() }
        val heights = remember { mutableStateMapOf<String, Int>() }
        val offsets = remember(rows.map { it.name }) { mutableMapOf<String, Animatable<Float, *>>() }
        fun offset(name: String) = offsets.getOrPut(name) { Animatable(0f) }
        var dragging by remember { mutableStateOf<String?>(null) }
        var before by remember { mutableStateOf(emptyList<String>()) }
        var pointerRootY by remember { mutableFloatStateOf(0f) }
        var touchInRow by remember { mutableFloatStateOf(0f) }
        var listCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
        val scope = rememberCoroutineScope()
        val density = LocalDensity.current
        fun top(name: String): Int = order.takeWhile { it != name }.sumOf { heights[it] ?: 0 }
        fun listY(): Float = pointerRootY - (listCoords?.positionInRoot()?.y ?: 0f)
        fun move() {
            val card = dragging ?: return
            val from = order.indexOf(card)
            val y = listY()
            var target = from
            order.forEachIndexed { i, name ->
                if (byName[name]?.enabled != byName[card]?.enabled) return@forEachIndexed
                val center = top(name) + (heights[name] ?: 0) / 2f
                if (i < from && y < center) target = minOf(target, i)
                if (i > from && y > center) target = maxOf(target, i)
            }
            if (target == from) return
            val oldTops = order.associateWith { top(it) }
            order.removeAt(from); order.add(target, card)
            order.filter { it != card }.forEach { name ->
                val delta = (oldTops.getValue(name) - top(name)).toFloat()
                if (delta != 0f) scope.launch {
                    val anim = offset(name)
                    anim.snapTo(anim.value + delta)
                    anim.animateTo(0f, tween(160, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
                }
            }
        }
        fun finish(drop: Boolean) {
            val y = listY()
            val inside = y >= 0 && y <= order.sumOf { heights[it] ?: 0 }
            if (drop && inside && order.toList() != before) {
                try { onReorder(order.toList()) } catch (e: Exception) { order.clear(); order.addAll(before); dragging = null; onDragging(false); throw e }
            } else if (order.toList() != before) { order.clear(); order.addAll(before) }
            scope.launch { offsets.values.forEach { it.snapTo(0f) } }
            dragging = null; onDragging(false)
        }
        // Edge auto-scroll while dragging.
        LaunchedEffect(dragging) {
            if (dragging == null) return@LaunchedEffect
            val edge = with(density) { 48.dp.toPx() }; val step = with(density) { 6.dp.toPx() }
            while (dragging != null) {
                withFrameNanos { }
                val bounds = viewport() ?: continue
                val topY = bounds.positionInRoot().y; val bottomY = topY + bounds.size.height
                val direction = when { pointerRootY < topY + edge -> -1; pointerRootY > bottomY - edge -> 1; else -> 0 }
                if (direction != 0 && (if (direction < 0) scroll.canScrollBackward else scroll.canScrollForward)) {
                    scroll.dispatchRawDelta(direction * step); move()
                }
            }
        }
        Box(Modifier.fillMaxWidth().onGloballyPositioned { listCoords = it }) {
            Column(Modifier.fillMaxWidth()) {
                order.forEach { name ->
                    val row = byName[name] ?: return@forEach
                    key(name) {
                        var handleCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
                        TaskRow(row, draggable, tick, nextText, nextColor, onConfigure, onToggle,
                            Modifier.onSizeChanged { heights[name] = it.height }
                                .graphicsLayer { translationY = offset(name).value; alpha = if (dragging == name) .28f else 1f },
                            handle = if (!draggable) Modifier else Modifier.onGloballyPositioned { handleCoords = it }
                                .pointerInput(name) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { at ->
                                            if (dragging == null) {
                                                before = order.toList(); dragging = name; onDragging(true)
                                                val root = handleCoords?.positionInRoot()?.y ?: 0f
                                                pointerRootY = root + at.y
                                                touchInRow = pointerRootY - (listCoords?.positionInRoot()?.y ?: 0f) - top(name)
                                            }
                                        },
                                        onDrag = { change, _ ->
                                            if (dragging == name) {
                                                pointerRootY = (handleCoords?.positionInRoot()?.y ?: 0f) + change.position.y
                                                move()
                                            }
                                        },
                                        onDragEnd = { if (dragging == name) finish(drop = true) },
                                        onDragCancel = { if (dragging == name) finish(drop = false) },
                                    )
                                })
                    }
                }
            }
            // Drag shadow: an opaque copy of the row under the finger.
            dragging?.let { name -> byName[name]?.let { row ->
                // Lifted card: springs up to 1.035x with a deep soft shadow; the slot below stays as a faint ghost.
                val lift = remember(name) { androidx.compose.animation.core.Animatable(0f) }
                val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
                LaunchedEffect(name) {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    lift.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 600f))
                }
                val liftShape = RoundedCornerShape(14.dp)
                TaskRow(row, draggable, tick, nextText, nextColor, {}, { _, _ -> }, Modifier.zIndex(1f)
                    .graphicsLayer { translationY = listY() - touchInRow; val sc = 1f + .035f * lift.value; scaleX = sc; scaleY = sc }
                    .softShadow(18.dp * lift.value, liftShape, 0.30f).clip(liftShape)
                    .border(1.5.dp, Ui.Blue.copy(alpha = .55f * lift.value), liftShape))
            } }
        }
    }
}

@Composable
private fun TaskRow(
    row: ScheduleRowModel, draggable: Boolean, tick: Int,
    nextText: (JSONObject) -> String, nextColor: (JSONObject) -> Color,
    onConfigure: (JSONObject) -> Unit, onToggle: (ScheduleRowModel, Boolean) -> Unit,
    modifier: Modifier = Modifier, handle: Modifier = Modifier,
) {
    val event = row.event
    val title = event.getString("event_name")
    var checked by remember(event) { mutableStateOf(event.getBoolean("enabled")) }
    val fade = if (checked) 1f else .5f
    tick
    Column(modifier.fillMaxWidth().background(Ui.Card)) {
        Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 66.dp).padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            if (draggable) Box(handle.size(24.dp, 48.dp).semantics { contentDescription = "拖动调整${title}顺序" }, contentAlignment = Alignment.Center) {
                BText("⠿", 20f, Ui.Muted, padding = NoPadding)
            }
            BText(event.optInt("priority").toString(), 11f, Ui.Muted, Modifier.width(24.dp).alpha(fade), padding = NoPadding, align = TextAlign.Center)
            Column(Modifier.weight(1f).alpha(fade).padding(horizontal = 4.dp)) {
                BText(title, 13f, weight = FontWeight.Medium, padding = PaddingValues(bottom = 4.dp), maxLines = 2)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    categories[row.name]?.let { (label, color) ->
                        BText(label, 9f, color, Modifier.padding(end = 5.dp).background(color.copy(alpha = 0x18 / 255f), RoundedCornerShape(4.dp)),
                            padding = PaddingValues(horizontal = 4.dp, vertical = 2.dp))
                    }
                    BText(row.interval, 10f, Ui.Muted, padding = NoPadding, maxLines = 1)
                }
            }
            BText(nextText(event), 10.5f, nextColor(event), Modifier.width(78.dp), padding = PaddingValues(horizontal = 2.dp), maxLines = 2)
            CompactSwitch(checked, "启用$title", Modifier.size(42.dp, 44.dp)) { value ->
                val previous = checked
                checked = value
                try { onToggle(row, value) } catch (_: Exception) { checked = previous }
            }
            Box(Modifier.size(30.dp, 44.dp).clickable(remember { MutableInteractionSource() }, null) { onConfigure(event) }
                .semantics { contentDescription = "配置$title" }, contentAlignment = Alignment.Center) {
                BText("⚙\uFE0E", 20f, Ui.Muted, padding = NoPadding)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ui.Border))
    }
}
