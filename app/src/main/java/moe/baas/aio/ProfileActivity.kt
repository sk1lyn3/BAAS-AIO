package moe.baas.aio

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.graphics.Typeface
import android.view.View
import moe.baas.aio.UiTheme.item

/** Small configuration host; imported BAAS settings stay in their original files. */
class ProfileActivity : Activity() {
    private lateinit var store: ProfileStore
    private lateinit var content: LinearLayout
    private var exporting: String? = null
    private var importingId: String? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); store = ProfileStore(this); store.initialize()
        exporting = state?.getString("exporting")
        importingId = state?.getString("importingId")
        content = UiTheme.page(this)
        UiTheme.setContentView(this, ScrollView(this).apply { addView(content) })
    }
    override fun onResume() { super.onResume(); render() }
    override fun onSaveInstanceState(state: Bundle) { super.onSaveInstanceState(state); state.putString("exporting", exporting); state.putString("importingId", importingId) }
    private fun button(text: String, action: () -> Unit) = UiTheme.button(this, text) { safely(action) }
    private fun row() = UiTheme.row(this)
    private fun render() {
        content.removeAllViews(); val selected = store.selected()
        content.item(row().apply {
            item(UiTheme.text(this@ProfileActivity, "配置文件", 20f).apply { setTypeface(null, Typeface.BOLD) }, 1f)
            item(button("返回") { finish() }, 1f)
        })
        for (profile in store.list()) {
            val chosen = profile.id == selected.id
            content.item(UiTheme.button(this, "${if (chosen) "✓ " else ""}${profile.name} · ${profile.server}", chosen) {
                safely { store.select(profile.id); render() }
            })
        }
        content.item(button("新建配置") {
            check(!TaskRuntime.isActive) { "请先停止任务" }
            val name = UiTheme.input(this).apply { hint = "配置名称"; setSingleLine(true) }
            val form = UiTheme.column(this).apply { item(name) }
            val dialog = AlertDialog.Builder(this).setTitle("新建配置").setView(form).setNegativeButton("取消", null).setPositiveButton("创建", null).create()
            dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                safely { store.select(store.newProfile(name.text.toString()).id); render(); dialog.dismiss() }
            } }; dialog.show()
        }.apply { isEnabled = !TaskRuntime.isActive })
        val actions = UiTheme.card(this)
        actions.item(UiTheme.text(this, selected.name, 16f).apply { setTypeface(null, Typeface.BOLD) })
        val settings = button("设置") {
            startActivity(Intent(this, ConfigActivity::class.java).putExtra("profileId", selected.id))
        }
        val rename = button("重命名") {
            val input = EditText(this).apply { setText(selected.name); setSingleLine(true) }
            AlertDialog.Builder(this).setTitle("配置名称").setView(input).setNegativeButton("取消", null)
                .setPositiveButton("保存") { _, _ -> safely { store.rename(selected.id, input.text.toString()); render() } }.show()
        }
        val copy = button("复制") { store.select(store.duplicate(selected.id).id); render() }
        val export = button("导出") {
            check(!TaskRuntime.isActive) { "请先停止任务" }; exporting = selected.id
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "${selected.name}.zip"), 202)
        }
        val delete = button("删除") {
            check(!TaskRuntime.isActive) { "请先停止任务" }
            AlertDialog.Builder(this).setMessage("删除配置 ${selected.name}？").setNegativeButton("取消", null)
                .setPositiveButton("删除") { _, _ -> safely { store.delete(selected.id); render() } }.show()
        }
        actions.item(row().apply { item(settings, 1f); item(rename, 1f); item(copy, 1f) })
        actions.item(row().apply { item(export, 1f); item(delete, 1f) })
        val importing = button("导入 ZIP") {
            check(!TaskRuntime.isActive) { "请先停止任务" }
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_MIME_TYPES,
                    arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")), 201)
        }
        actions.item(importing)
        val json = button("导入 config / event JSON") {
            check(!TaskRuntime.isActive) { "请先停止任务" }; importingId = selected.id
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 203)
        }; actions.item(json)
        listOf(rename, copy, export, delete, importing, json).forEach { it.isEnabled = !TaskRuntime.isActive; it.alpha = if (it.isEnabled) 1f else .45f }
        content.item(actions)
    }
    @Deprecated("Platform activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        safely {
            when (requestCode) {
                201 -> contentResolver.openInputStream(uri).use { input ->
                    val profiles = store.importZip(requireNotNull(input)); store.select(profiles.first().id)
                }
                202 -> contentResolver.openOutputStream(uri, "wt").use { output ->
                    store.exportZip(requireNotNull(exporting), requireNotNull(output)); exporting = null
                }
                203 -> contentResolver.openInputStream(uri).use { input -> store.importJson(requireNotNull(importingId), requireNotNull(input)); importingId = null }
            }
            render()
        }
    }
    private fun safely(action: () -> Unit) {
        try { action() } catch (error: Exception) {
            AlertDialog.Builder(this).setMessage(error.message ?: "配置操作失败").setPositiveButton("确定", null).show()
        }
    }
}
