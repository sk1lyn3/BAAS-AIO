package moe.baas.aio

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/** Regression: landscape Home is preview | content side by side (509771e: two weight-1 panes), any theme mode. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34])
class LandscapeLayoutTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private fun theme(mode: Int) = ctx.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putInt("mode", mode).commit()

    private fun split(mode: Int) {
        theme(mode)
        val a = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        rule.waitForIdle()
        assertEquals(Configuration.ORIENTATION_LANDSCAPE, a.resources.configuration.orientation)
        val root = rule.onNodeWithTag("home-landscape").getUnclippedBoundsInRoot()
        val pane = rule.onNodeWithTag("preview-pane").getUnclippedBoundsInRoot()
        val ratio = (pane.right - pane.left) / (root.right - root.left)
        assertTrue("preview pane ratio $ratio", ratio in 0.40f..0.55f)
    }

    @Config(qualifiers = "w873dp-h393dp-land-night-xxhdpi") @Test fun dark873() = split(2)
    @Config(qualifiers = "w915dp-h412dp-land-night-xxhdpi") @Test fun dark915() = split(2)
    @Config(qualifiers = "w800dp-h360dp-land-xxhdpi") @Test fun light800() = split(1)
    @Config(qualifiers = "w851dp-h393dp-land-xxhdpi") @Test fun system851() = split(0)

    /** Root cause guard: the manual-theme override must only carry the night bit, so rotation keeps updating. */
    @Test fun themeOverrideIsSparse() {
        theme(2)
        var captured: Configuration? = null
        val probe = object : android.view.ContextThemeWrapper(ctx, 0) {
            override fun applyOverrideConfiguration(c: Configuration) { captured = c }
        }
        Appearance.apply(probe, ctx)
        val c = checkNotNull(captured)
        assertEquals(Configuration.UI_MODE_NIGHT_YES, c.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        assertEquals(Configuration.ORIENTATION_UNDEFINED, c.orientation)
        assertEquals(Configuration.SCREEN_WIDTH_DP_UNDEFINED, c.screenWidthDp)
    }
}
