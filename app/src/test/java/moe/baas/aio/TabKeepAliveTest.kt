package moe.baas.aio

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h851dp-xxhdpi")
class TabKeepAliveTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @Before fun clock() { rule.mainClock.autoAdvance = false }

    private fun settle() {
        repeat(12) {
            rule.mainClock.advanceTimeByFrame()
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS)
        }
    }

    @Test fun visitedTabSurvivesRoundTripWithoutBlank() {
        ProfileStore(ctx).initialize()
        TaskRuntime.publish("idle", "test")
        val a = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        settle()
        rule.onNodeWithText("开始", substring = false).assertIsDisplayed()

        val switch = HomeActivity::class.java.declaredMethods.first { it.name == "switchPage" }.apply { isAccessible = true }
        switch.invoke(a, "托管"); settle()
        assertTrue(rule.onAllNodesWithText("开启托管").fetchSemanticsNodes().isNotEmpty())

        switch.invoke(a, "主页"); settle()
        rule.onNodeWithText("开始", substring = false).assertIsDisplayed()
        assertTrue(
            rule.onAllNodesWithText("待机", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithText("个任务已启用", substring = true).fetchSemanticsNodes().isNotEmpty()
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TabPulsePauseTest {
    @get:Rule val rule = createComposeRule()

    @Before fun clock() { rule.mainClock.autoAdvance = false }

    @Test fun offscreenPulseDoesNotKeepRecomposing() {
        var compositions by mutableIntStateOf(0)
        rule.setContent {
            compositions++
            CompositionLocalProvider(LocalTabSelected provides false) {
                StatusChip("x", Color.White, pulse = true)
            }
        }
        repeat(8) { rule.mainClock.advanceTimeByFrame() }
        val afterLayout = compositions
        repeat(40) { rule.mainClock.advanceTimeByFrame() }
        assertEquals("offscreen StatusChip pulse must not run infinite transition", afterLayout, compositions)
    }
}
