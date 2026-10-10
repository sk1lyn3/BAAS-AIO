@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package moe.baas.aio

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.ripple
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * v3 "Halo Sky" design tokens. Inspired by Blue Archive's clean sky-blue UI: white glassy surfaces,
 * BAAS blue gradients, thin halo rings. Every color is a plain getter that follows [night], which each
 * Activity sets from its configuration (uiMode changes recreate activities), so the same tokens work in
 * Compose and in the View-based dialogs (UiTheme) without CompositionLocals.
 */
object Ui {
    @JvmStatic var night = false
    private fun pick(day: Long, dark: Long) = Color(if (night) dark else day)
    val Blue get() = pick(0xFF0A93D8, 0xFF3DB8F5)
    val BlueDeep get() = pick(0xFF0573B8, 0xFF1C8FD6)
    val Cyan get() = pick(0xFF34C6F4, 0xFF5AD2FA)
    val Navy get() = pick(0xFF16304A, 0xFFE4EEF8)
    val Muted get() = pick(0xFF6A8196, 0xFF8EA4B9)
    /** Tinted fill for selected/quiet areas (was the page color in v2). */
    val Pale get() = pick(0xFFEAF6FE, 0xFF15263B)
    val Border get() = pick(0xFFDCEAF4, 0xFF233650)
    val Danger get() = pick(0xFFE0505A, 0xFFFF6B75)
    val Success get() = pick(0xFF18946B, 0xFF3ED3A0)
    val DeepBlue get() = pick(0xFF006DA6, 0xFF7CCBF5)
    val LightGray get() = pick(0xFFCCCCCC, 0xFF3A4A5E)
    val Card get() = pick(0xFFFFFFFF, 0xFF132135)
    val CardHi get() = pick(0xFFFFFFFF, 0xFF1A2B42)
    val BgTop get() = pick(0xFFE3F3FE, 0xFF0A1424)
    val BgBottom get() = pick(0xFFF7FBFF, 0xFF0D192B)
    val Halo get() = pick(0x220A93D8, 0x2A3DB8F5)
    val Shadow get() = pick(0xFF0A5C99, 0xFF000000)
    val OnBlue = Color.White
    val primaryBrush get() = Brush.linearGradient(listOf(Cyan, Blue, BlueDeep))
    /** Hero gradient: vivid sky by day, deep ocean at night so white text keeps contrast. */
    val heroBrush get() = Brush.linearGradient(if (night) listOf(Color(0xFF0B3F70), Color(0xFF13639F), Color(0xFF1B8CC6)) else listOf(Color(0xFF0573B8), Color(0xFF0A93D8), Color(0xFF34C6F4)))
    val bgBrush get() = Brush.verticalGradient(listOf(BgTop, BgBottom))

    private val blueRipple get() = RippleConfiguration(Blue, RippleAlpha(0.16f, 0.16f, 0.12f, 0.20f))
    private val quietRipple get() = RippleConfiguration(Muted, RippleAlpha(0.08f, 0.08f, 0.08f, 0.12f))

    @Composable fun Ripples(content: @Composable () -> Unit) =
        CompositionLocalProvider(LocalRippleConfiguration provides blueRipple, content = content)

    @Composable fun Quiet(content: @Composable () -> Unit) =
        CompositionLocalProvider(LocalRippleConfiguration provides quietRipple, content = content)
}

/** Soft blue-tinted card shadow (ambient/spot colors are honored on API 28+, our minimum). */
fun Modifier.softShadow(elevation: Dp, shape: Shape, alpha: Float = 0.16f): Modifier =
    if (elevation <= 0.dp) this else this.shadow(elevation, shape, clip = false,
        ambientColor = Ui.Shadow.copy(alpha = alpha), spotColor = Ui.Shadow.copy(alpha = alpha * 1.4f))

/** Spring "squish" on press, shared by buttons, cards and tabs. */
@Composable
fun Modifier.pressScale(source: MutableInteractionSource, pressedScale: Float = 0.96f): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f,
        spring(dampingRatio = 0.55f, stiffness = 900f), label = "press")
    return this.graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Page backdrop: sky gradient with two faint halo rings and a hairline diagonal — drawn once, no blur. */
