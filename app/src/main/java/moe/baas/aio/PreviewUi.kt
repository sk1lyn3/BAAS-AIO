package moe.baas.aio

import android.widget.FrameLayout
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
        // v3: floating translucent status pill + glass takeover pill (no blur, cheap on Android 9).
        Row(Modifier.fillMaxWidth().align(Alignment.TopStart).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f, fill = false).background(Color(0x99060B14), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 7.dp)) {
                androidx.compose.material3.Text(status, style = textStyle(13f, Color.White), maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            androidx.compose.foundation.layout.Spacer(Modifier.weight(0.0001f))
            if (takeoverVisible) BButton(takeoverText, Modifier.padding(start = 8.dp), flat = true, enabled = takeoverEnabled, disabledText = Color.White,
                minHeight = 36.dp, radius = 18.dp, fill = Color(0x99060B14), stroke = Color.White.copy(alpha = .35f), textColor = Color.White,
                padding = PaddingValues(horizontal = 16.dp)) { onTakeover() }
        }
    }
}
