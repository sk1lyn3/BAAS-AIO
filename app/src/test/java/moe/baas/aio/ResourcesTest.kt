package moe.baas.aio

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 体力/信用点/青辉石 row: worst-case widths, and no '—' flash while sliding away from 托管. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi", fontScale = 1.3f)
class ResourcesRenderTest {
    @get:Rule val rule = createComposeRule()
    private val out = (System.getProperty("shot.dir") ?: "/tmp/shots") + "/resources"
    private val worst = listOf(ResourceChip(R.drawable.currency_icon_ap, "999/999", ""), ResourceChip(R.drawable.currency_icon_gold, "9999.9M", ""),
        ResourceChip(R.drawable.currency_icon_gem, "99,999", ""))

    /** The pre-fix row, verbatim. */
    @androidx.compose.runtime.Composable private fun Before() = Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        worst.forEachIndexed { index, c ->
            Row(Modifier.padding(start = if (index == 0) 0.dp else 6.dp, top = 4.dp, bottom = 4.dp).background(Ui.Pale, CircleShape)
                .padding(start = 4.dp, end = 9.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(c.icon), null, Modifier.size(22.dp, 18.dp)); Spacer(Modifier.width(3.dp))
                RollingText(c.value, 11f, Ui.Navy, FontWeight.Bold)
            }
        }
    }
    @androidx.compose.runtime.Composable private fun Card(content: @androidx.compose.runtime.Composable () -> Unit) =
        Column(Modifier.haloBackdrop().padding(10.dp)) {
            BCard(Modifier.padding(4.dp)) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(4.dp)) { BText("default", 14f); BText("官服", 11f, Ui.Muted); content() }
                    CompactSwitch(true, "托管", Modifier.size(44.dp, 44.dp)) {}
                }
            }
        }
    @Test fun odometerFrames() {
        val real = listOf(ResourceChip(R.drawable.currency_icon_ap, "186/240", ""), ResourceChip(R.drawable.currency_icon_gold, "12.3M", ""),
            ResourceChip(R.drawable.currency_icon_gem, "24,680", ""))
        rule.mainClock.autoAdvance = false
        var on by androidx.compose.runtime.mutableStateOf(false)
        rule.setContent { Card { if (on) ResourceChips(real, roll = true) } }
        var f = 0
        repeat(6) { rule.mainClock.advanceTimeBy(33); rule.onRoot().captureRoboImage("$out/odo-%03d.png".format(f++)) }
        on = true
        repeat(30) { rule.mainClock.advanceTimeBy(33); rule.onRoot().captureRoboImage("$out/odo-%03d.png".format(f++)) }
        repeat(8) { rule.mainClock.advanceTimeBy(66); rule.onRoot().captureRoboImage("$out/odo-%03d.png".format(f++)) }
    }
    /** The intro flag clearing after the roll must not move a single pixel (same renderer before/after). */
    @Test fun noJumpWhenIntroEnds() {
        val real = listOf(ResourceChip(R.drawable.currency_icon_ap, "186/240", ""), ResourceChip(R.drawable.currency_icon_gold, "12.3M", ""),
            ResourceChip(R.drawable.currency_icon_gem, "24,680", ""))
        rule.mainClock.autoAdvance = false
        var roll by androidx.compose.runtime.mutableStateOf(true)
        rule.setContent { Card { ResourceChips(real, roll = roll) } }
        rule.mainClock.advanceTimeBy(1150)
        rule.onRoot().captureRoboImage("$out/end-roll.png")
        roll = false; androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications(); rule.mainClock.advanceTimeBy(100)
        rule.onRoot().captureRoboImage("$out/end-final.png")
        val a = android.graphics.BitmapFactory.decodeFile("$out/end-roll.png"); val b = android.graphics.BitmapFactory.decodeFile("$out/end-final.png")
        var diff = 0
        for (y in 0 until a.height) for (x in 0 until a.width) if (a.getPixel(x, y) != b.getPixel(x, y)) diff++
        assertEquals("changed pixels at intro end", 0, diff)
    }
    @Test fun before() { rule.setContent { Card { Before() } }; rule.onRoot().captureRoboImage("$out/before.png") }
    @Test fun after() { rule.setContent { Card { ResourceChips(worst) } }; rule.onRoot().captureRoboImage("$out/after.png") }
    @Config(qualifiers = "+night") @Test fun afterDark() { Ui.night = true; rule.setContent { Card { ResourceChips(worst) } }; rule.onRoot().captureRoboImage("$out/after-dark.png"); Ui.night = false }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResourcesTransitionTest {
    @get:Rule val rule = createEmptyComposeRule()
    @Test fun noPlaceholderFlashWhenLeavingManaged() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = ProfileStore(ctx); val sel = store.selected()
        val now = System.currentTimeMillis() / 1000.0
        val f = File(sel.path, "config.json"); val cfg = JSONObject(f.readText())
        cfg.put("ap", JSONObject().put("count", 240).put("max", 240).put("time", now))
        cfg.put("creditpoints", JSONObject().put("count", 1234567).put("time", now))
        cfg.put("pyroxene", JSONObject().put("count", 24680).put("time", now))
        f.writeText(cfg.toString())
        ManagedMode.select(sel.id, true)
        ActivityScenario.launch(HomeActivity::class.java).use { s ->
            val switch = HomeActivity::class.java.declaredMethods.first { it.name == "switchPage" }.apply { isAccessible = true }
            s.onActivity { switch.invoke(it, "托管") }
            rule.waitForIdle()
            assertEquals(1, rule.onAllNodesWithText("24,680").fetchSemanticsNodes().size)
            rule.mainClock.autoAdvance = false
            s.onActivity { switch.invoke(it, "主页") }
            for (t in 0 until 6) {
                rule.mainClock.advanceTimeBy(40)
                assertEquals("frame $t shows placeholder", 0, rule.onAllNodesWithText("—").fetchSemanticsNodes().size)
            }
        }
    }

    @Test fun rollOnlyOnToggleNotOnTabSwitch() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = ProfileStore(ctx); val sel = store.selected()
        val now = System.currentTimeMillis() / 1000.0
        val f = File(sel.path, "config.json"); val cfg = JSONObject(f.readText())
        cfg.put("pyroxene", JSONObject().put("count", 24680).put("time", now)); f.writeText(cfg.toString())
        ManagedMode.select(sel.id, true)
        ActivityScenario.launch(HomeActivity::class.java).use { s ->
            val switch = HomeActivity::class.java.declaredMethods.first { it.name == "switchPage" }.apply { isAccessible = true }
            s.onActivity { switch.invoke(it, "托管") }; rule.waitForIdle()
            rule.mainClock.autoAdvance = false
            // Tab switches away and back: the value is shown as a whole number every frame (no digit wheel).
            for (p in listOf("主页", "托管")) {
                s.onActivity { switch.invoke(it, p) }
                for (t in 0 until 8) { rule.mainClock.advanceTimeBy(40)
                    s.onActivity { a -> assertEquals("$p frame $t: intro armed", 0, (HomeActivity::class.java.getDeclaredField("resourceRoll").apply { isAccessible = true }.get(a) as Map<*, *>).size) }
                    if ((p == "托管" && t >= 1) || (p == "主页" && t < 5)) assertEquals("$p frame $t", 1, rule.onAllNodesWithText("24,680").fetchSemanticsNodes().size) }
                rule.mainClock.advanceTimeBy(600)
            }
            // Toggle off -> on: the intro plays (digits split into wheels), then settles back to the whole value.
            rule.onNodeWithContentDescription("托管${sel.name}").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick); rule.mainClock.advanceTimeBy(300)
            rule.onNodeWithContentDescription("托管${sel.name}").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick); rule.mainClock.advanceTimeBy(100)
            s.onActivity { a -> assertEquals(true, (HomeActivity::class.java.getDeclaredField("resourceRoll").apply { isAccessible = true }.get(a) as Map<*, *>)[sel.id]) }
            rule.mainClock.advanceTimeBy(2000)
            assertEquals(1, rule.onAllNodesWithText("24,680").fetchSemanticsNodes().size)
        }
    }
}
