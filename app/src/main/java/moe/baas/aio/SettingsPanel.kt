package moe.baas.aio

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import moe.baas.aio.UiTheme.item

/** WebUI-style collapsible sections sharing one unsaved configuration draft. */
class SettingsPanel(context: Context, profileId: String, state: Bundle? = null) : LinearLayout(context) {
    private lateinit var save: Button
    private lateinit var reset: Button
    private val form = ConfigForm(context, profileId, state?.takeIf { it.containsKey("baseline") }) { updateActions() }
    private val expanded = state?.getIntegerArrayList("expanded")?.toMutableSet() ?: mutableSetOf()
    private val manualPanels = mutableMapOf<String, ManualTaskPanel>()
    val dirty get() = form.dirty
    init {
        orientation = VERTICAL
        item(UiTheme.row(context, Gravity.CENTER_VERTICAL).apply {
            reset = UiTheme.flatButton(context, "撤销修改") { form.reset() }
            addView(reset, LayoutParams(0, UiTheme.dp(context, 32), 1f).apply { rightMargin = UiTheme.dp(context, 6) })
            save = UiTheme.flatButton(context, "保存") {
                try {
                    form.save(); manualPanels.values.forEach { it.refreshProfile() }; Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                } catch (error: Exception) {
                    UiTheme.dialog(context).setMessage(error.message ?: "保存失败").setPositiveButton("确定", null).show()
                }
            }
            save.setTextColor(Color.WHITE)
            save.background = UiTheme.background(context, UiTheme.blue, 8)
            addView(save, LayoutParams(0, UiTheme.dp(context, 32), 1f))
        })
        fun manual(title: String) = manualPanels.getOrPut(title) {
            ManualTaskPanel(context as Activity, profileId, title, state?.getBundle(title)) { form.dirty }
        }
        for (group in 0 until form.catalog.groups.length()) {
            val title = form.catalog.groups.getJSONObject(group).getString("title")
            section(title, group) {
                val extras = if (title == "关卡推图与剧情") mapOf(
                    "manual_boss" to manual("推图"),
                    "main_story_regions" to manual("剧情")
                ) else emptyMap()
                form.view(group, extras)
            }
            if (title == "关卡推图与剧情") section("活动图", form.catalog.groups.length()) { manual("活动图") }
        }
        section("其他功能", form.catalog.groups.length() + 1) { manual("其他功能") }
        updateActions()
    }
    private fun section(title: String, id: Int, createContent: () -> View) {
        item(UiTheme.card(context).apply {
            tag = id
            setPadding(UiTheme.dp(context, 12), 0, UiTheme.dp(context, 8), 0)
            val content = UiTheme.column(context).apply {
                setPadding(0, UiTheme.dp(context, 4), UiTheme.dp(context, 4), UiTheme.dp(context, 8))
            }
            val arrow = UiTheme.chevron(context)
            val header = UiTheme.row(context, Gravity.CENTER_VERTICAL).apply {
                isClickable = true; isFocusable = true
                background = UiTheme.quietRipple(context, Color.WHITE, 10)
                addView(UiTheme.text(context, title, 13f).apply {
                    setTypeface(null, Typeface.BOLD); setPadding(0, 0, 0, 0)
                }, LayoutParams(0, -2, 1f))
                addView(arrow, LayoutParams(UiTheme.dp(context, 24), -1))
            }
            fun show(open: Boolean, animate: Boolean = false) {
                if (open && content.childCount == 0) content.addView(createContent())
                content.visibility = if (open) View.VISIBLE else View.GONE
                val angle = if (open) 90f else 0f
                if (animate) arrow.animate().rotation(angle).setDuration(140).start() else arrow.rotation = angle
                header.contentDescription = "$title，${if (open) "已展开" else "已收起"}"
            }
            header.setOnClickListener {
                if (!expanded.add(id)) expanded.remove(id)
                show(id in expanded, animate = true)
            }
            addView(header, LayoutParams(-1, UiTheme.dp(context, 44)))
            addView(content, LayoutParams(-1, -2))
            show(id in expanded)
        })
    }
    private fun updateActions() {
        if (!::save.isInitialized) return
        save.isEnabled = !TaskRuntime.isActive && form.dirty && form.valid
        save.alpha = if (save.isEnabled) 1f else .45f
        reset.isEnabled = form.dirty
        reset.alpha = if (reset.isEnabled) 1f else .45f
    }
    fun refreshEnabled() { form.refreshEnabled(); updateActions() }
    fun reloadIfClean() { form.reloadIfClean(); manualPanels.values.forEach { it.refreshProfile() } }
    fun saveState() = form.saveState().apply { putIntegerArrayList("expanded", ArrayList(expanded)); manualPanels.forEach { (title, panel) -> putBundle(title, panel.saveState()) } }
}
