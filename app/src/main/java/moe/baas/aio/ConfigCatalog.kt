package moe.baas.aio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** UI field names and choices follow WebUI; values retain upstream config.json types. */
class ConfigCatalog(context: Context) {
    val groups = context.assets.open("config-catalog.json").bufferedReader().use { JSONArray(it.readText()) }
    val defaults = context.assets.open("profile-defaults.json").bufferedReader().use { JSONObject(it.readText()).getJSONObject("config") }
    val static = context.assets.open("static-config.json").bufferedReader().use { JSONObject(it.readText()) }
    fun fields(): List<JSONObject> = (0 until groups.length()).flatMap { i ->
        val fields = groups.getJSONObject(i).getJSONArray("fields")
        (0 until fields.length()).map { fields.getJSONObject(it) }
    }
    fun validate(key: String, value: Any) {
        val field = fields().firstOrNull { it.getString("key") == key } ?: error("Unknown configuration field: $key")
        val original = defaults.get(key)
        require(when (original) {
            is Boolean -> value is Boolean
            is Number -> value is Number && value.toDouble().isFinite()
            is String -> value is String
            is JSONArray -> value is JSONArray
            else -> false
        }) { "${field.getString("label")}：格式不正确" }
        val choices = field.optJSONArray("options")
        fun choice(item: Any) {
            if (choices != null && choices.length() > 0) require((0 until choices.length()).any {
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
}
