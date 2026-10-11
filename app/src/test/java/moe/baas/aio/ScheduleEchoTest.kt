package moe.baas.aio

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Regression: schedule edit buttons that change the draft JSON (立即, reset times, …) must echo immediately. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduleEchoTest {
    @get:Rule val rule = createEmptyComposeRule()

    private fun open(): ActivityScenario<ScheduleActivity> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = ProfileStore(ctx); val sel = store.selected()
        val events = store.events(sel)
        // Pick a task whose next run time is "立即" (tick <= 0) so the label must change.
        val task = (0 until events.length()).map { events.getJSONObject(it) }.first { it.optDouble("next_tick", 0.0) <= 0 }.getString("func_name")
        return ActivityScenario.launch(Intent(ctx, ScheduleActivity::class.java).putExtra("profileId", sel.id).putExtra("task", task))
    }

    @Test fun nowButtonEchoesNextRunTime() {
        open().use {
            rule.onAllNodesWithText("立即").assertCountEquals(2)
            rule.onAllNodesWithText("立即")[1].performClick()
            rule.waitForIdle()
            val now = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("GMT+08:00") }.format(Date())
            rule.onNodeWithText(now).assertExists()
            rule.onAllNodesWithText("立即").assertCountEquals(1)
        }
    }

    /** Laid-out nodes only (a node being disposed can linger with zero bounds for a frame). */
    private fun shown(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().count { it.boundsInRoot.width > 0f }

    @Test fun deleteResetTimeEchoes() {
        open().use {
            val before = shown("删除")
            org.junit.Assume.assumeTrue(before > 0)
            rule.onAllNodesWithText("删除")[0].performClick()
            rule.waitForIdle()
            org.junit.Assert.assertEquals(before - 1, shown("删除"))
        }
    }
}