fun Modifier.haloBackdrop(): Modifier = this.drawBehind {
    drawRect(Ui.bgBrush)
    val halo = Ui.Halo
    val c = Offset(size.width * 0.92f, size.height * 0.06f)
    val r = size.minDimension * 0.42f
    drawCircle(halo, r, c, style = Stroke(1.4.dp.toPx()))
    drawCircle(halo, r * 0.78f, c, style = Stroke(5.dp.toPx()))
    drawCircle(halo.copy(alpha = halo.alpha * 0.6f), r * 0.55f, Offset(size.width * 0.04f, size.height * 0.94f), style = Stroke(1.dp.toPx()))
    drawLine(halo, Offset(size.width * 0.55f, 0f), Offset(size.width, size.height * 0.32f), 1.dp.toPx())
}

/** TextView defaults: sans-serif, includeFontPadding, padding 4/6 (UiTheme.text). */
fun textStyle(size: Float, color: Color, weight: FontWeight = FontWeight.Normal, family: FontFamily = FontFamily.SansSerif) = TextStyle(
    fontSize = size.sp, color = color, fontWeight = weight, fontFamily = family,
    platformStyle = PlatformTextStyle(includeFontPadding = true),
)

@Composable
fun BText(
    text: String,
    size: Float = 14f,
    color: Color = Ui.Navy,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal,
    padding: PaddingValues = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign? = null,
    family: FontFamily = FontFamily.SansSerif,
    letterSpacing: TextUnit = TextUnit.Unspecified,
) {
    androidx.compose.material3.Text(
        text, modifier.padding(padding),
        style = textStyle(size, color, weight, family).copy(letterSpacing = letterSpacing),
        maxLines = maxLines, overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
        textAlign = align,
    )
}

val NoPadding = PaddingValues(0.dp)

/** `LinearLayout.item` margins: UiTheme (3/4) and HomeActivity (3/2). */
fun Modifier.item() = this.padding(horizontal = 3.dp, vertical = 4.dp)
fun Modifier.homeItem() = this.padding(horizontal = 3.dp, vertical = 2.dp)

/**
 * Buttons. Primary = BAAS blue gradient with a soft blue glow; secondary = glassy surface with a hairline
 * border. Both squish with a spring on press. Signature unchanged from v2 so every call site keeps its
 * behavior (enabled rules, labels, actions).
 */
@Composable
fun BButton(
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    flat: Boolean = false,
    enabled: Boolean = true,
    disabledAlpha: Float = 0.5f,
    textSize: Float = if (flat) 12f else 13f,
    minHeight: Dp = if (flat) 36.dp else 46.dp,
    minWidth: Dp = 64.dp,
    padding: PaddingValues = if (flat) PaddingValues(horizontal = 10.dp, vertical = 4.dp) else PaddingValues(horizontal = 10.dp, vertical = 4.dp),
    fill: Color = if (primary) Ui.Blue else Ui.Card,
    stroke: Color? = if (primary) null else Ui.Border,
    textColor: Color = if (primary) Color.White else Ui.Blue,
    radius: Dp = if (flat) 10.dp else 12.dp,
    start: Boolean = false,
    icon: Painter? = null,
    description: String? = null,
    ripple: Boolean = true,
    disabledText: Color? = null,
    bold: Boolean = false,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(radius)
    val gradient = fill == Ui.Blue
    val elevation = when { !enabled -> 0.dp; gradient -> 5.dp; flat -> 0.dp; else -> 1.5.dp }
    Ui.Ripples {
        Row(
            modifier
                .pressScale(source)
                .alpha(if (enabled) 1f else disabledAlpha)
                .defaultMinSize(minWidth, minHeight)
                .softShadow(elevation, shape, if (gradient) 0.28f else 0.10f)
                .then(if (gradient && enabled) Modifier.background(Ui.primaryBrush, shape) else Modifier.background(if (gradient) Ui.Pale else fill, shape))
                .then(if (gradient && !enabled) Modifier.border(BorderStroke(1.dp, Ui.Border), shape) else Modifier)
                .then(if (stroke != null) Modifier.border(BorderStroke(1.dp, stroke), shape) else Modifier)
                .clip(shape)
                .clickable(source, if (ripple) ripple() else null, enabled = enabled, role = Role.Button, onClick = onClick)
                .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
                .padding(padding),
            horizontalArrangement = if (start) Arrangement.Start else Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val color = if (enabled) textColor else disabledText ?: Ui.Muted
            if (icon != null) {
                Image(icon, null, Modifier.size(16.dp), colorFilter = ColorFilter.tint(color))
                Spacer(Modifier.width(5.dp))
            }
            androidx.compose.material3.Text(label, if (icon != null) Modifier.weight(1f) else Modifier,
                style = textStyle(textSize, color, if (gradient || bold) FontWeight.Bold else FontWeight.Medium).copy(letterSpacing = 0.3.sp),
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = if (start) TextAlign.Start else TextAlign.Center)
        }
    }
}

