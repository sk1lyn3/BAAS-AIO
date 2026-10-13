package moe.baas.aio

import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** PreviewIdleActivity (virtual display content right after 启动游戏, before the game draws): original theme vs v3 theme. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w640dp-h360dp-land-xhdpi")
class IdleShotTest {
    private val out = (System.getProperty("shot.dir") ?: "/tmp/shots") + "/idle"
    private fun shot(name: String, night: Boolean, originalTheme: Boolean) {
        RuntimeEnvironment.setQualifiers(if (night) "+night" else "+notnight")
        val c = Robolectric.buildActivity(PreviewIdleActivity::class.java)
        if (originalTheme) c.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)  // 509771e: Theme.Baas = Material.Light, no night variant
        c.setup()
        c.get().window.decorView.captureRoboImage("$out/$name.png")
    }
    @Test fun originalLight() = shot("original-light", false, true)
    @Test fun originalDark() = shot("original-dark", true, true)
    @Test fun v3Light() = shot("v3-light", false, false)
    @Test fun v3Dark() = shot("v3-dark", true, false)
}
