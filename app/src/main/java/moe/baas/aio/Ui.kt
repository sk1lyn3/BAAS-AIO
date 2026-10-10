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
 * Compose counterparts of the original [UiTheme] View helpers. Every value here is copied from
 * UiTheme.kt / Theme.Material.Light so screens look exactly like the View implementation.
 */
object Ui {
    val Blue = Color(0xFF008FD3)
    val Navy = Color(0xFF20394E)
    val Muted = Color(0xFF657A8B)
    val Pale = Color(0xFFEDF7FD)
    val Border = Color(0xFFDAE8F1)
    val Danger = Color(0xFFDC4852)
    val Success = Color(0xFF1C8B66)
    val DeepBlue = Color(0xFF006DA6)
    val LightGray = Color(0xFFCCCCCC)
    /** Platform RippleDrawable(0x22008fd3) and quietRipple (0x10657a8b). */
    private val blueRipple = RippleConfiguration(Color(0xFF008FD3), RippleAlpha(0.133f, 0.133f, 0.133f, 0.133f))
    private val quietRipple = RippleConfiguration(Color(0xFF657A8B), RippleAlpha(0.063f, 0.063f, 0.063f, 0.063f))

    @Composable fun Ripples(content: @Composable () -> Unit) =
        CompositionLocalProvider(LocalRippleConfiguration provides blueRipple, content = content)

    @Composable fun Quiet(content: @Composable () -> Unit) =
        CompositionLocalProvider(LocalRippleConfiguration provides quietRipple, content = content)
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
 * UiTheme.button / flatButton. Raised buttons keep the Material state-list elevation
 * (2dp, +4dp while pressed, none when disabled); flat buttons have none.
 */
@Composable
fun BButton(
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    flat: Boolean = false,
    enabled: Boolean = true,
    disabledAlpha: Float = 1f,
    textSize: Float = if (flat) 12f else 13f,
    minHeight: Dp = if (flat) 36.dp else 48.dp,
    minWidth: Dp = 88.dp,
    padding: PaddingValues = if (flat) PaddingValues(horizontal = 10.dp, vertical = 4.dp) else PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    fill: Color = if (primary) Ui.Blue else Color.White,
    stroke: Color? = if (primary) null else Ui.Border,
    textColor: Color = if (primary) Color.White else Ui.Blue,
    radius: Dp = 10.dp,
    start: Boolean = false,
    icon: Painter? = null,
    description: String? = null,
    ripple: Boolean = true,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val shape = RoundedCornerShape(radius)
    val elevation by animateDpAsState(if (flat || !enabled) 0.dp else if (pressed) 6.dp else 2.dp, tween(100), label = "elevation")
    Ui.Ripples {
        Row(
            modifier
                .alpha(if (enabled) 1f else disabledAlpha)
                .defaultMinSize(minWidth, minHeight)
                .then(if (elevation > 0.dp) Modifier.shadow(elevation, shape, clip = false) else Modifier)
                .background(fill, shape)
                .then(if (stroke != null) Modifier.border(BorderStroke(1.dp, stroke), shape) else Modifier)
                .clip(shape)
                .clickable(source, if (ripple) ripple() else null, enabled = enabled, role = Role.Button, onClick = onClick)
                .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
                .padding(padding),
            horizontalArrangement = if (start) Arrangement.Start else Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val color = if (enabled) textColor else Ui.Muted
            // A compound drawableStart sits at the padding edge; the label stays centred in the rest.
            if (icon != null) {
                Image(icon, null, Modifier.size(16.dp), colorFilter = ColorFilter.tint(color))
                Spacer(Modifier.width(5.dp))
            }
            androidx.compose.material3.Text(label, if (icon != null) Modifier.weight(1f) else Modifier,
                style = textStyle(textSize, color, FontWeight.Medium),
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = if (start) TextAlign.Start else TextAlign.Center)
        }
    }
}

/** UiTheme.card: white, radius 12, padding 12/8. */
@Composable
fun BCard(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(12.dp, 8.dp), content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).padding(padding), content = content)
}

@Composable
fun Divider(color: Color = Ui.Border) { Box(Modifier.fillMaxWidth().height(1.dp).background(color)) }

/** UiTheme.chevron with the 140ms rotation used for every expandable header and menu. */
@Composable
fun Chevron(open: Boolean, modifier: Modifier = Modifier, alpha: Float = 1f) {
    val angle by animateFloatAsState(if (open) 90f else 0f, tween(140), label = "chevron")
    Box(modifier, contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.ic_chevron), null, Modifier.rotate(angle).alpha(alpha))
    }
}