/** Glassy card: surface, 16dp corners, hairline highlight border and a soft blue shadow. */
@Composable
fun BCard(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(14.dp, 10.dp), content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.fillMaxWidth().softShadow(6.dp, shape, 0.10f).background(Ui.Card, shape)
        .border(1.dp, Brush.verticalGradient(listOf(Ui.CardHi, Ui.Border)), shape).padding(padding), content = content)
}

@Composable
fun Divider(color: Color = Ui.Border) { Box(Modifier.fillMaxWidth().height(1.dp).background(color)) }

@Composable
fun Chevron(open: Boolean, modifier: Modifier = Modifier, alpha: Float = 1f) {
    val angle by animateFloatAsState(if (open) 90f else 0f, spring(dampingRatio = 0.7f, stiffness = 500f), label = "chevron")
    Box(modifier, contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.ic_chevron), null, Modifier.rotate(angle).alpha(alpha), colorFilter = ColorFilter.tint(Ui.Muted))
    }
}

/** Switch: gradient track, white thumb with a tiny shadow, springy thumb travel. */
@Composable
fun CompactSwitch(checked: Boolean, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val progress by animateFloatAsState(if (checked) 1f else 0f, spring(dampingRatio = 0.62f, stiffness = 700f), label = "thumb")
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Box(
        modifier
            .clickable(remember { MutableInteractionSource() }, null, enabled = enabled, role = Role.Switch) {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove); onChange(!checked)
            }
            .semantics { contentDescription = label }
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        val shape = RoundedCornerShape(10.dp)
        Box(Modifier.size(36.dp, 20.dp).clip(shape).background(Ui.Border)) {
            Box(Modifier.matchParentSize().graphicsLayer { alpha = progress.coerceIn(0f, 1f) }.background(Ui.primaryBrush))
            Box(Modifier.align(Alignment.CenterStart).offset(x = 2.dp + 16.dp * progress).size(16.dp)
                .shadow(2.dp, CircleShape).background(Color.White, CircleShape))
        }
    }
}

fun Modifier.field(focused: Boolean = false, invalid: Boolean = false): Modifier {
    val shape = RoundedCornerShape(12.dp)
    val color = when { invalid -> Ui.Danger; focused -> Ui.Blue; else -> Ui.Border }
    return this.background(Ui.Card, shape).border(if (focused || invalid) 1.5.dp else 1.dp, color, shape)
}

/**
 * Segmented control. The plain variant has a gradient pill that glides to the selected cell (spring);
 * the log-level variant keeps colored chips.
 */
