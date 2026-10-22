package moe.baas.aio

import android.widget.FrameLayout
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** Hosts the preview SurfaceView container (kept as a real View so letterboxing and touch mapping are unchanged). */
@Composable
fun SurfaceHost(host: FrameLayout, modifier: Modifier = Modifier) {
    AndroidView(factory = { (host.parent as? android.view.ViewGroup)?.removeView(host); host }, modifier = modifier)
}

/** PreviewActivity: black frame, Surface, and the top status strip + takeover button. */
@Composable
fun FullscreenPreview(host: FrameLayout, status: String, takeoverVisible: Boolean, takeoverEnabled: Boolean, takeoverText: String, onTakeover: () -> Unit,
                      captureEnabled: Boolean? = null, onCapture: () -> Unit = {}) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        SurfaceHost(host, Modifier.fillMaxSize())
        PreviewTopBar(status, TaskRuntime.state, takeoverVisible, takeoverEnabled, takeoverText, onTakeover,
            captureEnabled = captureEnabled, onCapture = onCapture, modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top)))
    }
}

/**
 * Option B top bar: a 26dp capsule hugging the top edge, centered, fixed 260dp status zone + fixed 84dp button zone.
 * Total width never changes and nothing moves. Only the invisible 84x48dp hit box under the button zone takes touches;
 * the status zone has no pointer input, so touches there reach the game surface.
 */
