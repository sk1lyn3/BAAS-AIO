package moe.baas.aio

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import moe.baas.aio.UiTheme.item

/** Standalone task settings use the same draft and controls as the inline settings page. */
class ConfigActivity : Activity() {
    private val store by lazy { ProfileStore(this) }
    private lateinit var form: ConfigForm
    private lateinit var fields: LinearLayout
    private lateinit var save: Button
    private lateinit var groupButton: Button
    private var group = 0
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun button(label: String, primary: Boolean = false, action: () -> Unit) = UiTheme.button(this, label, primary) { safely(action) }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        try {
            val profileId = state?.getString("profileId") ?: intent.getStringExtra("profileId") ?: store.selected().id
            form = ConfigForm(this, profileId, state) { if (::save.isInitialized) save.isEnabled = !TaskRuntime.isActive && form.dirty && form.valid }
            group = state?.getInt("group") ?: intent.getIntExtra("group", 0)
            require(group in 0 until form.catalog.groups.length())
        } catch (error: Exception) {
            AlertDialog.Builder(this).setMessage(error.message).setPositiveButton("返回") { _, _ -> finish() }.show(); return
        }
        val root = column().apply { setBackgroundColor(UiTheme.pale); val p = UiTheme.dp(this@ConfigActivity, 12); setPadding(p,p,p,p) }
        root.item(row().apply {
            item(UiTheme.text(this@ConfigActivity, "任务参数", 20f).apply { setTypeface(null, Typeface.BOLD) }, 1f)
            item(button("返回") { leave() }, 1f)
        })
        root.item(UiTheme.text(this, store.get(form.profileId).name, 12f, UiTheme.muted))
        groupButton = button(form.catalog.groups.getJSONObject(group).getString("title")) {
            AlertDialog.Builder(this).setTitle("任务参数")
                .setItems((0 until form.catalog.groups.length()).map { form.catalog.groups.getJSONObject(it).getString("title") }.toTypedArray()) { _, i -> group = i; render() }.show()
        }; root.item(groupButton)
        fields = column()
        root.addView(ScrollView(this).apply { addView(fields) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.item(row().apply {
            item(button("取消") { leave() }, 1f)
            item(button("撤销修改") { form.reset() }, 1f)
            save = button("保存", true) { form.save(); Toast.makeText(this@ConfigActivity, "已保存", Toast.LENGTH_SHORT).show(); finish() }; item(save, 1f)
        })
        setContentView(root); render()
    }
    private fun render() {
        fields.removeAllViews()
        val view = form.view(group)
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        fields.addView(view)
        groupButton.text = form.catalog.groups.getJSONObject(group).getString("title")
        save.isEnabled = !TaskRuntime.isActive && form.dirty && form.valid
    }
    private fun leave() {
        if (!::form.isInitialized || !form.dirty) finish()
        else AlertDialog.Builder(this).setMessage("放弃未保存的修改？").setNegativeButton("继续编辑", null).setPositiveButton("放弃") { _, _ -> finish() }.show()
    }
    @Deprecated("Platform back API") override fun onBackPressed() = leave()
    override fun onSaveInstanceState(state: Bundle) {
        if (::form.isInitialized) { state.putAll(form.saveState()); state.putInt("group", group) }
        super.onSaveInstanceState(state)
    }
    private fun safely(action: () -> Unit) {
        try { action() } catch (error: Exception) { AlertDialog.Builder(this).setMessage(error.message ?: "配置操作失败").setPositiveButton("确定", null).show() }
    }
}
