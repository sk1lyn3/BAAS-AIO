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
                    BText(getString(R.string.profile_title), 22f, weight = FontWeight.ExtraBold, modifier = Modifier.weight(1f).item())
                    BButton(getString(R.string.action_back), Modifier.weight(1f).item()) { safely { finish() } }
                }
                for (profile in profiles) {
                    val chosen = profile.id == selected.id
                    BButton("${if (chosen) "✓ " else ""}${profile.name} · ${profile.server}", Modifier.fillMaxWidth().item(), primary = chosen) {
                        safely { store.select(profile.id); render() }
                    }
                }
                BButton(getString(R.string.profile_new), Modifier.fillMaxWidth().item(), enabled = !active) { safely { newProfile() } }
                BCard(Modifier.item()) {
                    BText(selected.name, 16f, weight = FontWeight.Bold, modifier = Modifier.item())
                    val on = !active
                    Row(Modifier.fillMaxWidth().item()) {
                        BButton(getString(R.string.tab_settings), Modifier.weight(1f).item()) {
                            safely { startActivity(Intent(this@ProfilePage, ConfigActivity::class.java).putExtra("profileId", selected.id)) }
                        }
                        BButton(getString(R.string.profile_rename), Modifier.weight(1f).item(), enabled = on, disabledAlpha = .45f) { safely { rename(selected) } }
                        BButton(getString(R.string.action_copy), Modifier.weight(1f).item(), enabled = on, disabledAlpha = .45f) { safely { store.select(store.duplicate(selected.id).id); render() } }
                    }
                    Row(Modifier.fillMaxWidth().item()) {
                        BButton(getString(R.string.profile_export), Modifier.weight(1f).item(), enabled = on, disabledAlpha = .45f) {
                            safely {
                                check(!TaskRuntime.isActive) { getString(R.string.profile_stop_task_first) }; exporting = selected.id
                                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip")
                                    .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "${selected.name}.zip"), 202)
                            }
                        }
                        BButton(getString(R.string.action_delete), Modifier.weight(1f).item(), enabled = on, disabledAlpha = .45f) {
                            safely {
                                check(!TaskRuntime.isActive) { getString(R.string.profile_stop_task_first) }
                                UiTheme.dialog(this@ProfilePage).setMessage(getString(R.string.profile_confirm_delete, selected.name)).setNegativeButton(getString(R.string.action_cancel), null)
                                    .setPositiveButton(getString(R.string.action_delete)) { _, _ -> safely { store.delete(selected.id); render() } }.show()
                            }
                        }
                    }
                    BButton(getString(R.string.profile_import_zip), Modifier.fillMaxWidth().item(), enabled = on, disabledAlpha = .45f) {
                        safely {
                            check(!TaskRuntime.isActive) { getString(R.string.profile_stop_task_first) }
                            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_MIME_TYPES,
                                    arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")), 201)
                        }
                    }
                    BButton(getString(R.string.profile_import_json), Modifier.fillMaxWidth().item(), enabled = on, disabledAlpha = .45f) {
                        safely {
                            check(!TaskRuntime.isActive) { getString(R.string.profile_stop_task_first) }; importingId = selected.id
                            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 203)
                        }
                    }
                }
            }
        }
    }

    private fun newProfile() {
            check(!TaskRuntime.isActive) { getString(R.string.profile_stop_task_first) }
            val name = UiTheme.input(this).apply { hint = getString(R.string.profile_name); setSingleLine(true) }
            val form = UiTheme.inputPanel(this, name)
            val servers = listOf("官服", "B服", "日服", "国际服")
            var serverIndex = 0
            // Grid: input and selector share the form's 16dp inset, the same 44dp height and the same 12dp text inset.
            val serverLabel = UiTheme.text(this, servers[serverIndex], 13f).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(UiTheme.dp(this@ProfilePage, 12), 0, 0, 0)
            }
            val arrow = UiTheme.chevron(this)
            val selector = row().apply {
                background = UiTheme.fieldBackground(this@ProfilePage)
                addView(serverLabel, LinearLayout.LayoutParams(0, -1, 1f))
                addView(arrow, LinearLayout.LayoutParams(UiTheme.dp(this@ProfilePage, 24), -1))
                isClickable = true; isFocusable = true; contentDescription = getString(R.string.profile_server)
                setOnClickListener {
                    ChoiceMenu.show(this, servers, serverIndex, arrow) { index ->
                        serverIndex = index; serverLabel.text = servers[index]
                    }
                }
            }
            form.addView(row().apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(UiTheme.text(this@ProfilePage, getString(R.string.profile_server), 13f).apply { setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(selector, LinearLayout.LayoutParams(UiTheme.dp(this@ProfilePage, 148), UiTheme.dp(this@ProfilePage, 44)))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiTheme.dp(this@ProfilePage, 10) })
            val dialog = UiTheme.dialog(this).setTitle(getString(R.string.profile_new)).setView(form).setNegativeButton(getString(R.string.action_cancel), null).setPositiveButton(getString(R.string.profile_create), null).create()
            dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                safely { store.select(store.newProfile(name.text.toString(), servers[serverIndex]).id); render(); dialog.dismiss() }
            } }; dialog.show()
    }

    private fun rename(selected: ProfileStore.Profile) {
        val input = UiTheme.input(this).apply { setText(selected.name); setSingleLine(true) }
        UiTheme.dialog(this).setTitle(getString(R.string.profile_name)).setView(UiTheme.inputPanel(this, input)).setNegativeButton(getString(R.string.action_cancel), null)
            .setPositiveButton(getString(R.string.action_save)) { _, _ -> safely { store.rename(selected.id, input.text.toString()); render() } }.show()
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
            UiTheme.dialog(this).setMessage(error.message ?: getString(R.string.profile_operation_failed)).setPositiveButton(getString(R.string.action_ok), null).show()
        }
    }
}
