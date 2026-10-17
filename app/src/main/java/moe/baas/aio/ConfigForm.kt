package moe.baas.aio

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.baas.aio.UiTheme.item
import org.json.JSONArray
import org.json.JSONObject

/** Shared draft and native parameter controls for inline and standalone settings (Compose rendering). */
class ConfigForm(private val context: Context, val profileId: String, state: Bundle? = null,
                 private val changed: () -> Unit = {}) {
    private val store = ProfileStore(context)
    val catalog = ConfigCatalog(context)
    private var baseline = if (state == null) store.config(profileId) else
        ProfileStore.ConfigSnapshot(state.getString("baseline")!!, JSONObject(state.getString("original")!!))
    private var draft = JSONObject(state?.getString("draft") ?: baseline.values.toString())
    /** Incremented whenever the original implementation would re-render the field views. */
    private var renders by mutableIntStateOf(0)
    /** Incremented on every draft/text change so dirty/valid observers recompose. */
    var stamp by mutableIntStateOf(0)
        private set
    /** Texts of rendered number inputs (cleared on re-render, like recreated NumberInputs). */
    private val numberTexts = mutableStateMapOf<String, String>()
    private val numberRules = mutableMapOf<String, NumberRules>()
    private var menuFor by mutableStateOf<String?>(null)
    private var editable = !TaskRuntime.isActive
    private fun column() = UiTheme.column(context)
    private fun row() = UiTheme.row(context, Gravity.CENTER_VERTICAL)
    private fun text(value: String, size: Float = 13f) = UiTheme.text(context, value, size)
    val valid get() = numberTexts.all { (key, text) -> numberRules[key]?.valid(text) != false }
    val dirty get() = changes().length() > 0 || !valid
    private fun redraw() {
        numberTexts.clear(); renders++; stamp++
        changed()
    }
    fun refreshEnabled() {
        if (editable != !TaskRuntime.isActive) { editable = !TaskRuntime.isActive; redraw() }
    }
    fun reloadIfClean() {
        if (!dirty) { baseline = store.config(profileId); draft = JSONObject(baseline.values.toString()); redraw() }
    }
    fun reset() { draft = JSONObject(baseline.values.toString()); redraw() }
    fun save() {
        check(valid) { "请检查数值输入" }
        store.saveConfig(profileId, baseline, changes())
        baseline = store.config(profileId); draft = JSONObject(baseline.values.toString()); redraw()
    }
    fun saveState() = Bundle().apply {
        putString("profileId", profileId); putString("baseline", baseline.raw)
        putString("original", baseline.values.toString()); putString("draft", draft.toString())
    }
    private fun changes() = JSONObject().apply {
        (catalog.fields() + catalog.otherFields).forEach { field ->
            val key = field.getString("key")
            if (draft.get(key).toString() != baseline.values.get(key).toString()) put(key, draft.get(key))
        }
    }
    private fun set(field: JSONObject, value: Any, refresh: Boolean = true) {
        check(!TaskRuntime.isActive) { "请先停止任务再修改参数" }
        catalog.validate(field.getString("key"), value, baseline.values.get(field.getString("key")))
        draft.put(field.getString("key"), value)
        if (refresh) redraw() else { stamp++; changed() }
    }
    private fun shown(key: String): Boolean {
        if ((key.contains("invite2_") || key == "favorStudent2") && !draft.getBoolean("cafe_reward_has_no2_cafe")) return false
        for (n in 1..2) {
            if (key == "cafe_reward_invite${n}_starred_student_position") return draft.getString("cafe_reward_invite${n}_criterion") == "starred"
            if (key == "favorStudent$n") return draft.getString("cafe_reward_invite${n}_criterion") == "name"
        }
        if (key == "side_team_attribute") return draft.getString("choose_team_method") == "side"
        if (key == "preset_team_attribute") return draft.getString("choose_team_method") == "preset"
        for (n in 2..3) if (key.contains("phase$n") || key.contains("phase_${n}_")) return draft.getInt("create_phase") >= n
        return true
    }
    private fun display(field: JSONObject, value: Any): String {
        if (field.optBoolean("readOnly")) return when (value) { is JSONArray -> "${value.length()} 项"; is JSONObject -> "${value.length()} 项"; else -> value.toString() }
        if (value is JSONArray) return when (field.optString("kind")) {
            "shop" -> "${(0 until value.length()).count { value.optInt(it) == 1 }} 件"
            "teams" -> "${value.length()} 组"
            else -> (0 until value.length()).joinToString("、") { value.get(it).toString() }.ifEmpty { "未设置" }
        }
        if (field.getString("key") == "main_story_regions") return value.toString().ifEmpty { "全部章节" }
        val choices = field.optJSONArray("options")
        if (choices != null) for (i in 0 until choices.length()) {
            val choice = choices.getJSONObject(i)
            if (choice.getString("value") == value.toString()) return choice.getString("label")
        }
        return value.toString().ifEmpty { "未设置" }
    }
    /** One catalog group's fields (former render(group, fields)). [extras] follow their field like before. */
    @Composable
    fun Fields(group: Int, extras: Map<String, @Composable () -> Unit> = emptyMap()) {
        val groupFields = catalog.groups.getJSONObject(group).getJSONArray("fields")
        Fields((0 until groupFields.length()).map { groupFields.getJSONObject(it) }, extras)
    }

    /** 其他参数: core settings outside the catalog groups. */
    @Composable
    fun OtherFields() = Fields(catalog.otherFields, emptyMap())

    @Composable
    private fun Fields(fields: List<JSONObject>, extras: Map<String, @Composable () -> Unit>) {
        renders // re-render trigger
        stamp
        key(renders) {
            Column {
                var first = true
                val active = TaskRuntime.isActive
                for (field in fields) {
                    val key = field.getString("key")
                    if (!shown(key)) continue
                    val value = draft.get(key)
                    if (!first) Box(Modifier.fillMaxWidth().height(1.dp).background(Ui.Divider))
                    first = false
                    Line(field, key, value, active)
                    extras[key]?.let { Box(Modifier.item()) { it() } }
                }
            }
        }
    }

    @Composable
    private fun Line(field: JSONObject, key: String, value: Any, active: Boolean) {
        val label = field.getString("label")
        val locked = field.optBoolean("readOnly")
        val boolean = !locked && value is Boolean
        val number = !locked && !boolean && ((value is Number && catalog.defaults.get(key) is Number) || field.optBoolean("numeric")) && field.optJSONArray("options")?.length() == 0
        Ui.Quiet {
            Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp).alpha(if (active) .55f else 1f)
                .then(when {
                    boolean -> Modifier.clickable(enabled = !active) { safely { set(field, !(value as Boolean), refresh = key == "cafe_reward_has_no2_cafe") } }
                    number || locked -> Modifier
                    else -> Modifier.clickable(enabled = !active) { safely { edit(field) } }
                })
                .padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    BText(label, 12.5f, modifier = Modifier.weight(1f, fill = false), padding = PaddingValues(0.dp), maxLines = 2)
                    HelpTip(key, label = label)
                }
                when {
                    boolean -> CompactSwitch(value as Boolean, label, Modifier.size(42.dp, 36.dp), enabled = !active) { checked ->
                        safely { set(field, checked, refresh = key == "cafe_reward_has_no2_cafe") }
                    }
                    number -> {
                        val original = catalog.defaults.get(key)
                        val integer = when (original) { is Int, is Long -> true; is Number -> false; else -> key != "screenshot_interval" }
                        val min = if (field.isNull("min")) null else field.get("min").toString().toBigDecimal()
                        val max = if (field.isNull("max")) {
                            if (original is Int) Int.MAX_VALUE.toBigDecimal() else if (original is Long) Long.MAX_VALUE.toBigDecimal() else null
                        } else field.get("max").toString().toBigDecimal()
                        val rules = NumberRules(min, max, (if (integer) "1" else "0.1").toBigDecimal(), integer, original == "")
                        numberRules[key] = rules
                        NumberField(numberTexts[key] ?: value.toString(), { text ->
                            numberTexts[key] = text; stamp++
                            val raw = rules.value(text)
                            if (raw == null) changed() else safely {
                                val parsed: Any = when (original) {
                                    is Int -> raw.toBigDecimal().intValueExact()
                                    is Long -> raw.toBigDecimal().longValueExact()
                                    is Number -> raw.toDouble()
                                    else -> raw
                                }
                                set(field, parsed, refresh = false)
                            }
                        }, label, rules, Modifier.width(132.dp), editable = !active)
                    }
                    else -> Box {
                        Row(Modifier.size(132.dp, 36.dp).field(), verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Text(display(field, value),
                                Modifier.weight(1f).padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
                                style = textStyle(13f, Ui.Navy).copy(textAlign = TextAlign.End, platformStyle = PlatformTextStyle(includeFontPadding = false)),
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (locked) Box(Modifier.width(10.dp)) else Chevron(menuFor == key, Modifier.width(20.dp).fillMaxHeight(), alpha = .65f)
                        }
                        if (menuFor == key) {
                            val options = field.getJSONArray("options")
                            val labels = (0 until options.length()).map { options.getJSONObject(it).getString("label") }
                            val selected = (0 until options.length()).firstOrNull { options.getJSONObject(it).getString("value") == value.toString() } ?: -1
                            ChoicePopup(labels, selected, 132.dp, onDismiss = { menuFor = null }) { index ->
                                safely {
                                    val choice = options.getJSONObject(index).getString("value")
                                    set(field, if (catalog.defaults.get(key) is Number) choice.toInt() else choice)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    private fun edit(field: JSONObject) {
        val key = field.getString("key"); val value = draft.get(key)
        when (field.optString("kind")) {
            "shop" -> editShop(field, value as JSONArray)
            "teams" -> editTeams(field, value as JSONArray)
            "slots" -> if (key == "lesson_times") editText(field, value) else editSlots(field, value as JSONArray)
            else -> {
                val options = field.optJSONArray("options")
                if (options != null && options.length() > 0) menuFor = key
                else editText(field, value)
            }
        }
    }
    private fun editText(field: JSONObject, value: Any) {
        val input = UiTheme.input(context).apply {
            setText(if (value is JSONArray) (0 until value.length()).joinToString(", ") { value.getString(it) } else value.toString())
            if (field.optBoolean("numeric") || catalog.defaults.get(field.getString("key")) is Number) {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setSingleLine(true)
            }
            else { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; maxLines = 4 }
        }
        input.contentDescription = field.getString("label")
        val panel = UiTheme.inputPanel(context, input)
        val dialog = UiTheme.dialog(context).setTitle(field.getString("label")).setView(panel)
            .setNegativeButton("取消", null).setPositiveButton("确定", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                safely {
                    val raw = input.text.toString().trim()
                    val replacement: Any = if (ConfigCatalog.isListText(field.getString("key"))) ConfigCatalog.splitList(raw)
                    else when (catalog.defaults.get(field.getString("key"))) {
                        is Int, is Long -> raw.toInt()
                        is Number -> raw.toDouble()
                        is JSONArray -> if (field.optString("kind") == "slots") {
                            // Region positions matter: reject missing entries instead of shifting later regions.
                            val items = if (raw.isEmpty()) emptyList() else raw.split(Regex("[,，]\\s*|\\r?\\n"))
                            JSONArray(items.map { requireNotNull(it.trim().toIntOrNull()) { "请输入逗号分隔的整数，跳过区域请填 0" } })
                        } else JSONArray(raw.split(',', '，', '\n').map { it.trim() }.filter { it.isNotEmpty() })
                        else -> if (field.getString("key") == "main_story_regions") MainStoryEpisodes.normalize(raw) else raw
                    }
                    set(field, replacement); dialog.dismiss()
                }
            }
        }; dialog.show()
    }
    private fun editShop(field: JSONObject, value: JSONArray) {
        val mode = serverMode()
        val goods = catalog.static.getJSONObject(field.getString("goods")).getJSONArray(mode)
        val fallback = if (field.getString("key").startsWith("Tactical")) "战术币" else "信用点"
        ShopPicker.show(context, field.getString("label"), goods, value, fallback) { safely { set(field, it) } }
    }
    private fun serverMode() = GameServer.mode(store.get(profileId).server)

    /** side/preset_team_attribute: list of groups, each a list of attribute values; tap a slot to choose. */
    private fun editTeams(field: JSONObject, value: JSONArray) {
        val copy = JSONArray(value.toString()); val body = column()
        val choices = field.getJSONArray("options")
        val values = (0 until choices.length()).map { choices.getJSONObject(it).getString("value") }
        val labels = (0 until choices.length()).map { choices.getJSONObject(it).getString("label") }
        fun label(v: String) = labels.getOrNull(values.indexOf(v)) ?: v
        fun paint(chip: android.widget.TextView, v: String) {
            chip.text = label(v)
            chip.contentDescription = "${chip.tag}：${label(v)}"
            val unused = v == "Unused"
            chip.setTextColor(if (unused) UiTheme.muted else UiTheme.blue)
            chip.background = UiTheme.background(context, if (unused) UiTheme.pale else androidx.core.graphics.ColorUtils.setAlphaComponent(UiTheme.blue, 0x22), 10).apply {
                setStroke(UiTheme.dp(context, 1), if (unused) androidx.core.graphics.ColorUtils.setAlphaComponent(UiTheme.muted, 0x40) else UiTheme.blue)
            }
        }
        val preset = field.getString("key") == "preset_team_attribute"
        for (r in 0 until copy.length()) {
            val group = copy.getJSONArray(r)
            body.item(text(if (preset) "第 ${r + 1} 组" else "编队 ${r + 1}", 12f))
            val controls = row()
            for (c in 0 until group.length()) {
                val chip = UiTheme.text(context, "", 13f).apply {
                    gravity = Gravity.CENTER; minHeight = UiTheme.dp(context, 40)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    tag = "第 ${r + 1} 组第 ${c + 1} 个"
                }
                paint(chip, group.getString(c))
                chip.setOnClickListener {
                    TilePicker.dropdown(chip, labels.map { TileItem(it) }, values.indexOf(group.getString(c))) { i ->
                        group.put(c, values[i]); paint(chip, values[i])
                    }
                }
                controls.addView(chip, LinearLayout.LayoutParams(0, -2, 1f).apply { if (c > 0) leftMargin = UiTheme.dp(context, 4) })
            }
            body.item(controls)
        }
        val panel = column().apply {
            setPadding(UiTheme.dp(context, 12), UiTheme.dp(context, 4), UiTheme.dp(context, 12), UiTheme.dp(context, 4))
            addView(ScrollView(context).apply { addView(body) }, LinearLayout.LayoutParams(-1, -2))
        }
        val dialog = UiTheme.dialog(context).setTitle(field.getString("label"))
            .setView(panel).setNegativeButton("取消", null).setPositiveButton("确定", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { safely { set(field, copy); dialog.dismiss() } } }
        dialog.show()
    }
    /** kind "slots" (drill_difficulty_list / drill_fight_formation_list): one number per slot. Teams use editTeams. */
    private fun editSlots(field: JSONObject, value: JSONArray) {
        val copy = JSONArray(value.toString()); val body = column()
        val slots = mutableListOf<NumberInput>()
        for (r in 0 until copy.length()) {
            val controls = row()
            controls.item(text("第 ${r + 1} ${if (field.getString("key") == "lesson_times") "区域" else "场"}", 12f), 1f)
            val input = NumberInput(context, copy.get(r).toString(), field.getString("label") + " ${r + 1}",
                if (field.isNull("min")) null else field.get("min").toString().toBigDecimal(),
                if (field.isNull("max")) Int.MAX_VALUE.toBigDecimal() else field.get("max").toString().toBigDecimal())
            slots += input; controls.item(input, 1f)
            body.item(controls)
        }
        val panel = column().apply {
            setPadding(UiTheme.dp(context, 12), UiTheme.dp(context, 4), UiTheme.dp(context, 12), UiTheme.dp(context, 4))
            addView(ScrollView(context).apply { addView(body) }, LinearLayout.LayoutParams(-1, UiTheme.dp(context, 280)))
        }
        val dialog = UiTheme.dialog(context).setTitle(field.getString("label"))
            .setView(panel).setNegativeButton("取消", null).setPositiveButton("确定", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            safely { slots.forEachIndexed { i, input -> copy.put(i, requireNotNull(input.value) { "请检查第 ${i + 1} 项数值" }.toBigDecimal().intValueExact()) }; set(field, copy); dialog.dismiss() }
        } }; dialog.show()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
    private fun safely(action: () -> Unit) {
        try { action() } catch (error: Exception) { UiTheme.dialog(context).setMessage(error.message ?: "配置操作失败").setPositiveButton("确定", null).show() }
    }
}
