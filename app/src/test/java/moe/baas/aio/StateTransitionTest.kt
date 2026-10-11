package moe.baas.aio

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Regression: Compose must recompose on every runtime-state transition (no stale enabled flags). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StateTransitionTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private fun managed(on: Boolean) = check(ctx.getSharedPreferences("managed", Context.MODE_PRIVATE).edit().putBoolean("enabled", on).commit())

    @Before fun reset() { rule.mainClock.autoAdvance = false; ManagedMode.attach(ctx); managed(false); TaskRuntime.publish("idle", "test") }

    // Manual clock: other suites leave infinite animations (light band) on leaked activities.
    private fun settle() { repeat(3) { rule.mainClock.advanceTimeByFrame() } }

    // Same rule as Home "启动游戏" (509771e HomeActivity.kt:804), minus preview/profile inputs.
    private fun launchRule() = !TaskRuntime.isActive && !ManagedMode.enabled && !TaskRuntime.isProbeRunning

    @Test fun managedOnThenOffRestoresLaunchButton() {
        rule.setContent { Text(if (launchRule()) "launch:on" else "launch:off") }
        rule.onNodeWithText("launch:on").assertExists()
        managed(true); settle()
        rule.onNodeWithText("launch:off").assertExists()
        managed(false); settle()
        rule.onNodeWithText("launch:on").assertExists()
    }

    @Test fun taskStartPauseStopRestoresButtons() {
        rule.setContent { Text("state:${TaskRuntime.state}:${launchRule()}") }
        rule.onNodeWithText("state:idle:true").assertExists()
        for (s in listOf("starting", "running", "pausing", "paused", "running", "stopping")) {
            TaskRuntime.publish(s, "t"); settle()
            rule.onNodeWithText("state:$s:false").assertExists()
        }
        TaskRuntime.publish("stopped", "t"); settle()
        rule.onNodeWithText("state:stopped:true").assertExists()
        TaskRuntime.publish("failed", "t"); settle()
        rule.onNodeWithText("state:failed:true").assertExists()
    }

    @Test fun probeTogglesRecompose() {
        rule.setContent { Text(if (launchRule()) "p:on" else "p:off") }
        check(TaskRuntime.beginProbe()); settle()
        rule.onNodeWithText("p:off").assertExists()
        TaskRuntime.finishProbe(); settle()
        rule.onNodeWithText("p:on").assertExists()
    }
}
