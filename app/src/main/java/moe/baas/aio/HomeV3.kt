package moe.baas.aio

import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.imageResource
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val HomeTabs = listOf("主页", "托管", "调度", "日志", "设置")

/** Provided by HomeActivity ContentHost: false for keep-alive offscreen tabs so infinite animations pause. */
val LocalTabSelected = androidx.compose.runtime.compositionLocalOf { true }
private val tabIcons = listOf(Icons.Rounded.Home, Icons.Rounded.Build, Icons.Rounded.DateRange, Icons.Rounded.List, Icons.Rounded.Settings)

/** Brand mark: the real BAAS logo + gradient "BAAS" wordmark. */
@Composable
fun HaloBrand(modifier: Modifier = Modifier) {
    Row(modifier.height(32.dp), verticalAlignment = Alignment.CenterVertically) {
        // The real BAAS logo (res/drawable-nodpi/baas_logo_ui.png, a 192px LANCZOS downscale of baas_logo.png,
        // identical to the desktop GUI's gui/assets/logo.png). Its own white disc reads on light and dark.
        androidx.compose.foundation.Image(androidx.compose.ui.graphics.ImageBitmap.imageResource(R.drawable.baas_logo_ui), "BAAS",
            Modifier.size(24.dp).softShadow(3.dp, CircleShape, 0.18f).clip(CircleShape),
            contentScale = androidx.compose.ui.layout.ContentScale.Fit, filterQuality = androidx.compose.ui.graphics.FilterQuality.High)
        Spacer(Modifier.width(6.dp))
        Text("BAAS", style = textStyle(17f, Ui.Blue, FontWeight.Black).copy(brush = Ui.primaryBrush, letterSpacing = 1.2.sp,
            platformStyle = PlatformTextStyle(includeFontPadding = false)), maxLines = 1)
    }
}

/**
 * Floating glass tab bar. A gradient pill glides (spring) under the selected tab; icon + label; light
 * haptic tick on change. Same five tabs and the same switchPage() action as the original buttons.
 */
@Composable
fun TabBar(page: String, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    val index = HomeTabs.indexOf(page).coerceAtLeast(0)
    val pos by animateFloatAsState(index.toFloat(), spring(dampingRatio = 0.72f, stiffness = 520f), label = "tabPill")
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(22.dp)
    BoxWithConstraints(modifier.fillMaxWidth().height(60.dp).softShadow(10.dp, shape, 0.14f)
        .background(Ui.Card, shape).border(1.dp, Brush.verticalGradient(listOf(Ui.CardHi, Ui.Border)), shape).padding(5.dp)) {
        val cell = maxWidth / HomeTabs.size
        Box(Modifier.offset(x = cell * pos).width(cell).fillMaxHeight().padding(horizontal = 2.dp)
            .softShadow(6.dp, RoundedCornerShape(17.dp), 0.32f).background(Ui.primaryBrush, RoundedCornerShape(17.dp)))
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            HomeTabs.forEachIndexed { i, name ->
                val on = i == index
                val tint by animateColorAsState(if (on) Color.White else Ui.Muted, tween(200), label = "tabTint")
                val lift by animateFloatAsState(if (on) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "tabLift")
                val source = remember { MutableInteractionSource() }
                Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(17.dp)).pressScale(source, 0.9f)
                    .clickable(source, null, role = Role.Tab) {
                        if (!on) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect(name)
                    }.semantics { selected = on; contentDescription = name },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                    Icon(tabIcons[i], null, Modifier.size(20.dp).graphicsLayer { translationY = -1.5.dp.toPx() * lift; scaleX = 1f + .08f * lift; scaleY = 1f + .08f * lift }, tint = tint)
                    Text(name, style = textStyle(11f, tint, if (on) FontWeight.Bold else FontWeight.Medium)
                        .copy(platformStyle = PlatformTextStyle(includeFontPadding = false)), maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

/** Hero card: BAAS gradient, halo rings, status chip with a pulse while running, shimmer rail while active. */
@Composable
fun HeroCard(modifier: Modifier = Modifier, active: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val halo = Color.White
    Box(modifier.fillMaxWidth().softShadow(8.dp, shape, 0.26f).clip(shape)
        .background(Ui.heroBrush)
        .drawBehind {
            val c = Offset(size.width * .95f, size.height * .1f)
            drawCircle(halo.copy(alpha = .16f), size.height * .9f, c, style = Stroke(1.5.dp.toPx()))
            drawCircle(halo.copy(alpha = .10f), size.height * .68f, c, style = Stroke(10.dp.toPx()))
            drawCircle(halo.copy(alpha = .08f), size.height * .3f, Offset(size.width * .08f, size.height * 1.05f))
        }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), content = content)
        // Pause the shimmer when this tab is keep-alive but hidden (avoids burning frames offscreen).
        if (active && LocalTabSelected.current) RunningRail(Modifier.align(Alignment.BottomStart))
        else if (active) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = .18f)))
    }
}

