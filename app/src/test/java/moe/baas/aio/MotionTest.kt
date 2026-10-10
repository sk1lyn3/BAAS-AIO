package moe.baas.aio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Scripted frame-by-frame capture of the v3 motion (manual clock), turned into GIFs afterwards. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h851dp-xxhdpi")
class MotionTest {
    @get:Rule val rule = createComposeRule()
    private val out = (System.getProperty("shot.dir") ?: "/tmp/shots") + "/motion"

    private fun film(name: String, night: Boolean, steps: List<Pair<Int, () -> Unit>>, content: @androidx.compose.runtime.Composable () -> Unit) {
        Ui.night = night
        rule.mainClock.autoAdvance = false
        rule.setContent { Ui.Ripples { Column(Modifier.haloBackdrop().padding(14.dp)) { content() } } }
        var f = 0
        for ((frames, action) in steps) {
            action()
            repeat(frames) { rule.mainClock.advanceTimeBy(20); rule.onRoot().captureRoboImage("$out/$name-%03d.png".format(f++)) }
        }
    }

    private var page by mutableStateOf("主页")
    private var seg by mutableIntStateOf(0)
    private var on by mutableStateOf(false)
    private var ap by mutableStateOf("118/240")
    private var running by mutableStateOf(false)

    @androidx.compose.runtime.Composable
    private fun Demo() {
        HeroCard(active = running) {
            StatusChip("default", if (running) Color(0xFF3EE6A8) else Color.White, pulse = running, Modifier.padding(2.dp))
            RollingText(if (running) "执行中 · 竞技场" else "待机", 20f, Color.White, FontWeight.Bold, Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp))
            BText("官服 · 22 个任务已启用", 12f, Color.White.copy(alpha = .78f))
            Row(Modifier.padding(top = 6.dp)) {
                HeroButton("开始", Modifier.weight(1f).padding(3.dp), solid = true, enabled = !running) {}
                HeroButton("停止任务", Modifier.weight(1f).padding(3.dp), solid = false, enabled = running) {}
            }
        }
        Spacer(Modifier.height(14.dp))
        BCard {
            Row {
                BText("体力", 13f, Ui.Muted, Modifier.weight(1f)); RollingText(ap, 15f, Ui.Navy, FontWeight.Bold, Modifier.padding(6.dp))
            }
            Row { BText("托管 default", 13f, modifier = Modifier.weight(1f)); CompactSwitch(on, "s", Modifier.width(48.dp).height(36.dp)) {} }
        }
        Spacer(Modifier.height(14.dp))
        Segments(listOf("全部", "已启用", "已禁用"), seg, Modifier.fillMaxWidth()) {}
        Spacer(Modifier.height(14.dp))
        TabBar(page) {}
    }

    private fun script() = listOf<Pair<Int, () -> Unit>>(
        10 to {}, 18 to { page = "托管" }, 18 to { page = "调度"; seg = 1 }, 16 to { on = true; seg = 2 },
        22 to { running = true; ap = "126/240" }, 20 to { page = "设置" }, 18 to { page = "主页"; seg = 0; on = false },
        16 to { running = false; ap = "131/240" })

    @Test fun light() = film("demo-light", false, script()) { Demo() }
    @Test fun dark() = film("demo-dark", true, script()) { Demo() }
}
