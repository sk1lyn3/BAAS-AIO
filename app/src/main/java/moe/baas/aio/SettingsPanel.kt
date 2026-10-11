package moe.baas.aio

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** WebUI-style collapsible sections sharing one unsaved configuration draft. */
class SettingsPanel(private val context: Context, private val profileId: String, state: Bundle? = null) {
    private val form = ConfigForm(context, profileId, state?.takeIf { it.containsKey("baseline") })
    private val expanded = mutableStateListOf<Int>().apply { state?.getIntegerArrayList("expanded")?.let { addAll(it) } }
    private val manualPanels = mutableMapOf<String, ManualTaskPanel>()
    private val restored = state
    val dirty get() = form.dirty
    private fun manual(title: String) = manualPanels.getOrPut(title) {
        ManualTaskPanel(context as Activity, profileId, title, restored?.getBundle(title)) { form.dirty }
    }

    /** [scrollTo] receives the y offset of [focusGroup]'s card the first time it is laid out. */
    @Composable
    fun Content(modifier: Modifier = Modifier, focusGroup: Int? = null, scrollTo: ((Int) -> Unit)? = null) {
        form.stamp
        LiveTask.snapshot
        Column(modifier) {
            Row(Modifier.fillMaxWidth().item(), verticalAlignment = Alignment.CenterVertically) {
                val canReset = form.dirty
                BButton("撤销修改", Modifier.weight(1f).height(32.dp).padding(end = 0.dp), flat = true, enabled = canReset, disabledAlpha = .45f) { form.reset() }
                androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                val canSave = !TaskRuntime.isActive && form.dirty && form.valid
                BButton("保存", Modifier.weight(1f).height(32.dp), flat = true, enabled = canSave, disabledAlpha = .45f,
                    fill = Ui.Blue, stroke = null, textColor = Color.White, radius = 8.dp) {
                    try {
                        form.save(); manualPanels.values.forEach { it.refreshProfile() }; Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                    } catch (error: Exception) {
                        UiTheme.dialog(context).setMessage(error.message ?: "保存失败").setPositiveButton("确定", null).show()
                    }
                }
            }
            val groups = form.catalog.groups
            for (group in 0 until groups.length()) {
                val title = groups.getJSONObject(group).getString("title")
                Section(title, group, focusGroup, scrollTo) {
                    val extras: Map<String, @Composable () -> Unit> = if (title == "关卡推图与剧情") mapOf(
                        "manual_boss" to { manual("推图").Content() },
                        "main_story_regions" to { manual("剧情").Content() },
                    ) else emptyMap()
                    form.Fields(group, extras)
                }
                if (title == "关卡推图与剧情") Section("活动图", groups.length(), focusGroup, scrollTo) { manual("活动图").Content() }
            }
            Section("其他功能", groups.length() + 1, focusGroup, scrollTo) { manual("其他功能").Content() }
        }
    }

    @Composable
    private fun Section(title: String, id: Int, focusGroup: Int?, scrollTo: ((Int) -> Unit)?, content: @Composable () -> Unit) {
        val open = id in expanded
        val reported = remember { booleanArrayOf(false) }
        BCard(Modifier.item().then(if (id == focusGroup && scrollTo != null) Modifier.onGloballyPositioned {
            if (!reported[0]) { reported[0] = true; scrollTo(it.positionInParent().y.toInt()) }
        } else Modifier), padding = PaddingValues(start = 12.dp, end = 8.dp)) {
            Ui.Quiet {
                Row(Modifier.fillMaxWidth().height(50.dp).softShadow(3.dp, RoundedCornerShape(14.dp), 0.08f).background(Ui.Card, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))
                    .clickable { if (!expanded.remove(id)) expanded.add(id) }
                    .semantics { contentDescription = "$title，${if (open) "已展开" else "已收起"}" },
                    verticalAlignment = Alignment.CenterVertically) {
                    BText(title, 13f, weight = FontWeight.Bold, padding = NoPadding, modifier = Modifier.weight(1f))
                    Chevron(open, Modifier.width(24.dp).fillMaxHeight())
                }
            }
            AnimatedVisibility(open, enter = expandVertically(tween(160)) + fadeIn(tween(160)), exit = shrinkVertically(tween(140)) + fadeOut(tween(140))) {
                Column(Modifier.fillMaxWidth().padding(top = 4.dp, end = 4.dp, bottom = 8.dp)) { content() }
            }
        }
    }

    fun refreshEnabled() { form.refreshEnabled() }
    fun reloadIfClean() { form.reloadIfClean(); manualPanels.values.forEach { it.refreshProfile() } }
    fun saveState() = form.saveState().apply { putIntegerArrayList("expanded", ArrayList(expanded)); manualPanels.forEach { (title, panel) -> putBundle(title, panel.saveState()) } }
}