@Composable
private fun RunningRail(modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "rail")
    val x by t.animateFloat(-0.4f, 1.1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "railX")
    BoxWithConstraints(modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = .18f))) {
        Box(Modifier.offset(x = maxWidth * x).width(maxWidth * .35f).fillMaxHeight()
            .background(Brush.horizontalGradient(listOf(Color.Transparent, Color.White, Color.Transparent))))
    }
}

/** Status chip on the hero: colored dot (pulsing when [pulse]) + label. */
@Composable
fun StatusChip(label: String, dot: Color, pulse: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.background(Color.White.copy(alpha = .18f), CircleShape).border(1.dp, Color.White.copy(alpha = .28f), CircleShape)
        .padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
            if (pulse && LocalTabSelected.current) {
                val t = rememberInfiniteTransition(label = "pulse")
                val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "p")
                Box(Modifier.size(14.dp).graphicsLayer { scaleX = .5f + p; scaleY = .5f + p; alpha = 1f - p }.background(dot, CircleShape))
            }
            Box(Modifier.size(8.dp).border(1.5.dp, Color.White, CircleShape).padding(1.5.dp).background(dot, CircleShape))
        }
        Spacer(Modifier.width(6.dp))
        Text(label, style = textStyle(11f, Color.White, FontWeight.Bold).copy(platformStyle = PlatformTextStyle(includeFontPadding = false)), maxLines = 1)
    }
}

/** Value that rolls up/down like an odometer when it changes (resources, counters). */
@Composable
fun RollingText(value: String, size: Float, color: Color, weight: FontWeight = FontWeight.Medium, modifier: Modifier = Modifier) {
    AnimatedContent(value, modifier, transitionSpec = {
        (slideInVertically(tween(260)) { it / 2 } + fadeIn(tween(200))) togetherWith (slideOutVertically(tween(220)) { -it / 2 } + fadeOut(tween(160)))
    }, label = "roll") { v ->
        Text(v, style = textStyle(size, color, weight).copy(platformStyle = PlatformTextStyle(includeFontPadding = false)), maxLines = 1)
    }
}

/** Section caption: small accent bar + bold label, used above card groups. */
@Composable
fun Caption(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 6.dp, end = 3.dp, top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(3.dp, 12.dp).background(Ui.primaryBrush, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(7.dp))
        Text(text, Modifier.weight(1f), style = textStyle(12f, Ui.Muted, FontWeight.Bold).copy(letterSpacing = .4.sp), maxLines = 1)
        trailing?.invoke()
    }
}

/** Hero-styled buttons (white solid / translucent outline) so they read on the gradient. */
@Composable
fun HeroButton(label: String, modifier: Modifier = Modifier, solid: Boolean, enabled: Boolean, onClick: () -> Unit) =
    BButton(label, modifier, enabled = enabled, disabledAlpha = .45f,
        fill = if (solid) Color.White else Color.White.copy(alpha = .14f),
        stroke = if (solid) null else Color.White.copy(alpha = .45f),
        textColor = if (solid) Color(0xFF0573B8) else Color.White,
        disabledText = if (solid) Color(0xFF0573B8) else Color.White, bold = true, onClick = onClick)


data class ResourceChip(val icon: Int, val value: String, val detail: String, val onClick: (() -> Unit)? = null, val tip: String? = null)

/**
 * 体力 / 信用点 / 青辉石 on ONE row. Chips hug their content (no equal splits); if the three don't fit the available
 * width, the number size steps down from 11sp to at most 9sp (measured, not guessed). Numbers are never ellipsized.
 */