@Composable
fun Segments(labels: List<String>, selected: Int, modifier: Modifier = Modifier, textSize: Float = 12f, height: Dp = 36.dp,
             colors: List<Color>? = null, descriptions: List<String>? = null, change: (Int) -> Unit) {
    val outer = RoundedCornerShape(12.dp)
    if (colors == null) {
        val pos by animateFloatAsState(selected.toFloat(), spring(dampingRatio = 0.75f, stiffness = 600f), label = "seg")
        androidx.compose.foundation.layout.BoxWithConstraints(modifier.background(Ui.Card, outer).border(1.dp, Ui.Border, outer).clip(outer).padding(3.dp)) {
            val cell = maxWidth / labels.size
            Box(Modifier.offset(x = cell * pos).width(cell).height(height - 6.dp).softShadow(3.dp, RoundedCornerShape(9.dp), 0.25f)
                .background(Ui.primaryBrush, RoundedCornerShape(9.dp)))
            Row(Modifier.fillMaxWidth()) {
                labels.forEachIndexed { index, label ->
                    val on = index == selected
                    Box(Modifier.weight(1f).height(height - 6.dp).clip(RoundedCornerShape(9.dp))
                        .clickable(remember { MutableInteractionSource() }, null, role = Role.Tab) { if (!on) change(index) }
                        .then(descriptions?.getOrNull(index)?.let { d -> Modifier.semantics { contentDescription = d } } ?: Modifier),
                        contentAlignment = Alignment.Center) {
                        val tc by animateColorAsState(if (on) Color.White else Ui.Muted, tween(180), label = "segText")
                        BText(label, textSize, tc, maxLines = 1, weight = if (on) FontWeight.Bold else FontWeight.Medium, align = TextAlign.Center, padding = NoPadding)
                    }
                }
            }
        }
        return
    }
    Row(modifier) {
        labels.forEachIndexed { index, label ->
            val on = index == selected
            val color = colors[index]
            val shape = RoundedCornerShape(10.dp)
            val bg by animateColorAsState(color.copy(alpha = if (on) 0.22f else 0.07f), tween(180), label = "chip")
            CompositionLocalProvider(LocalRippleConfiguration provides RippleConfiguration(color, RippleAlpha(0.16f, 0.16f, 0.16f, 0.2f))) {
                Box(
                    Modifier.weight(1f).padding(start = if (index == 0) 0.dp else 2.dp, end = if (index == labels.lastIndex) 0.dp else 2.dp)
                        .background(bg, shape).border(1.dp, color.copy(alpha = if (on) 0.7f else 0.22f), shape).clip(shape)
                        .defaultMinSize(minHeight = height)
                        .clickable(role = Role.Tab) { if (!on) change(index) }
                        .then(descriptions?.getOrNull(index)?.let { d -> Modifier.semantics { contentDescription = d } } ?: Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (on) Box(Modifier.padding(end = 4.dp).size(5.dp).background(color, CircleShape))
                        BText(label, textSize, color, maxLines = 1, weight = if (on) FontWeight.Bold else FontWeight.Medium, align = TextAlign.Center, padding = NoPadding)
                    }
                }
            }
        }
    }
}

@Composable
fun ToggleButton(label: String, selected: Boolean, modifier: Modifier = Modifier, textSize: Float = 12f, minHeight: Dp = 36.dp, onClick: () -> Unit) {
    val fill by animateColorAsState(if (selected) Ui.Pale else Ui.Card, tween(180), label = "toggleFill")
    BButton(label, modifier, flat = true, textSize = textSize, minHeight = minHeight, radius = 10.dp,
        fill = fill, stroke = if (selected) Ui.Blue else Ui.Border,
        textColor = if (selected) Ui.Blue else Ui.Muted, onClick = onClick)
}

/** Link card with a tinted chevron bubble. */
@Composable
fun SettingsCard(title: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val source = remember { MutableInteractionSource() }
    Ui.Ripples {
        Row(modifier.fillMaxWidth().pressScale(source, 0.98f).softShadow(3.dp, shape, 0.08f).background(Ui.Card, shape).clip(shape)
            .clickable(source, ripple(), onClick = onClick)
            .padding(start = 14.dp, end = 10.dp).height(50.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp, 18.dp).background(Ui.primaryBrush, RoundedCornerShape(3.dp)))
            Spacer(Modifier.width(10.dp))
            BText(title, 14f, weight = FontWeight.Bold, padding = NoPadding, modifier = Modifier.weight(1f))
            Box(Modifier.size(26.dp).background(Ui.Pale, CircleShape), contentAlignment = Alignment.Center) {
                BText("›", 18f, Ui.Blue, padding = NoPadding, align = TextAlign.Center)
            }
        }
    }
}

fun RowScope.weighted(weight: Float = 1f) = Modifier.weight(weight)

/** Paint [color] outside a rounded rect (for views that ignore clipping, e.g. SurfaceView). */
fun Modifier.roundedMask(radius: Dp, color: Color): Modifier = this.drawWithContent {
    drawContent()
    val r = radius.toPx()
    val outer = androidx.compose.ui.graphics.Path().apply { addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height)) }
    val inner = androidx.compose.ui.graphics.Path().apply {
        addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, androidx.compose.ui.geometry.CornerRadius(r, r)))
    }
    drawPath(androidx.compose.ui.graphics.Path.combine(androidx.compose.ui.graphics.PathOperation.Difference, outer, inner), color)
}
