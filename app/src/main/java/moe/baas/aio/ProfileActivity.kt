package moe.baas.aio

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import moe.baas.aio.UiTheme.item

/** Small configuration host; imported BAAS settings stay in their original files. */
class ProfileActivity : PaneActivity() {
    override fun createPage(intent: Intent, close: () -> Unit) = ProfilePage(this, intent, close)
}

class ProfilePage(activity: Activity, intent: Intent, close: () -> Unit) : PanePage(activity, intent, close) {
    private lateinit var store: ProfileStore
    private var exporting: String? = null
    private var importingId: String? = null
    private var version by mutableIntStateOf(0)
    override val pageRoot = false
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); store = ProfileStore(this); store.initialize()
        exporting = state?.getString("exporting")
        importingId = state?.getString("importingId")
        show()
    }
    override fun onResume() { super.onResume(); render() }
    override fun onSaveInstanceState(state: Bundle) { super.onSaveInstanceState(state); state.putString("exporting", exporting); state.putString("importingId", importingId) }
    private fun row() = UiTheme.row(this)
    private fun render() { version++ }

    @Composable
    override fun Content() {
        val v = version
        val selected = remember(v) { store.selected() }
        val profiles = remember(v) { store.list() }
        val active = LiveTask.snapshot.let { TaskRuntime.isActive }
        val sc = rememberScrollState()
        Column(Modifier.fadingScrollbar(sc).verticalScroll(sc)) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Row(Modifier.fillMaxWidth().item(), verticalAlignment = Alignment.Top) {
                    BText("配置文件", 22f, weight = FontWeight.ExtraBold, modifier = Modifier.weight(1f).item())
                    BButton("返回", Modifier.weight(1f).item()) { safely { finish() } }
                }
                for (profile in profiles) {
                    val chosen = profile.id == selected.id
                    BButton("${if (chosen) "✓ " else ""}${profile.name} · ${profile.server}", Modifier.fillMaxWidth().item(), primary = chosen) {
                        safely { store.select(profile.id); render() }
                    }
                }
                BButton("新建配置", Modifier.fillMaxWidth().item(), enabled = !active) { safely { newProfile() } }
                BCard(Modifier.item()) {
                    BText(selected.name, 16f, weight = FontWeight.Bold, modifier = Modifier.item())
                    val on = !active
                    Row(Modifier.fillMaxWidth().item()) {
                        BButton("设置", Modifier.weight(1f).item()) {
                            safely { startActivity(Intent(this@ProfilePage, ConfigActivity::class.java).putExtra("profileId", selected.id)) }
                        }
                        BButton("重命名", Modifier.weight(1f).item(), enabled = on, disabledAlpha = .45f) { safely { rename(selected) } }
                        BButton("复制", Modifier.weight(1f).item(), enabled = on, disabledAlpha = .45f) { safely { store.select(store.duplicate(selected.id).id); render() } }
                    }
                    Row(Modifier.fillMaxWidth().item()) {
                        BButton("导出", Modifier.weight(1f).item(), enabled = on, disabledAlpha = .45f) {
                            safely {
                                check(!TaskRuntime.isActive) { "请先停止任务" }; exporting = selected.id
                                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip")
                                    .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "${selected.name}.zip"), 202)
                            }
                        }
                        BButton("删除", Modifier.weight(1f).item(), enabled = on, disabledAlpha = .45f) {
                            safely {
                                check(!TaskRuntime.isActive) { "请先停止任务" }
                                UiTheme.dialog(this@ProfilePage).setMessage("删除配置 ${selected.name}？").setNegativeButton("取消", null)
                                    .setPositiveButton("删除") { _, _ -> safely { store.delete(selected.id); render() } }.show()
                            }
                        }
                    }
                    BButton("导入 ZIP", Modifier.fillMaxWidth().item(), enabled = on, disabledAlpha = .45f) {
                        safely {
                            check(!TaskRuntime.isActive) { "请先停止任务" }
                            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_MIME_TYPES,
                                    arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")), 201)
                        }
                    }
                    BButton("导入 config / event JSON", Modifier.fillMaxWidth().item(), enabled = on, disabledAlpha = .45f) {
                        safely {
                            check(!TaskRuntime.isActive) { "请先停止任务" }; importingId = selected.id
                            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 203)
                        }
                    }
                }
            }
        }
    }

    private fun newProfile() {
            check(!TaskRuntime.isActive) { "请先停止任务" }
            val name = UiTheme.input(this).apply { hint = "配置名称"; setSingleLine(true) }
            val form = UiTheme.inputPanel(this, name)
            val servers = listOf("官服", "B服", "日服", "国际服")
            var serverIndex = 0
            val serverLabel = UiTheme.text(this, servers[serverIndex], 13f)
            val arrow = UiTheme.chevron(this)
            val selector = row().apply {
                background = UiTheme.fieldBackground(this@ProfilePage)
                addView(serverLabel, LinearLayout.LayoutParams(0, -1, 1f))
                addView(arrow, LinearLayout.LayoutParams(UiTheme.dp(this@ProfilePage, 24), -1))
                isClickable = true; isFocusable = true; contentDescription = "区服"
                setOnClickListener {
                    ChoiceMenu.show(this, servers, serverIndex, arrow) { index ->
                        serverIndex = index; serverLabel.text = servers[index]
                    }
                }
            }
            form.item(row().apply {
                item(UiTheme.text(this@ProfilePage, "区服", 13f), 1f)
                addView(selector, LinearLayout.LayoutParams(UiTheme.dp(this@ProfilePage, 132), UiTheme.dp(this@ProfilePage, 36)))
            })
            val dialog = UiTheme.dialog(this).setTitle("新建配置").setView(form).setNegativeButton("取消", null).setPositiveButton("创建", null).create()
            dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                safely { store.select(store.newProfile(name.text.toString(), servers[serverIndex]).id); render(); dialog.dismiss() }
            } }; dialog.show()
    }

    private fun rename(selected: ProfileStore.Profile) {
        val input = UiTheme.input(this).apply { setText(selected.name); setSingleLine(true) }
        UiTheme.dialog(this).setTitle("配置名称").setView(UiTheme.inputPanel(this, input)).setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ -> safely { store.rename(selected.id, input.text.toString()); render() } }.show()
    }
    @Deprecated("Platform activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
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
            UiTheme.dialog(this).setMessage(error.message ?: "配置操作失败").setPositiveButton("确定", null).show()
        }
    }
}
