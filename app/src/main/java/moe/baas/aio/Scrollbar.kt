package moe.baas.aio

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.snapshotFlow

/**
 * The platform ScrollView scrollbar (Theme.Material.Light): 4dp square thumb, black at 31%, at the
 * viewport's right edge. Like View.awakenScrollBars it shows on attach for 4×300ms, then after every
 * scroll change for 300ms, and fades out over 250ms. Put it before verticalScroll() in the chain.
 */
fun Modifier.fadingScrollbar(state: ScrollState, firstDelay: Long = 1200L): Modifier = composed {
    val alpha = remember(state) { Animatable(0f) }
    LaunchedEffect(state) {
        var first = true
        snapshotFlow { state.value }.collectLatest {
            val wait = if (first) firstDelay else 300L
            first = false
            if (wait < 0) return@collectLatest
            alpha.snapTo(1f); delay(wait); alpha.animateTo(0f, tween(250, easing = LinearEasing))
        }
    }
    drawWithContent {
        drawContent()
        val max = state.maxValue
        val a = alpha.value
        if (max <= 0 || max == Int.MAX_VALUE || a <= 0f) return@drawWithContent
        val thick = 4.dp.toPx()
        val view = size.height
        val length = maxOf(view * view / (view + max), thick * 2)
        val top = (view - length) * state.value.coerceIn(0, max) / max
        drawRect(Color.Black.copy(alpha = 0.31f * a), Offset(size.width - thick, top), Size(thick, length))
    }
}
