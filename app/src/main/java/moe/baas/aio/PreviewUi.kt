package moe.baas.aio

import android.widget.FrameLayout
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** Hosts the preview SurfaceView container (kept as a real View so letterboxing and touch mapping are unchanged). */
@Composable
fun SurfaceHost(host: FrameLayout, modifier: Modifier = Modifier) {
    AndroidView(factory = { (host.parent as? android.view.ViewGroup)?.removeView(host); host }, modifier = modifier)
}

/**
 * The platform Widget.Material.Button look (btn_default_material): #D6D7D7 fill inset 4/6dp,
 * radius 2dp, raised 2dp (+4dp pressed), 14sp medium all-caps unless restyled.
 */
@Composable
fun MaterialDefaultButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    textSize: Float = 14f,
    textColor: Color = Color(0xDE000000),
    disabledTextColor: Color = Color(0x42000000),
    allCaps: Boolean = true,
    minHeight: Dp = 48.dp,
    /** Total View padding: 4/6 drawable inset + 8/4 shape padding by default. */
    padding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val elevation by animateDpAsState(if (!enabled) 0.dp else if (pressed) 6.dp else 2.dp, tween(100), label = "elevation")
    val shape = RoundedCornerShape(2.dp)
    Box(modifier.defaultMinSize(88.dp, minHeight).clickable(source, null, enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        // Background is inset 4dp horizontally / 6dp vertically like the platform drawable.
        Box(Modifier.matchParentSize().padding(horizontal = 4.dp, vertical = 6.dp).shadow(elevation, shape).background(if (enabled) Color(0xFFD6D7D7) else Color(0x42D6D7D7), shape))
        // Pressed highlight stays inside the inset shape, like the platform ripple mask.
        if (pressed) Box(Modifier.matchParentSize().padding(horizontal = 4.dp, vertical = 6.dp).background(Color(0x1F000000), shape))
        androidx.compose.material3.Text(if (allCaps) text.uppercase() else text,
            Modifier.padding(padding),
            style = textStyle(textSize, if (enabled) textColor else disabledTextColor, FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** PreviewActivity: black frame, Surface, and the top status strip + takeover button. */
@Composable
fun FullscreenPreview(host: FrameLayout, status: String, takeoverVisible: Boolean, takeoverEnabled: Boolean, takeoverText: String, onTakeover: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        SurfaceHost(host, Modifier.fillMaxSize())
        PreviewTopBar(status, TaskRuntime.state, takeoverVisible, takeoverEnabled, takeoverText, onTakeover,
            Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top)))
    }
}

/**
 * Option B top bar: a 26dp capsule hugging the top edge, centered, fixed 260dp status zone + fixed 84dp button zone.
 * Total width never changes and nothing moves. Only the invisible 84x48dp hit box under the button zone takes touches;
 * the status zone has no pointer input, so touches there reach the game surface.
 */
@Composable
fun PreviewTopBar(status: String, state: String, takeoverVisible: Boolean, takeoverEnabled: Boolean, takeoverText: String,
                  onTakeover: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
    val dot by animateColorAsState(when (state) {
        "running", "starting" -> Color(0xFF3EE6A8); "failed" -> Color(0xFFFF6B75); "paused", "pausing" -> Color(0xFFFFC857)
        else -> Color(0xFFFFC857)
    }, tween(150), label = "dot")
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val active = takeoverVisible && takeoverEnabled
    Box(modifier.width(344.dp)) {
        Row(Modifier.height(26.dp).background(Color(0x99060B14), shape), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.width(260.dp).padding(start = 10.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(dot, CircleShape))
                Crossfade(status, Modifier.padding(start = 6.dp), animationSpec = tween(150), label = "status") { text ->
                    androidx.compose.material3.Text(text, style = textStyle(12f, Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(Modifier.width(84.dp).fillMaxHeight().background(when {
                !takeoverVisible -> Color.Transparent; pressed -> Color.White.copy(alpha = .28f); else -> Color.White.copy(alpha = .16f) },
                RoundedCornerShape(bottomEnd = 12.dp)), contentAlignment = Alignment.Center) {
                if (takeoverVisible) Crossfade(takeoverText, animationSpec = tween(150), label = "take") { text ->
                    androidx.compose.material3.Text(text, style = textStyle(12f, Color(0xFF8FD8FF).copy(alpha = if (takeoverEnabled) 1f else .5f)), maxLines = 1)
                }
            }
        }
        if (takeoverVisible) Box(Modifier.align(Alignment.TopEnd).size(84.dp, 48.dp)
            .clickable(interaction, null, enabled = active, role = Role.Button, onClickLabel = takeoverText) { onTakeover() }
            .semantics { contentDescription = takeoverText })
    }
}
