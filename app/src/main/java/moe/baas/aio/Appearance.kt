package moe.baas.aio

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.ui.graphics.toArgb

/**
 * Manual theme: 0 = follow system, 1 = light, 2 = dark (SharedPreferences "appearance"/"mode").
 *
 * Startup: every activity wraps its base context with [wrap], forcing the uiMode night bit, so the
 * window background (values-night), platform dialogs and the very first frame already use the chosen
 * theme — no wrong-theme flash. Live switch (from 设置): [switch] snapshots the window, flips the
 * observable [Ui.night] (all Compose colors recompose in place, no recreate), restyles the bars and
 * backdrop, then fades the snapshot out for a 280ms cross-fade.
 */
object Appearance {
    private const val PREFS = "appearance"
    fun mode(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("mode", 0)

    private fun systemNight(context: Context) =
        (context.applicationContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    fun isNight(context: Context, mode: Int = mode(context)) = when (mode) { 1 -> false; 2 -> true; else -> systemNight(context) }

    /** Use from attachBaseContext. Follow-system leaves the configuration untouched. */
    fun wrap(base: Context): Context {
        val mode = mode(base)
        if (mode == 0) return base
        val config = Configuration(base.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            (if (mode == 2) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        return base.createConfigurationContext(config)
    }

    /** Persist and apply instantly with a cross-fade. */
    fun switch(activity: Activity, mode: Int) {
        if (mode == mode(activity)) return
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("mode", mode).apply()
        val night = isNight(activity, mode)
        if (night == Ui.night) return
        val decor = activity.window.decorView as ViewGroup
        val shot = runCatching {
            Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also { decor.draw(Canvas(it)) }
        }.getOrNull()
        val cover = shot?.let { bitmap ->
            ImageView(activity).apply { setImageBitmap(bitmap); scaleType = ImageView.ScaleType.FIT_XY }
                .also { decor.addView(it, ViewGroup.LayoutParams(-1, -1)) }
        }
        Ui.night = night
        UiTheme.styleWindow(activity)
        cover?.animate()?.alpha(0f)?.setDuration(280)?.withEndAction {
            decor.removeView(cover); shot.recycle()
        }?.start()
    }
}
