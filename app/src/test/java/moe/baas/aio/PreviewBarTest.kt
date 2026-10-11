package moe.baas.aio

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Fullscreen preview top bar: original vs v3 vs design options (mockups only, not shipped). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w851dp-h393dp-land-xxhdpi")
class PreviewBarTest {
    @get:Rule val rule = createComposeRule()
    private val out = (System.getProperty("shot.dir") ?: "/tmp/shots") + "/previewbar"
    private val game by lazy { BitmapFactory.decodeFile("/tmp/game.png").asImageBitmap() }
    private data class S(val name: String, val status: String, val take: String?)
    private val states = listOf(S("connecting", "连接中…", null), S("running", "执行中 · 竞技场", "手动接管"), S("paused", "已暂停 · 竞技场", "继续任务"), S("running-long", "执行中 · 总力战 · 自动编队与扫荡（困难）", "手动接管"))
    private var cur by androidx.compose.runtime.mutableStateOf(states[0])
    private val dark = Color(0x99060B14)

    @Composable private fun Stage(bar: @Composable BoxScope.(S) -> Unit) = Box(Modifier.fillMaxSize().background(Color.Black)) {
        Image(game, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        bar(cur)
    }

    @Composable private fun BoxScope.Original(s: S) = Row(Modifier.fillMaxWidth().align(Alignment.TopStart)) {
        // LinearLayout(HORIZONTAL) is baseline-aligned by default: the 14sp strip sits on the button's text baseline.
        Text(s.status, Modifier.weight(1f).alignByBaseline().background(Color(0x99000000)).padding(start = 12.dp), style = textStyle(14f, Color.White))
        if (s.take != null) Box(Modifier.alignByBaseline()) { MaterialDefaultButton(s.take, {}, enabled = true) }
    }
    @Composable private fun BoxScope.V3(s: S) = FullscreenBar(s.status, s.take)
    @Composable private fun FullscreenBar(status: String, take: String?) = Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f, fill = false).background(dark, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 7.dp)) {
            Text(status, style = textStyle(13f, Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.weight(0.0001f))
        if (take != null) BButton(take, Modifier.padding(start = 8.dp), flat = true, disabledText = Color.White, minHeight = 36.dp, radius = 18.dp,
            fill = dark, stroke = Color.White.copy(alpha = .35f), textColor = Color.White, padding = PaddingValues(horizontal = 16.dp)) {}
    }
    private fun dot(s: S) = when { s.take == null -> Color(0xFFFFC857); s.name == "paused" -> Color(0xFFFFC857); else -> Color(0xFF3EE6A8) }

    /** A: v3 pills, but takeover pinned to its own fixed top-end slot; status pill hugs text, capped + ellipsized, grows only to the right. */
    @Composable private fun BoxScope.OptionA(s: S) {
        Box(Modifier.align(Alignment.TopStart).padding(10.dp).widthIn(max = 520.dp).background(dark, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 7.dp)) {
            Text(s.status, style = textStyle(13f, Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (s.take != null) BButton(s.take, Modifier.align(Alignment.TopEnd).padding(10.dp).width(104.dp), flat = true, minHeight = 36.dp, radius = 18.dp,
            fill = dark, stroke = Color.White.copy(alpha = .35f), textColor = Color.White, padding = PaddingValues(horizontal = 12.dp)) {}
    }
    /** B: fixed-width top-edge capsule, centered: 260dp status segment (ellipsis) + fixed 84dp takeover segment -> nothing moves. */
    @Composable private fun BoxScope.OptionB(s: S) = Row(Modifier.align(Alignment.TopCenter)
        .background(dark, RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)).height(26.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.width(260.dp).padding(start = 10.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(dot(s), CircleShape))
            Text(s.status, Modifier.padding(start = 6.dp), style = textStyle(12f, Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.width(84.dp).fillMaxHeight().background(if (s.take != null) Color.White.copy(alpha = .16f) else Color.Transparent,
            RoundedCornerShape(bottomEnd = 12.dp)), contentAlignment = Alignment.Center) { if (s.take != null) Text(s.take, style = textStyle(12f, Color(0xFF8FD8FF))) }
    }
    /** C: original geometry (full-width text-height strip, 14sp), only the button restyled as a compact v3 pill. */
    @Composable private fun BoxScope.OptionC(s: S) = Row(Modifier.fillMaxWidth().align(Alignment.TopStart), verticalAlignment = Alignment.Top) {
        Text(s.status, Modifier.weight(1f).background(Color(0x99000000)).padding(start = 12.dp), style = textStyle(14f, Color.White))
        if (s.take != null) BButton(s.take, Modifier.width(96.dp), flat = true, minHeight = 30.dp, radius = 0.dp, fill = Color(0x99000000), stroke = Color.Transparent,
            textColor = Color(0xFF8FD8FF), textSize = 13f, minWidth = 0.dp, padding = PaddingValues(horizontal = 14.dp)) {}
    }

    private var variant by androidx.compose.runtime.mutableStateOf("original")
    @Test fun bars() {
        rule.setContent { Ui.Ripples { Stage { s -> when (variant) {
            "original" -> Original(s); "v3" -> V3(s); "A" -> OptionA(s); "B" -> OptionB(s); else -> OptionC(s) } } } }
        for (v in listOf("original", "v3", "A", "B", "C")) for (s in states) {
            variant = v; cur = s; rule.waitForIdle()
            rule.onRoot().captureRoboImage("$out/$v-${s.name}.png")
        }
    }
}
