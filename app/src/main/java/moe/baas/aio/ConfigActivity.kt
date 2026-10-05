package moe.baas.aio

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import moe.baas.aio.UiTheme.item

/** Configuration management reuses the same collapsible sections as Home settings. */
class ConfigActivity : Activity() {
    private lateinit var panel: SettingsPanel
    private lateinit var profileId: String
    private val changed: (String) -> Unit = { runOnUiThread { if (::panel.isInitialized) panel.refreshEnabled() } }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        try {
            val store = ProfileStore(this)
            profileId = state?.getString("profileId") ?: intent.getStringExtra("profileId") ?: store.selected().id
            val initial = state ?: Bundle().apply {
                if (intent.hasExtra("group")) putIntegerArrayList("expanded", arrayListOf(intent.getIntExtra("group", 0)))
            }
            panel = SettingsPanel(this, profileId, initial)
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setBackgroundColor(UiTheme.pale)
                val p = UiTheme.dp(this@ConfigActivity, 12); setPadding(p, p, p, p)
            }
            root.item(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(UiTheme.text(this@ConfigActivity, "配置设置", 20f).apply { setTypeface(null, Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(UiTheme.flatButton(this@ConfigActivity, "返回") { leave() })
            })
            root.item(UiTheme.text(this, store.get(profileId).name, 12f, UiTheme.muted))
            val scroll = ScrollView(this).apply { addView(panel) }
            root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            UiTheme.setContentView(this, root)
            if (state == null && intent.hasExtra("group")) scroll.post {
                panel.findViewWithTag<android.view.View>(intent.getIntExtra("group", 0))?.let { scroll.scrollTo(0, it.top) }
            }
            TaskRuntime.observe(changed)
        } catch (error: Exception) {
            AlertDialog.Builder(this).setMessage(error.message).setPositiveButton("返回") { _, _ -> finish() }.show()
        }
    }
    private fun leave() {
        if (!::panel.isInitialized || !panel.dirty) finish()
        else AlertDialog.Builder(this).setMessage("放弃未保存的修改？").setNegativeButton("继续编辑", null).setPositiveButton("放弃") { _, _ -> finish() }.show()
    }
    override fun onResume() {
        super.onResume()
        if (::panel.isInitialized) { panel.reloadIfClean(); panel.refreshEnabled() }
    }
    @Deprecated("Platform back API") override fun onBackPressed() = leave()
    override fun onSaveInstanceState(state: Bundle) {
        if (::panel.isInitialized) { state.putAll(panel.saveState()); state.putString("profileId", profileId) }
        super.onSaveInstanceState(state)
    }
    override fun onDestroy() { TaskRuntime.remove(changed); super.onDestroy() }
}