/**
 * UiTheme.compactSwitch: 32x18 track (blue / border, 180ms fade), 16dp white thumb, drawn at the
 * end of its box with 4dp side padding like the platform Switch.
 */
@Composable
fun CompactSwitch(checked: Boolean, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val track by animateColorAsState(if (checked) Ui.Blue else Ui.Border, tween(180), label = "track")
    val thumb by animateDpAsState(if (checked) 16.dp else 0.dp, tween(250), label = "thumb")
    Box(
        modifier
            .clickable(remember { MutableInteractionSource() }, null, enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .semantics { contentDescription = label }
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(Modifier.size(32.dp, 18.dp).background(track, RoundedCornerShape(9.dp))) {
            Box(Modifier.align(Alignment.CenterStart).offset(x = thumb).size(16.dp).background(Color.White, CircleShape))
        }
    }
}

/** UiTheme.fieldBackground. */
fun Modifier.field(focused: Boolean = false, invalid: Boolean = false): Modifier {
    val shape = RoundedCornerShape(10.dp)
    val color = when { invalid -> Ui.Danger; focused -> Ui.Blue; else -> Ui.Border }
    return this.background(Color.White, shape).border(if (focused || invalid) 2.dp else 1.dp, color, shape)
}

/** UiTheme.segments; [colors] gives the log-level variant. */
@Composable
fun Segments(labels: List<String>, selected: Int, modifier: Modifier = Modifier, textSize: Float = 12f, height: Dp = 36.dp,
             colors: List<Color>? = null, descriptions: List<String>? = null, change: (Int) -> Unit) {
    val outer = RoundedCornerShape(8.dp)
    Row(if (colors == null) modifier.background(Color.White, outer).border(1.dp, Ui.Border, outer).clip(outer) else modifier) {
        labels.forEachIndexed { index, label ->
            val on = index == selected
            val color = colors?.get(index)
            val shape = RoundedCornerShape(6.dp)
            val cell = if (color == null) Modifier.background(if (on) Ui.Blue else Color.Transparent)
            else Modifier.padding(start = if (index == 0) 0.dp else 2.dp, end = if (index == labels.lastIndex) 0.dp else 2.dp)
                .background(color.copy(alpha = if (on) 0x30 / 255f else 0x10 / 255f), shape)
                .border(1.dp, color.copy(alpha = if (on) 0xB0 / 255f else 0x40 / 255f), shape).clip(shape)
            val ripple = if (color == null) Ui.Blue else color
            CompositionLocalProvider(LocalRippleConfiguration provides RippleConfiguration(ripple, RippleAlpha(0.133f, 0.133f, 0.133f, 0.133f))) {
                Box(
                    Modifier.weight(1f).then(cell).defaultMinSize(minHeight = height)
                        .clickable(role = Role.Tab) { if (!on) change(index) }
                        .then(descriptions?.getOrNull(index)?.let { d -> Modifier.semantics { contentDescription = d } } ?: Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    BText(label, textSize, color ?: if (on) Color.White else Ui.Muted, maxLines = 1,
                        weight = if (color != null && on) FontWeight.Bold else FontWeight.Normal, align = TextAlign.Center)
                }
            }
        }
    }
}

/** UiTheme.setToggleSelected applied to a flat button. */
@Composable
fun ToggleButton(label: String, selected: Boolean, modifier: Modifier = Modifier, textSize: Float = 12f, minHeight: Dp = 36.dp, onClick: () -> Unit) =
    BButton(label, modifier, flat = true, textSize = textSize, minHeight = minHeight, radius = 8.dp,
        fill = if (selected) Ui.Pale else Color.White, stroke = if (selected) Ui.Blue else Ui.Border,
        textColor = if (selected) Ui.Blue else Ui.Muted, onClick = onClick)

/** HomeActivity.settingsCard. */
@Composable
fun SettingsCard(title: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Ui.Ripples {
        Row(modifier.fillMaxWidth().background(Color.White, shape).clip(shape).clickable(onClick = onClick)
            .padding(start = 12.dp, end = 8.dp).height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            BText(title, 13f, weight = FontWeight.Bold, padding = NoPadding, modifier = Modifier.weight(1f))
            BText("›", 22f, Ui.Muted, padding = NoPadding, align = TextAlign.Center, modifier = Modifier.width(24.dp))
        }
    }
}

/** RowScope helper mirroring `item(view, weight)`. */
fun RowScope.weighted(weight: Float = 1f) = Modifier.weight(weight)
