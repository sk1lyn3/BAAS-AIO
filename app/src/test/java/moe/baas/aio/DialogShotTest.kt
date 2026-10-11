package moe.baas.aio

import android.app.AlertDialog
import android.app.TimePickerDialog
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

/** Native dialogs before (platform look) / after (v3 styleDialog), light + dark. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h851dp-xxhdpi")
class DialogShotTest {
    private val out = (System.getProperty("shot.dir") ?: "/tmp/shots") + "/dialogs"
    private fun run(tag: String, v3: Boolean) {
        UiTheme.v3Dialogs = v3
        val a = Robolectric.buildActivity(ProfileActivity::class.java).setup().get()
        val page = PaneActivity::class.java.getDeclaredField("page").apply { isAccessible = true }.get(a) as ProfilePage
        fun cap(name: String) {
            ShadowLooper.idleMainLooper()
            val d = ShadowDialog.getLatestDialog() as AlertDialog
            val v = d.window!!.decorView
            val bmp = android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(bmp).apply { drawColor(if (Ui.night) 0xFF0A1424.toInt() else 0xFFE3F3FE.toInt()) }.let { v.draw(it) }
            java.io.File(out).mkdirs(); java.io.FileOutputStream("$out/$name-$tag.png").use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; d.dismiss(); ShadowLooper.idleMainLooper()
        }
        ProfilePage::class.java.getDeclaredMethod("newProfile").apply { isAccessible = true }.invoke(page); cap("new")
        val sel = ProfileStore(a).selected()
        ProfilePage::class.java.getDeclaredMethod("rename", ProfileStore.Profile::class.java).apply { isAccessible = true }.invoke(page, sel); cap("rename")
        UiTheme.dialog(page).setMessage("删除配置 ${sel.name}？").setNegativeButton("取消", null).setPositiveButton("删除", null).show(); cap("delete")
        TimePickerDialog(a, null, 4, 0, true).also { UiTheme.styleDialog(it) }.show(); cap("time")
        UiTheme.v3Dialogs = true
    }
    @Test fun before() = run("before-light", false)
    @Test fun after() = run("after-light", true)
    @Config(qualifiers = "+night") @Test fun beforeDark() { Ui.night = true; run("before-dark", false) }
    @Config(qualifiers = "+night") @Test fun afterDark() { Ui.night = true; run("after-dark", true) }
}
