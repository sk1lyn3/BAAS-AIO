package moe.baas.aio

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.*
import moe.baas.aio.UiTheme.item
import org.json.JSONArray
import org.json.JSONObject

/** Shared draft and native parameter controls for inline and standalone settings. */
class ConfigForm(private val context: Context, val profileId: String, state: Bundle? = null,
                 private val changed: () -> Unit = {}) {
    private val store = ProfileStore(context)
    val catalog = ConfigCatalog(context)
    private var baseline = if (state == null) store.config(profileId) else
        ProfileStore.ConfigSnapshot(state.getString("baseline")!!, JSONObject(state.getString("original")!!))
    private var draft = JSONObject(state?.getString("draft") ?: baseline.values.toString())
    private val views = mutableMapOf<Int, LinearLayout>()
    private var editable = !TaskRuntime.isActive
    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun text(value: String, size: Float = 13f) = UiTheme.text(context, value, size)
    val dirty get() = changes().length() > 0
    fun view(group: Int): LinearLayout = views.getOrPut(group) { column().also { render(group, it) } }
    private fun redraw() {
        val scroll = views.values.asSequence().mapNotNull { fields ->
            generateSequence(fields.parent) { it.parent }.filterIsInstance<ScrollView>().firstOrNull()
        }.firstOrNull()
        val offset = scroll?.scrollY ?: 0
        views.forEach { (group, fields) -> render(group, fields) }
        changed()
        scroll?.post { scroll.scrollTo(0, offset) }
    }
    fun refreshEnabled() {
        if (editable != !TaskRuntime.isActive) { editable = !TaskRuntime.isActive; redraw() }
    }
    fun reloadIfClean() {
        if (!dirty) { baseline = store.config(profileId); draft = JSONObject(baseline.values.toString()); redraw() }
    }
    fun reset() { draft = JSONObject(baseline.values.toString()); redraw() }
    fun save() {
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
    private fun set(field: JSONObject, value: Any) {
        check(!TaskRuntime.isActive) { "请先停止任务再修改参数" }
        catalog.validate(field.getString("key"), value)
        draft.put(field.getString("key"), value); redraw()
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
    private fun render(group: Int, fields: LinearLayout) {
        fields.removeAllViews()
        val groupFields = catalog.groups.getJSONObject(group).getJSONArray("fields")
        for (i in 0 until groupFields.length()) {
            val field = groupFields.getJSONObject(i); val key = field.getString("key")
            if (!shown(key)) continue
            val value = draft.get(key)
            if (fields.childCount > 0) fields.addView(android.view.View(context).apply {
                setBackgroundColor(0x80dae8f1.toInt())
            }, LinearLayout.LayoutParams(-1, UiTheme.dp(context, 1)))
            val line = row().apply {
                minimumHeight = UiTheme.dp(context, 44)
                setPadding(UiTheme.dp(context, 4), UiTheme.dp(context, 4), UiTheme.dp(context, 4), UiTheme.dp(context, 4))
                alpha = if (TaskRuntime.isActive) .55f else 1f
            }
            line.addView(text(field.getString("label"), 12.5f).apply {
                setPadding(0, 0, UiTheme.dp(context, 8), 0); maxLines = 2
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (value is Boolean) {
                val toggle = UiTheme.compactSwitch(context, value, field.getString("label")).apply {
                    background = null; isEnabled = !TaskRuntime.isActive
                    setOnCheckedChangeListener { _, checked -> safely { set(field, checked) } }
                }
                line.addView(toggle, LinearLayout.LayoutParams(UiTheme.dp(context, 42), UiTheme.dp(context, 36)))
                line.isEnabled = !TaskRuntime.isActive
                line.setOnClickListener { toggle.performClick() }
            } else {
                line.addView(text(display(field, value), 11.5f).apply {
                    setTextColor(UiTheme.navy); gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(UiTheme.dp(context, 8), UiTheme.dp(context, 5), UiTheme.dp(context, 8), UiTheme.dp(context, 5))
                    background = UiTheme.background(context, UiTheme.pale, 6).apply {
                        setStroke(UiTheme.dp(context, 1), UiTheme.border)
                    }
                }, LinearLayout.LayoutParams(0, -2, 1f))
                line.addView(UiTheme.chevron(context).apply { alpha = .65f }, LinearLayout.LayoutParams(UiTheme.dp(context, 20), UiTheme.dp(context, 32)))
                line.isClickable = true; line.isFocusable = true; line.isEnabled = !TaskRuntime.isActive
                line.setOnClickListener { safely { edit(field) } }
            }
            line.background = UiTheme.quietRipple(context)
            fields.addView(line, LinearLayout.LayoutParams(-1, -2))
        }
    }
    private fun edit(field: JSONObject) {
        val key = field.getString("key"); val value = draft.get(key)
        when (field.optString("kind")) {
            "shop" -> editShop(field, value as JSONArray)
            "slots", "teams" -> editSlots(field, value as JSONArray)
            else -> {
                val options = field.optJSONArray("options")
                if (options != null && options.length() > 0) {
                    AlertDialog.Builder(context).setTitle(field.getString("label"))
                        .setSingleChoiceItems((0 until options.length()).map { options.getJSONObject(it).getString("label") }.toTypedArray(),
                            (0 until options.length()).firstOrNull { options.getJSONObject(it).getString("value") == value.toString() } ?: -1) { dialog, i ->
                            safely { val choice = options.getJSONObject(i).getString("value"); set(field, if (catalog.defaults.get(key) is Number) choice.toInt() else choice); dialog.dismiss() }
                        }.setNegativeButton("取消", null).show()
                } else editText(field, value)
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
        val panel = column().apply {
            setPadding(UiTheme.dp(context, 16), UiTheme.dp(context, 8), UiTheme.dp(context, 16), UiTheme.dp(context, 4))
            addView(input, LinearLayout.LayoutParams(-1, -2))
        }
        val dialog = AlertDialog.Builder(context).setTitle(field.getString("label")).setView(panel)
            .setNegativeButton("取消", null).setPositiveButton("确定", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                safely {
                    val raw = input.text.toString().trim()
                    val replacement: Any = when (catalog.defaults.get(field.getString("key"))) {
                        is Int, is Long -> raw.toInt()
                        is Number -> raw.toDouble()
                        is JSONArray -> JSONArray(raw.split(',', '，', '\n').map { it.trim() }.filter { it.isNotEmpty() })
                        else -> raw
                    }
                    set(field, replacement); dialog.dismiss()
                }
            }
        }; dialog.show()
    }
    private fun editShop(field: JSONObject, value: JSONArray) {
        val mode = when (store.get(profileId).server) { "日服" -> "JP"; "官服" -> "CN"; else -> "Global" }
        val goods = catalog.static.getJSONObject(field.getString("goods")).getJSONArray(mode)
        val count = maxOf(goods.length(), value.length())
        val checks = BooleanArray(count) { value.optInt(it) == 1 }
        val labels = (0 until count).map { i ->
            goods.optJSONArray(i)?.let { "${it.getString(0)} · ${it.get(1)}" } ?: "商品 ${i + 1}"
        }.toTypedArray()
        AlertDialog.Builder(context).setTitle(field.getString("label"))
            .setMultiChoiceItems(labels, checks) { _, i, checked -> checks[i] = checked }
            .setNegativeButton("取消", null).setPositiveButton("确定") { _, _ -> safely { set(field, JSONArray(checks.map { if (it) 1 else 0 })) } }.show()
    }
    private fun editSlots(field: JSONObject, value: JSONArray) {
        val copy = JSONArray(value.toString()); val body = column()
        val teams = field.optString("kind") == "teams"
        val slots = mutableListOf<EditText>()
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
                    val input = UiTheme.input(context).apply { inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine(true); setText(values.get(0).toString()) }
                    slots += input; controls.item(input, 1f)
                }
            }
            body.item(controls)
        }
        val panel = column().apply {
            setPadding(UiTheme.dp(context, 12), UiTheme.dp(context, 4), UiTheme.dp(context, 12), UiTheme.dp(context, 4))
            addView(ScrollView(context).apply { addView(body) }, LinearLayout.LayoutParams(-1, UiTheme.dp(context, 280)))
        }
        val dialog = AlertDialog.Builder(context).setTitle(field.getString("label"))
            .setView(panel).setNegativeButton("取消", null).setPositiveButton("确定", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            safely { if (!teams) slots.forEachIndexed { i, input -> copy.put(i, input.text.toString().toInt()) }; set(field, copy); dialog.dismiss() }
        } }; dialog.show()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
    private fun safely(action: () -> Unit) {
        try { action() } catch (error: Exception) { AlertDialog.Builder(context).setMessage(error.message ?: "配置操作失败").setPositiveButton("确定", null).show() }
    }
}