@Composable
fun ResourceChips(chips: List<ResourceChip>, modifier: Modifier = Modifier, roll: Boolean = false) = androidx.compose.foundation.layout.BoxWithConstraints(modifier.padding(top = 3.dp)) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val gap = 5.dp; val chrome = 4.dp + 8.dp + 20.dp + 3.dp   // start pad + end pad + icon + spacer
    val available = constraints.maxWidth
    val size = remember(chips.map { it.value }, available, density) {
        listOf(11f, 10.5f, 10f, 9.5f, 9f).firstOrNull { sp ->
            val texts = chips.sumOf { measurer.measure(it.value, textStyle(sp, Ui.Navy, FontWeight.Bold).copy(fontFeatureSettings = "tnum")).size.width }
            texts + with(density) { (chrome * chips.size + gap * (chips.size - 1)).roundToPx() } <= available
        } ?: 9f
    }
    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
        chips.forEach { chip ->
            Box {
                Row(Modifier.padding(vertical = 4.dp).clip(CircleShape).background(Ui.Pale, CircleShape)
                    .then(chip.onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier)
                    .padding(start = 4.dp, end = 8.dp, top = 3.dp, bottom = 3.dp)
                    .semantics(mergeDescendants = true) { contentDescription = chip.detail }, verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(chip.icon), null, Modifier.size(20.dp, 16.dp))
                    Spacer(Modifier.width(3.dp))
                    OdometerRoll(chip.value, size, Ui.Navy, FontWeight.Bold, intro = roll)
                }
                if (chip.tip != null && LocalTabSelected.current) {
                    val tipGap = with(density) { 3.dp.roundToPx() }
                    androidx.compose.ui.window.Popup(popupPositionProvider = remember(tipGap) {
                        object : androidx.compose.ui.window.PopupPositionProvider {
                            override fun calculatePosition(anchorBounds: androidx.compose.ui.unit.IntRect,
                                windowSize: androidx.compose.ui.unit.IntSize, layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                                popupContentSize: androidx.compose.ui.unit.IntSize) = androidx.compose.ui.unit.IntOffset(
                                    anchorBounds.left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                                    anchorBounds.bottom + tipGap)
                        }
                    }) {
                        Text(chip.tip, style = textStyle(11f, Ui.Navy), maxLines = 1,
                            modifier = Modifier.softShadow(4.dp, RoundedCornerShape(8.dp), 0.12f)
                                .background(Ui.Card, RoundedCornerShape(8.dp))
                                .border(1.dp, Ui.Border, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                }
            }
        }
    }
}

/**
 * Mechanical digit-wheel intro: every digit column rolls from 9 down to its real digit (clip + translationY),
 * leftmost settles first and each column to the right a beat later, like a carry rippling through a counter.
 * Separators (',', '/', '.', 'M', 'K', '—') stay still; each digit column has the fixed width of '0', so the
 * chip never changes width while rolling. Skipped when the system "remove animations" setting is on.
 */
@Composable
fun OdometerRoll(value: String, size: Float, color: Color, weight: FontWeight, intro: Boolean = true) {
    // One renderer for every state (intro roll, settled, later updates): identical font, cell width, line box and
    // baseline, so nothing jumps when the intro ends. The intro only decides where each wheel STARTS (9 vs its digit).
    val style = textStyle(size, color, weight).copy(fontFeatureSettings = "tnum", platformStyle = PlatformTextStyle(includeFontPadding = false))
    val context = androidx.compose.ui.platform.LocalContext.current
    val animate = remember { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val cell = remember(style) { measurer.measure("0", style).size }
    val w = with(density) { cell.width.toDp() }; val h = with(density) { cell.height.toDp() }
    androidx.compose.runtime.key(value.length) {
        Row(Modifier.clearAndSetSemantics { text = AnnotatedString(value) }, verticalAlignment = Alignment.CenterVertically) {
            var index = 0
            value.forEach { ch ->
                if (!ch.isDigit()) Box(Modifier.height(h), contentAlignment = Alignment.Center) { Text(ch.toString(), style = style, maxLines = 1) }
                else {
                    val order = index++
                    val target = ch - '0'
                    val pos = remember { androidx.compose.animation.core.Animatable(if (intro && animate) 9f else target.toFloat()) }
                    var first by remember { mutableStateOf(true) }
                    androidx.compose.runtime.LaunchedEffect(target) {
                        if (!animate) pos.snapTo(target.toFloat())
                        else if (first) pos.animateTo(target.toFloat(), tween(460 + order * 40, delayMillis = order * 55,
                            easing = androidx.compose.animation.core.CubicBezierEasing(.22f, .9f, .32f, 1.12f)))
                        else pos.animateTo(target.toFloat(), tween(260, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                        first = false
                    }
                    Box(Modifier.size(w, h).clipToBounds()) {
                        Column(Modifier.wrapContentHeight(Alignment.Top, unbounded = true).graphicsLayer { translationY = -pos.value * cell.height }) {
                            for (d in 0..9) Box(Modifier.size(w, h), contentAlignment = Alignment.Center) { Text(d.toString(), style = style, maxLines = 1) }
                        }
                    }
                }
            }
        }
    }
}
