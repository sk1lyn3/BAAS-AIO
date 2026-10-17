package moe.baas.aio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * UI field names and choices follow WebUI; values retain upstream config.json types. Each core ships its own catalog.
 * Default keys in no group and not "hidden" are shown under 其他参数, labelled by "other" or by their key.
 * "tasks" gives scheduled tasks the settings section their 执行参数 opens and a tag.
 */
class ConfigCatalog(catalogText: String, defaultsText: String, staticText: String) {
    constructor(context: Context) : this(CoreData.read(context, "config-catalog.json"),
        CoreData.read(context, "profile-defaults.json"), CoreData.read(context, "static-config.json"))
    private val root = JSONObject(catalogText)
    val groups: JSONArray = root.getJSONArray("groups")
    val defaults: JSONObject = JSONObject(defaultsText).getJSONObject("config")
    val static = JSONObject(staticText)
    private val tasks = root.getJSONObject("tasks")
    /** Section id of 其他参数, after the groups, 活动图 and 其他功能. */
    val otherSection get() = groups.length() + 2
    fun fields(): List<JSONObject> = (0 until groups.length()).flatMap { i ->
        val fields = groups.getJSONObject(i).getJSONArray("fields")
        (0 until fields.length()).map { fields.getJSONObject(it) }
    }
    /** Fields for 其他参数: labelled keys first, then the rest by name. Nested lists and objects are read-only. */
    val otherFields: List<JSONObject> by lazy {
        val hidden = root.getJSONArray("hidden").let { list -> (0 until list.length()).map { list.getString(it) } }.toSet()
        val other = root.getJSONObject("other")
        val grouped = fields().map { it.getString("key") }.toSet()
        val keys = defaults.keys().asSequence().filter { it !in grouped && it !in hidden }.toList()
        val labelled = other.keys().asSequence().filter { it in keys }.toList()
        (labelled + (keys - labelled.toSet()).sorted()).map { key ->
            val spec = other.optJSONObject(key)
            val value = defaults.get(key)
            val strings = value is JSONArray && (spec?.optString("kind") == "list" || value.length() > 0 && (0 until value.length()).all { value.get(it) is String })
            JSONObject().put("key", key).put("label", spec?.getString("label") ?: key).put("options", JSONArray())
                .put("min", JSONObject.NULL).put("max", JSONObject.NULL).put("readOnly", value is JSONObject || value is JSONArray && !strings)
        }
    }

    /** Section 执行参数 opens for [task], or null when the catalog gives it none. */
    fun section(task: String): Int? = when (val group = tasks.optJSONObject(task)?.optString("group").orEmpty()) {
        "" -> null
        OTHER -> otherSection.takeIf { otherFields.isNotEmpty() }
        else -> (0 until groups.length()).firstOrNull { groups.getJSONObject(it).getString("title") == group }
    }
    fun tag(task: String): String? = tasks.optJSONObject(task)?.optString("tag")?.ifEmpty { null }

    companion object {
        const val OTHER = "其他参数"
        /** Upstream defaults these to "" but core iterates them as lists (Baas_thread.update_create_priority). */
        fun isListText(key: String) = key.startsWith("createPriority_phase")
        fun splitList(raw: String) = JSONArray(raw.split(',', '，', '\n').map { it.trim() }.filter { it.isNotEmpty() })
    }
    /** [saved] is the profile's current value: option values in it stay valid even if this catalog no longer lists them. */
    fun validate(key: String, value: Any, saved: Any? = null) {
        val field = (fields() + otherFields).firstOrNull { it.getString("key") == key } ?: error("Unknown configuration field: $key")
        val original = defaults.get(key)
        require(when (original) {
            is Boolean -> value is Boolean
            is Number -> value is Number && value.toDouble().isFinite()
            is String -> value is String || (isListText(key) && value is JSONArray)
            is JSONArray -> value is JSONArray
            else -> false
        }) { "${field.getString("label")}：格式不正确" }
        val kept = HashSet<String>()
        fun keep(item: Any?) { if (item is JSONArray) for (i in 0 until item.length()) keep(item.get(i)) else if (item != null) kept += item.toString() }
        keep(saved)
        val choices = field.optJSONArray("options")
        fun choice(item: Any) {
            if (choices != null && choices.length() > 0) require(item.toString() in kept || (0 until choices.length()).any {
                choices.getJSONObject(it).getString("value") == item.toString()
            }) { "${field.getString("label")}：无效选项" }
        }
        fun number(item: Any) {
            val n = item.toString().toDoubleOrNull()
            require(n != null && n.isFinite()) { "${field.getString("label")}：请输入数字" }
            if (!field.isNull("min")) require(n >= field.getDouble("min")) { "最小值为 ${field.get("min")}" }
            if (!field.isNull("max")) require(n <= field.getDouble("max")) { "最大值为 ${field.get("max")}" }
            if (original is Int || original is Long || field.optString("kind") == "slots") require(n % 1 == 0.0) { "请输入整数" }
        }
        if (value is JSONArray) {
            require(value.length() <= 256) { "最多 256 项" }
            for (i in 0 until value.length()) {
                val item = value.get(i)
                when (field.optString("kind")) {
                    "shop" -> require(item is Number && item.toDouble() in listOf(0.0, 1.0)) { "商品选择格式不正确" }
                    "slots" -> { require(item is Number); number(item); choice(item) }
                    "teams" -> {
                        require(item is JSONArray && item.length() == if (key == "side_team_attribute") 4 else 5) { "编队格式不正确" }
                        for (n in 0 until item.length()) choice(item.get(n))
                    }
                    else -> require(item is String && item.length <= 200) { "请输入学生名称" }
                }
            }
        } else {
            choice(value)
            if (value is Number || field.optBoolean("numeric") || !field.isNull("min") || !field.isNull("max")) {
                if (!(original == "" && value == "")) number(value)
            }
            if (value is String) require(value.length <= 10000) { "配置内容过长" }
        }
    }

    /** Install-time check that the catalog fits the defaults and static tables it ships with. */
    fun check() {
        for (field in fields()) {
            val key = field.getString("key")
            require(defaults.has(key)) { "设置项 $key 没有默认值" }
            if (field.optString("kind") == "shop") {
                val goods = field.getString("goods")
                require(GameServer.MODES.all { static.optJSONObject(goods)?.optJSONArray(it) != null }) { "缺少商店数据 $goods" }
            }
            try { validate(key, defaults.get(key)) } catch (_: IllegalArgumentException) { throw IllegalArgumentException("设置项 $key 的默认值无效") }
        }
    }
}
