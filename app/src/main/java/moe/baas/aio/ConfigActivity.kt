package moe.baas.aio

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.launch

/** Configuration management reuses the same collapsible sections as Home settings. */
class ConfigActivity : PaneActivity() {
    override fun createPage(intent: Intent, close: () -> Unit) = ConfigPage(this, intent, close)
}

class ConfigPage(activity: Activity, intent: Intent, close: () -> Unit) : PanePage(activity, intent, close) {
    private lateinit var panel: SettingsPanel
    private lateinit var profileId: String
    private lateinit var profileName: String
    private var focusGroup: Int? = null
    private val changed: (String) -> Unit = { runOnUiThread { if (::panel.isInitialized) panel.refreshEnabled() } }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        try {
            val store = ProfileStore(this)
            profileId = state?.getString("profileId") ?: intent.getStringExtra("profileId") ?: store.selected().id
            val initial = state ?: Bundle().apply {
                if (intent.hasExtra("group")) putIntegerArrayList("expanded", arrayListOf(intent.getIntExtra("group", 0)))
            }
            panel = SettingsPanel(activity, profileId, initial)
            profileName = store.get(profileId).name
            if (state == null && intent.hasExtra("group")) focusGroup = intent.getIntExtra("group", 0)
            show()
            TaskRuntime.observe(changed)
        } catch (error: Exception) {
            UiTheme.dialog(this).setMessage(error.message).setPositiveButton("返回") { _, _ -> finish() }.show()
        }
    }

    @Composable
    override fun Content() {
        val scroll = rememberScrollState()
        val scope = rememberCoroutineScope()
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().item(), verticalAlignment = Alignment.CenterVertically) {
                BText("配置设置", 20f, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                BButton("返回", flat = true) { leave() }
            }
            BText(profileName, 12f, Ui.Muted, Modifier.fillMaxWidth().item())
            panel.Content(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll), focusGroup) { y ->
                focusGroup = null
                scope.launch { scroll.scrollTo(y) }
            }
        }
    }

    private fun leave() {
        if (!::panel.isInitialized || !panel.dirty) finish()
        else UiTheme.dialog(this).setMessage("放弃未保存的修改？").setNegativeButton("继续编辑", null).setPositiveButton("放弃") { _, _ -> finish() }.show()
    }
    override fun onResume() {
        super.onResume()
        if (::panel.isInitialized) { panel.reloadIfClean(); panel.refreshEnabled() }
    }
    override fun onBackPressed() = leave()
    override fun onSaveInstanceState(state: Bundle) {
        if (::panel.isInitialized) { state.putAll(panel.saveState()); state.putString("profileId", profileId) }
        super.onSaveInstanceState(state)
    }
    override fun onDestroy() { TaskRuntime.remove(changed); super.onDestroy() }
}
