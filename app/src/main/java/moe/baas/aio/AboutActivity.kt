package moe.baas.aio

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import moe.baas.aio.UiTheme.item

class AboutActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = UiTheme.page(this)
        root.item(UiTheme.row(this, Gravity.CENTER_VERTICAL).apply {
            item(UiTheme.text(this@AboutActivity, "关于", 20f), 1f)
            addView(UiTheme.flatButton(this@AboutActivity, "返回") { finish() })
        })
        val content = UiTheme.column(this)
        content.item(UiTheme.card(this).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, UiTheme.dp(context, 24), 0, UiTheme.dp(context, 20))
            addView(ImageView(context).apply {
                setImageResource(R.mipmap.ic_launcher)
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(UiTheme.dp(context, 64), UiTheme.dp(context, 64)))
            addView(UiTheme.text(context, "BAAS AIO", 22f, UiTheme.blue).apply {
                setTypeface(null, Typeface.BOLD)
            })
            addView(UiTheme.text(context, "${BuildConfig.VERSION_NAME} · ${if (BuildConfig.DEBUG) "Debug" else "正式版"}", 12f, UiTheme.muted))
            addView(UiTheme.text(context, "BAAS 安卓一体版", 13f, UiTheme.muted))
        })
        content.item(UiTheme.card(this).apply {
            addView(UiTheme.text(context, "项目与组件", 13f).apply { setTypeface(null, Typeface.BOLD) })
            project("BAAS", "Python 工作流与识图资源", "https://github.com/pur1fying/blue_archive_auto_script")
            project("Shizuku", "设备控制权限", "https://github.com/RikkaApps/Shizuku")
            project("Chaquopy", "内嵌 Python 运行环境", "https://github.com/chaquo/chaquopy")
        })
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        UiTheme.setContentView(this, root)
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
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: ActivityNotFoundException) {
                    UiTheme.dialog(context).setMessage("未找到可打开链接的应用")
                        .setPositiveButton("确定", null).show()
                }
            }
        }, LinearLayout.LayoutParams(-1, -2))
    }
}
