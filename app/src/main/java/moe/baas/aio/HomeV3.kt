package moe.baas.aio

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
        if (active) RunningRail(Modifier.align(Alignment.BottomStart))
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
            if (pulse) {
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
