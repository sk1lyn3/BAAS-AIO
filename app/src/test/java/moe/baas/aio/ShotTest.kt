package moe.baas.aio

import android.app.Activity
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h851dp-xxhdpi")
class ShotTest {
    private val out = System.getProperty("shot.dir") ?: "/tmp/shots"
    // Compose's GlobalSnapshotManager binds to the first test's looper; push state writes manually.
    private fun idle() { repeat(8) { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications(); ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS) } }
    private fun snap(a: Activity, name: String) { idle(); a.window.decorView.captureRoboImage("$out/$name.png") }
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun tabs(prefix: String) {
        val a = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        snap(a, "${prefix}home-主页")
        val switch = HomeActivity::class.java.declaredMethods.first { it.name == "switchPage" }.apply { isAccessible = true }
        val getter = HomeActivity::class.java.declaredMethods.first { it.name.startsWith("getPage") }.apply { isAccessible = true }
        for (p in listOf("托管", "调度", "日志", "设置")) { switch.invoke(a, p); println("$prefix switch $p -> ${getter.invoke(a)} orient=${a.resources.configuration.orientation}"); snap(a, "${prefix}home-$p") }
    }
    private fun pages(prefix: String) {
        snap(Robolectric.buildActivity(ProfileActivity::class.java).setup().get(), "${prefix}profile")
        snap(Robolectric.buildActivity(LogActivity::class.java).setup().get(), "${prefix}log")
        snap(Robolectric.buildActivity(ConfigActivity::class.java, Intent(ctx, ConfigActivity::class.java).putExtra("group", 0)).setup().get(), "${prefix}config")
        val store = ProfileStore(ctx); val sel = store.selected()
        val task = store.events(sel).getJSONObject(1).getString("func_name")
        snap(Robolectric.buildActivity(ScheduleActivity::class.java, Intent(ctx, ScheduleActivity::class.java)
            .putExtra("profileId", sel.id).putExtra("task", task)).setup().get(), "${prefix}schedule-edit")
    }
    @Test fun portrait() = tabs("")
    @Config(qualifiers = "+night") @Test fun portraitNight() = tabs("dark-")
    @Config(qualifiers = "w851dp-h393dp-land-xxhdpi") @Test fun landscape() = tabs("land-")
    @Config(qualifiers = "w851dp-h393dp-land-night-xxhdpi") @Test fun landscapeNight() = tabs("land-dark-")
    @Test fun secondary() = pages("")
    @Config(qualifiers = "+night") @Test fun secondaryNight() = pages("dark-")

    /** Frame sequence of a tab switch, for the demo GIF. */
    @Test fun tabMotion() {
        val a = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        idle()
        val switch = HomeActivity::class.java.declaredMethods.first { it.name == "switchPage" }.apply { isAccessible = true }
        var f = 0
        for (p in listOf("托管", "调度", "设置", "主页")) {
            switch.invoke(a, p)
            repeat(14) { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications(); ShadowLooper.idleMainLooper(24, TimeUnit.MILLISECONDS); a.window.decorView.captureRoboImage("$out/motion/tab-%03d.png".format(f++)) }
            repeat(6) { ShadowLooper.idleMainLooper(60, TimeUnit.MILLISECONDS) }
            repeat(4) { a.window.decorView.captureRoboImage("$out/motion/tab-%03d.png".format(f++)) }
        }
    }
}
