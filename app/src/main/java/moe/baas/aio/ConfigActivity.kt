package moe.baas.aio

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.*
import moe.baas.aio.UiTheme.item
import org.json.JSONArray
import org.json.JSONObject

/** A configuration draft; saving patches the original file consumed by Python core. */
class ConfigActivity : Activity() {
    private val store by lazy { ProfileStore(this) }
    private val catalog by lazy { ConfigCatalog(this) }
    private lateinit var profileId: String
    private lateinit var baseline: ProfileStore.ConfigSnapshot
    private lateinit var draft: JSONObject
    private lateinit var fields: LinearLayout
    private lateinit var save: Button
    private lateinit var groupButton: Button
    private var group = 0
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun text(value: String, size: Float = 14f) = UiTheme.text(this, value, size)
    private fun button(label: String, primary: Boolean = false, action: () -> Unit) = UiTheme.button(this, label, primary) { safely(action) }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        try {
            profileId = state?.getString("profileId") ?: intent.getStringExtra("profileId") ?: store.selected().id
            baseline = store.config(profileId)
            if (state != null) baseline = ProfileStore.ConfigSnapshot(state.getString("baseline")!!, JSONObject(state.getString("original")!!))
            draft = JSONObject(state?.getString("draft") ?: baseline.values.toString())
            group = state?.getInt("group") ?: intent.getIntExtra("group", 0)
            require(group in 0 until catalog.groups.length())
        } catch (error: Exception) {
            AlertDialog.Builder(this).setMessage(error.message).setPositiveButton("返回") { _, _ -> finish() }.show(); return
        }
        val root = column().apply {
            setBackgroundColor(UiTheme.pale)
            val p = UiTheme.dp(this@ConfigActivity, 12); setPadding(p, p, p, p)
        }
        root.item(row().apply {
            item(text("任务参数", 20f).apply { setTypeface(null, Typeface.BOLD) }, 1f)
            item(button("返回") { leave() }, 1f)
        })
        root.item(text(store.get(profileId).name, 12f).apply { setTextColor(UiTheme.muted) })
        groupButton = button(catalog.groups.getJSONObject(group).getString("title")) {
            AlertDialog.Builder(this).setTitle("任务参数")
                .setItems((0 until catalog.groups.length()).map { catalog.groups.getJSONObject(it).getString("title") }.toTypedArray()) { _, i ->
                    group = i; render(); groupButton.text = catalog.groups.getJSONObject(group).getString("title")
                }.show()
        }; root.item(groupButton)
        fields = column()
        root.addView(ScrollView(this).apply { addView(fields) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.item(row().apply {
            item(button("取消") { leave() }, 1f)
            item(button("撤销修改") {
                draft = JSONObject(this@ConfigActivity.baseline.values.toString()); render()
            }, 1f)
            save = button("保存", true) {
                store.saveConfig(profileId, this@ConfigActivity.baseline, changes())
                Toast.makeText(this@ConfigActivity, "已保存", Toast.LENGTH_SHORT).show(); finish()
            }; item(save, 1f)
        })
        setContentView(root); render()
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
        draft.put(field.getString("key"), value); render()
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
    private fun render() {
        fields.removeAllViews()
        val groupFields = catalog.groups.getJSONObject(group).getJSONArray("fields")
        for (i in 0 until groupFields.length()) {
            val field = groupFields.getJSONObject(i); val key = field.getString("key")
            if (!shown(key)) continue
            val value = draft.get(key)
            if (value is Boolean) fields.item(Switch(this).apply {
                text = field.getString("label"); textSize = 14f; setTextColor(UiTheme.navy)
                minHeight = UiTheme.dp(this@ConfigActivity, 48); isChecked = value; isEnabled = !TaskRuntime.isActive
                thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(UiTheme.blue, UiTheme.muted))
                trackTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(0x55008fd3, 0x33657a8b))
                setOnCheckedChangeListener { _, checked -> safely { set(field, checked) } }
            }) else fields.item(UiTheme.card(this).apply {
                minimumHeight = UiTheme.dp(this@ConfigActivity, 56)
                item(row().apply {
                    item(text(field.getString("label")), 1f)
                    item(text(display(field, value), 12f).apply {
                        setTextColor(UiTheme.blue); gravity = Gravity.END
                        maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                    }, 1f)
                })
                isClickable = true; isFocusable = true; isEnabled = !TaskRuntime.isActive
                setOnClickListener { safely { edit(field) } }
            })
        }
        save.isEnabled = !TaskRuntime.isActive && changes().length() > 0
    }
    private fun edit(field: JSONObject) {
        val key = field.getString("key"); val value = draft.get(key)
        when (field.optString("kind")) {
            "shop" -> editShop(field, value as JSONArray)
            "slots", "teams" -> editSlots(field, value as JSONArray)
            else -> {
                val options = field.optJSONArray("options")
                if (options != null && options.length() > 0) {
                    AlertDialog.Builder(this).setTitle(field.getString("label"))
                        .setSingleChoiceItems((0 until options.length()).map { options.getJSONObject(it).getString("label") }.toTypedArray(),
                            (0 until options.length()).firstOrNull { options.getJSONObject(it).getString("value") == value.toString() } ?: -1) { dialog, i ->
                            safely { val choice = options.getJSONObject(i).getString("value"); set(field, if (catalog.defaults.get(key) is Number) choice.toInt() else choice); dialog.dismiss() }
                        }.setNegativeButton("取消", null).show()
                } else editText(field, value)
            }
        }
    }
    private fun editText(field: JSONObject, value: Any) {
        val input = EditText(this).apply {
            setText(if (value is JSONArray) (0 until value.length()).joinToString(", ") { value.getString(it) } else value.toString())
            if (field.optBoolean("numeric") || value is Number) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
            else { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; maxLines = 4 }
        }
        val dialog = AlertDialog.Builder(this).setTitle(field.getString("label")).setView(input)
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
        AlertDialog.Builder(this).setTitle(field.getString("label"))
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
                    val spinner = Spinner(this).apply {
                        adapter = ArrayAdapter(this@ConfigActivity, android.R.layout.simple_spinner_item,
                            (0 until choices.length()).map { choices.getJSONObject(it).getString("label") }).apply {
                            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                        }
                        minimumHeight = UiTheme.dp(this@ConfigActivity, 48)
                        dropDownWidth = UiTheme.dp(this@ConfigActivity, 160)
                        setSelection((0 until choices.length()).firstOrNull { choices.getJSONObject(it).getString("value") == values.getString(c) } ?: choices.length() - 1)
                    }
                    controls.item(spinner, 1f)
                    spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) { values.put(c, choices.getJSONObject(position).getString("value")) }
                        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                    }
                } else {
                    val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine(true); setText(values.get(0).toString()) }
                    slots += input; controls.item(input, 1f)
                }
            }
            body.item(controls)
        }
        val panel = column().apply {
            addView(ScrollView(this@ConfigActivity).apply { addView(body) }, LinearLayout.LayoutParams(-1, UiTheme.dp(this@ConfigActivity, 280)))
        }
        val dialog = AlertDialog.Builder(this).setTitle(field.getString("label"))
            .setView(panel).setNegativeButton("取消", null).setPositiveButton("确定", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            safely { if (!teams) slots.forEachIndexed { i, input -> copy.put(i, input.text.toString().toInt()) }; set(field, copy); dialog.dismiss() }
        } }; dialog.show()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
    private fun leave() {
        if (!::draft.isInitialized || changes().length() == 0) finish()
        else AlertDialog.Builder(this).setMessage("放弃未保存的修改？").setNegativeButton("继续编辑", null).setPositiveButton("放弃") { _, _ -> finish() }.show()
    }
    @Deprecated("Platform back API") override fun onBackPressed() = leave()
    override fun onSaveInstanceState(state: Bundle) {
        if (::draft.isInitialized) {
            state.putString("profileId", profileId); state.putInt("group", group)
            state.putString("baseline", baseline.raw); state.putString("original", baseline.values.toString()); state.putString("draft", draft.toString())
        }; super.onSaveInstanceState(state)
    }
    private fun safely(action: () -> Unit) {
        try { action() } catch (error: Exception) { AlertDialog.Builder(this).setMessage(error.message ?: "配置操作失败").setPositiveButton("确定", null).show() }
    }
}