@Composable
fun PreviewTopBar(status: String, state: String, takeoverVisible: Boolean, takeoverEnabled: Boolean, takeoverText: String,
                  onTakeover: () -> Unit, modifier: Modifier = Modifier, captureEnabled: Boolean? = null, onCapture: () -> Unit = {}) {
    val shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
    val dot by animateColorAsState(when (state) {
        "running", "starting" -> Color(0xFF3EE6A8); "failed" -> Color(0xFFFF6B75); "paused", "pausing" -> Color(0xFFFFC857)
        else -> Color(0xFFFFC857)
    }, tween(150), label = "dot")
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val active = takeoverVisible && takeoverEnabled
    // Button zone collapses to 0 when absent and expands over 200ms when it appears; the pill stays centered,
    // so whenever the button is present it always sits in the same spot.
    val zone by animateDpAsState(if (takeoverVisible) 84.dp else 0.dp, tween(200), label = "zone")
    var lastTake by remember { mutableStateOf(takeoverText) }
    if (takeoverVisible) lastTake = takeoverText
    // The capture cell is mirrored by an empty slot on the right, so the capsule stays centered and the
    // takeover zone keeps its position while the cell expands or collapses.
    val cell by animateDpAsState(if (captureEnabled != null) 36.dp else 0.dp, tween(200), label = "capture")
    var lastCapture by remember { mutableStateOf(true) }
    if (captureEnabled != null) lastCapture = captureEnabled
    val captureInteraction = remember { MutableInteractionSource() }
    val capturePressed by captureInteraction.collectIsPressedAsState()
    Row(modifier) {
        if (cell > 0.dp) Box(Modifier.width(cell).height(48.dp)
            .clickable(captureInteraction, null, enabled = captureEnabled == true, role = Role.Button, onClickLabel = CAPTURE_LABEL) { onCapture() }
            .semantics { contentDescription = CAPTURE_LABEL }) {
            Box(Modifier.fillMaxWidth().height(26.dp).clip(RoundedCornerShape(bottomStart = 12.dp)).background(Color(0x99060B14))
                .background(Color.White.copy(alpha = if (capturePressed) .28f else .10f)), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Icon(CameraIcon, null, Modifier.requiredSize(15.dp), tint = Ui.PreviewAccent.copy(alpha = if (lastCapture) 1f else .4f))
            }
        }
        Box {
            Row(Modifier.height(26.dp).clip(if (cell > 0.dp) RoundedCornerShape(bottomEnd = 12.dp) else shape).background(Color(0x99060B14)),
                verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.width(260.dp).padding(start = 10.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(dot, CircleShape))
                    Crossfade(status, Modifier.padding(start = 6.dp).weight(1f), animationSpec = tween(150), label = "status") { text -> MarqueeText(text) }
                }
                Box(Modifier.width(zone).fillMaxHeight().background(if (pressed) Color.White.copy(alpha = .28f) else Color.White.copy(alpha = .16f)),
                    contentAlignment = Alignment.Center) {
                    Crossfade(lastTake, Modifier.requiredWidth(84.dp), animationSpec = tween(150), label = "take") { text ->
                        androidx.compose.material3.Text(text, Modifier.fillMaxWidth(), style = textStyle(12f, Ui.PreviewAccent.copy(alpha = if (takeoverEnabled) 1f else .5f)),
                            maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
            if (takeoverVisible) Box(Modifier.align(Alignment.TopEnd).size(84.dp, 48.dp)
                .clickable(interaction, null, enabled = active, role = Role.Button, onClickLabel = takeoverText) { onTakeover() }
                .semantics { contentDescription = takeoverText })
        }
        Spacer(Modifier.width(cell))
    }
}

/** Static when it fits; otherwise scrolls (marquee, 1s initial delay) with soft edge fades. */
@Composable
private fun MarqueeText(text: String) = androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
    val limit = constraints.maxWidth
    var overflow by remember(text) { mutableStateOf(false) }
    val style = textStyle(12f, Color.White)
    val fade = Modifier.graphicsLayer(compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen).drawWithContent {
        drawContent()
        val e = 10.dp.toPx() / size.width
        drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(0f to Color.Transparent, e to Color.Black, 1 - e to Color.Black, 1f to Color.Transparent),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
    }
    androidx.compose.material3.Text(text,
        (if (overflow) Modifier.layout { m, c ->
            // Widen by the lead gap and shift left by it, so at rest the first glyph sits exactly where static text would.
            val lead = 12.dp.roundToPx()
            val p = m.measure(c.copy(minWidth = c.minWidth + lead, maxWidth = c.maxWidth + lead))
            layout(p.width - lead, p.height) { p.place(-lead, 0) }
        }.then(fade).basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1000, repeatDelayMillis = 1200, spacing = MarqueeSpacing(20.dp))
            // 12dp lead gap (> 10dp edge fade) scrolls with the text: first glyph is never faded at rest; 20 + 12 = 32dp loop gap.
            .padding(start = 12.dp) else Modifier),
        style = style, maxLines = 1, softWrap = false,
        onTextLayout = { if (!overflow && (it.didOverflowWidth || it.size.width > limit)) overflow = true })
}

private const val CAPTURE_LABEL = "截图游戏画面"

/** Material Outlined.PhotoCamera, inlined to avoid pulling in material-icons-extended. */
private val CameraIcon = androidx.compose.ui.graphics.vector.ImageVector.Builder("PhotoCamera", 24.dp, 24.dp, 24f, 24f).addPath(
    androidx.compose.ui.graphics.vector.addPathNodes("M14.12,4l1.83,2L20,6v12L4,18L4,6h4.05l1.83,-2h4.24M15,2L9,2L7.17,4L4,4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16" +
        "c1.1,0 2,-0.9 2,-2L22,6c0,-1.1 -0.9,-2 -2,-2h-3.17L15,2zM12,9c1.65,0 3,1.35 3,3s-1.35,3 -3,3 -3,-1.35 -3,-3 1.35,-3 3,-3m0,-2" +
        "c-2.76,0 -5,2.24 -5,5s2.24,5 5,5 5,-2.24 5,-5 -2.24,-5 -5,-5z"),
    fill = androidx.compose.ui.graphics.SolidColor(Color.Black)).build()

/** Icon-only capture button: [visual] circle inside a 48dp touch target. */
@Composable
fun CaptureButton(visual: androidx.compose.ui.unit.Dp, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Ui.Ripples {
        Box(modifier.size(48.dp).clickable(interaction, null, enabled = enabled, role = Role.Button, onClickLabel = CAPTURE_LABEL) { onClick() }
            .semantics { contentDescription = CAPTURE_LABEL }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(visual).graphicsLayer { alpha = if (enabled) 1f else .45f }
                .background(Ui.Card, CircleShape).border(1.dp, Ui.Border, CircleShape).clip(CircleShape)
                .indication(interaction, androidx.compose.material3.ripple()), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Icon(CameraIcon, null, Modifier.size(visual * .56f), tint = Ui.Blue)
            }
        }
    }
}
