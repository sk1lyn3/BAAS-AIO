package moe.baas.aio

import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import moe.baas.aio.UiTheme.item

object AboutDialog {
    fun show(context: Context) {
        val content = UiTheme.column(context).apply {
            val padding = UiTheme.dp(context, 12)
            setPadding(padding, 0, padding, 0)
        }
        content.item(UiTheme.card(context).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, UiTheme.dp(context, 16), 0, UiTheme.dp(context, 12))
            addView(ImageView(context).apply {
                setImageResource(R.mipmap.ic_launcher)
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(UiTheme.dp(context, 52), UiTheme.dp(context, 52)))
            addView(UiTheme.text(context, "BAAS AIO", 22f, UiTheme.blue).apply {
                setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER
            })
            addView(UiTheme.text(context, BuildConfig.VERSION_NAME + if (BuildConfig.DEBUG) " · Debug" else "", 12f, UiTheme.muted).apply { gravity = Gravity.CENTER })
        })
        content.item(UiTheme.card(context).apply {
            addView(UiTheme.text(context, context.getString(R.string.about_related_projects), 13f).apply { setTypeface(null, Typeface.BOLD) })
            project("BAAS AIO", context.getString(R.string.about_repository), "https://github.com/sk1lyn3/BAAS-AIO")
            project("BAAS", context.getString(R.string.about_baas_description), "https://github.com/pur1fying/blue_archive_auto_script")
            project("Shizuku", context.getString(R.string.about_shizuku_description), "https://github.com/RikkaApps/Shizuku")
            project("Chaquopy", context.getString(R.string.about_chaquopy_description), "https://github.com/chaquo/chaquopy")
            project("MAA-Meow", context.getString(R.string.about_meow_description), "https://github.com/Aliothmoon/MAA-Meow")
        })
        UiTheme.dialog(context)
            .setView(ScrollView(context).apply { addView(content) })
            .setPositiveButton(context.getString(R.string.action_close), null)
            .show()
    }

    private fun LinearLayout.project(name: String, description: String, url: String) {
        addView(UiTheme.row(context, Gravity.CENTER_VERTICAL).apply {
            background = UiTheme.quietRipple(context)
            isClickable = true; isFocusable = true
            addView(UiTheme.column(context).apply {
                addView(UiTheme.text(context, name, 14f, UiTheme.blue))
                addView(UiTheme.text(context, description, 12f, UiTheme.muted).apply { setPadding(UiTheme.dp(context, 4), 0, 0, UiTheme.dp(context, 10)) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(UiTheme.chevron(context), LinearLayout.LayoutParams(UiTheme.dp(context, 24), UiTheme.dp(context, 48)))
            setOnClickListener {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: ActivityNotFoundException) {
                    UiTheme.dialog(context).setMessage(context.getString(R.string.error_no_link_handler))
                        .setPositiveButton(context.getString(R.string.action_ok), null).show()
                }
            }
        }, LinearLayout.LayoutParams(-1, -2))
    }
}
