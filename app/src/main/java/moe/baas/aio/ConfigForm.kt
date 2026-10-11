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
        catalog.fields().forEach { field ->
            val key = field.getString("key")
            if (draft.get(key).toString() != baseline.values.get(key).toString()) put(key, draft.get(key))
        }
    }
    private fun set(field: JSONObject, value: Any, refresh: Boolean = true) {
        check(!TaskRuntime.isActive) { "请先停止任务再修改参数" }
        catalog.validate(field.getString("key"), value)
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
        if (value is JSONArray) return when (field.optString("kind")) {
            "shop" -> "${(0 until value.length()).count { value.optInt(it) == 1 }} 件"
            "teams" -> "${value.length()} 组"
            else -> (0 until value.length()).joinToString("、") { value.get(it).toString() }.ifEmpty { "未设置" }
        }
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
        renders // re-render trigger
        stamp
        key(renders) {
            Column {
                val groupFields = catalog.groups.getJSONObject(group).getJSONArray("fields")
                var first = true
                val active = TaskRuntime.isActive
                for (i in 0 until groupFields.length()) {
                    val field = groupFields.getJSONObject(i); val key = field.getString("key")
                    if (!shown(key)) continue
                    val value = draft.get(key)
                    if (!first) Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x80DAE8F1)))
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
        val boolean = value is Boolean
        val number = !boolean && (value is Number || field.optBoolean("numeric")) && field.optJSONArray("options")?.length() == 0
        Ui.Quiet {
            Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp).alpha(if (active) .55f else 1f)
                .then(when {
                    boolean -> Modifier.clickable(enabled = !active) { safely { set(field, !(value as Boolean), refresh = key == "cafe_reward_has_no2_cafe") } }
                    number -> Modifier
                    else -> Modifier.clickable(enabled = !active) { safely { edit(field) } }
                })
                .padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                BText(label, 12.5f, modifier = Modifier.weight(1f), padding = PaddingValues(end = 8.dp), maxLines = 2)
                when {
                    boolean -> CompactSwitch(value as Boolean, label, Modifier.size(42.dp, 36.dp), enabled = !active) { checked ->
                        safely { set(field, checked, refresh = key == "cafe_reward_has_no2_cafe") }
                    }
                    number -> {
                        val original = catalog.defaults.get(key)
                        val integer = original is Int || original is Long || key != "screenshot_interval"
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
                            Chevron(menuFor == key, Modifier.width(20.dp).fillMaxHeight(), alpha = .65f)
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
            "slots", "teams" -> if (key == "lesson_times") editText(field, value) else editSlots(field, value as JSONArray)
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
            if (field.optBoolean("numeric") || value is Number) {
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
                    val replacement: Any = when (catalog.defaults.get(field.getString("key"))) {
                        is Int, is Long -> raw.toInt()
                        is Number -> raw.toDouble()
                        is JSONArray -> if (field.optString("kind") == "slots") {
                            // Region positions matter: reject missing entries instead of shifting later regions.
                            val items = if (raw.isEmpty()) emptyList() else raw.split(Regex("[,，]\\s*|\\r?\\n"))
                            JSONArray(items.map { requireNotNull(it.trim().toIntOrNull()) { "请输入逗号分隔的整数，跳过区域请填 0" } })
                        } else JSONArray(raw.split(',', '，', '\n').map { it.trim() }.filter { it.isNotEmpty() })
                        else -> raw
                    }
                    set(field, replacement); dialog.dismiss()
                }
            }
        }; dialog.show()
    }
    private fun editShop(field: JSONObject, value: JSONArray) {
        val mode = when (store.get(profileId).server) { "日服" -> "JP"; "官服", "B服" -> "CN"; else -> "Global" }
        val goods = catalog.static.getJSONObject(field.getString("goods")).getJSONArray(mode)
        val count = maxOf(goods.length(), value.length())
        val checks = BooleanArray(count) { value.optInt(it) == 1 }
        val labels = (0 until count).map { i ->
            goods.optJSONArray(i)?.let { "${it.getString(0)} · ${it.get(1)}" } ?: "商品 ${i + 1}"
        }.toTypedArray()
        UiTheme.dialog(context).setTitle(field.getString("label"))
            .setMultiChoiceItems(labels, checks) { _, i, checked -> checks[i] = checked }
            .setNegativeButton("取消", null).setPositiveButton("确定") { _, _ -> safely { set(field, JSONArray(checks.map { if (it) 1 else 0 })) } }.show()
    }
    private fun editSlots(field: JSONObject, value: JSONArray) {
        val copy = JSONArray(value.toString()); val body = column()
        val teams = field.optString("kind") == "teams"
        val slots = mutableListOf<NumberInput>()
        for (r in 0 until copy.length()) {
            val values = if (teams) copy.getJSONArray(r) else JSONArray().put(copy.get(r))
            val controls = row()
            if (teams) body.item(text("编队 ${r + 1}", 12f))
            else controls.item(text("第 ${r + 1} ${if (field.getString("key") == "lesson_times") "区域" else "场"}", 12f), 1f)
            for (c in 0 until values.length()) {
                if (teams) {
                    val choices = field.getJSONArray("options")
                    val spinner = Spinner(context).apply {
                        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item,
                            (0 until choices.length()).map { choices.getJSONObject(it).getString("label") }).apply {
                            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                        }
                        minimumHeight = UiTheme.dp(context, 48)
                        dropDownWidth = UiTheme.dp(context, 160)
                        setSelection((0 until choices.length()).firstOrNull { choices.getJSONObject(it).getString("value") == values.getString(c) } ?: choices.length() - 1)
                    }
                    controls.item(spinner, 1f)
                    spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) { values.put(c, choices.getJSONObject(position).getString("value")) }
                        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                    }
                } else {
                    val input = NumberInput(context, values.get(0).toString(), field.getString("label") + " ${r + 1}",
                        if (field.isNull("min")) null else field.get("min").toString().toBigDecimal(),
                        if (field.isNull("max")) Int.MAX_VALUE.toBigDecimal() else field.get("max").toString().toBigDecimal())
                    slots += input; controls.item(input, 1f)
                }
            }
            body.item(controls)
        }
        val panel = column().apply {
            setPadding(UiTheme.dp(context, 12), UiTheme.dp(context, 4), UiTheme.dp(context, 12), UiTheme.dp(context, 4))
            addView(ScrollView(context).apply { addView(body) }, LinearLayout.LayoutParams(-1, UiTheme.dp(context, 280)))
        }
        val dialog = UiTheme.dialog(context).setTitle(field.getString("label"))
            .setView(panel).setNegativeButton("取消", null).setPositiveButton("确定", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            safely { if (!teams) slots.forEachIndexed { i, input -> copy.put(i, requireNotNull(input.value) { "请检查第 ${i + 1} 项数值" }.toBigDecimal().intValueExact()) }; set(field, copy); dialog.dismiss() }
        } }; dialog.show()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
    private fun safely(action: () -> Unit) {
        try { action() } catch (error: Exception) { UiTheme.dialog(context).setMessage(error.message ?: "配置操作失败").setPositiveButton("确定", null).show() }
    }
}
