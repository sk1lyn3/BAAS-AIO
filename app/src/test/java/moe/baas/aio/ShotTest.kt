package moe.baas.aio

import android.app.Activity
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h851dp-xxhdpi")
class ShotTest {
    private val out = System.getProperty("shot.dir") ?: "/tmp/shots"
    private fun idle() { repeat(5) { ShadowLooper.idleMainLooper(); Thread.sleep(50) } }
    private fun snap(a: Activity, name: String) { idle(); a.window.decorView.captureRoboImage("$out/$name.png") }

    private fun tabs(prefix: String) {
        val a = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        snap(a, "${prefix}home-主页")
        val r = HomeActivity::class.java.declaredMethods.first { it.name == "renderPage" }.apply { isAccessible = true }
        for (p in listOf("托管", "调度", "日志", "设置")) {
            runCatching {
                // v2 stores page in a mutableState delegate; fall back to setter
                val setter = HomeActivity::class.java.declaredMethods.firstOrNull { it.name.startsWith("setPage") }
                if (setter != null) { setter.isAccessible = true; setter.invoke(a, p) } else HomeActivity::class.java.getDeclaredField("page").apply { isAccessible = true }.set(a, p)
                val args = arrayOfNulls<Any>(r.parameterCount)
                r.parameterTypes.forEachIndexed { i, t -> args[i] = when (t) { Boolean::class.javaPrimitiveType -> false; Int::class.javaPrimitiveType -> 0; else -> null } }
                r.invoke(a, *args)
                snap(a, "${prefix}home-$p")
            }.onFailure { println("tab $p failed: $it") }
        }
    }
    @Test fun portrait() = tabs("")
    @Config(qualifiers = "w851dp-h393dp-land-xxhdpi") @Test fun landscape() = tabs("land-")
    @Test fun profile() = snap(Robolectric.buildActivity(ProfileActivity::class.java).setup().get(), "profile")
    @Test fun log() = snap(Robolectric.buildActivity(LogActivity::class.java).setup().get(), "log")
}
