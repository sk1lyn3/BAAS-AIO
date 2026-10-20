package moe.baas.aio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One help page: [summary] first, then titled [sections] (bullet lines), two-column [tables], monospace [examples]. Must stand alone (no external docs). */
data class HelpWideTable(
    val title: String,
    val headers: List<String>,
    val rows: List<List<String>>,
    /** true: starts folded; tap the title row to expand / collapse. */
    val collapsed: Boolean = false,
)

data class HelpEntry(
    val title: String,
    val summary: String,
    val sections: List<Pair<String, List<String>>> = emptyList(),
    val examples: List<Pair<String, String>> = emptyList(),
    /** Optional two-column tables: title to (left, right) rows, e.g. node name -> yield. */
    val tables: List<Pair<String, List<Pair<String, String>>>> = emptyList(),
    /** Optional plain-text blocks at the very bottom (title to verbatim text), for long-press copying. */
    val texts: List<Pair<String, String>> = emptyList(),
    /** Optional wide tables rendered in a horizontal ScrollView (swipe left/right). */
    val wideTables: List<HelpWideTable> = emptyList(),
)

/**
 * Help pages keyed by config key, read from help-catalog.json of the active core (see docs/help-catalog.md).
 * A downloaded file that is unreadable or has an unknown format falls back to the copy in the APK.
 * For any Compose label, place `HelpTip("key")` right after it; from View code call `HelpDialog.show(context, "key")`.
 */
object HelpCatalog {
    const val FORMAT = 1
    const val FILE = "help-catalog.json"
    @Volatile private var entries: Map<String, HelpEntry>? = null

    private fun entries(context: Context): Map<String, HelpEntry> = entries ?: synchronized(this) {
        entries ?: (try { parse(CoreData.read(context, FILE)) }
            catch (_: Exception) { parse(context.assets.open(FILE).bufferedReader().use { it.readText() }) })
            .also { entries = it }
    }

    fun has(context: Context, key: String) = key in entries(context)
    operator fun get(context: Context, key: String) = entries(context)[key]

    /** Parses and validates the whole file; throws on an unknown format or a malformed entry. */
    fun parse(text: String): Map<String, HelpEntry> {
        val root = JSONObject(text)
        require(root.getInt("format") == FORMAT) { "帮助文本格式不受支持" }
        val list = root.getJSONObject("entries")
        return list.keys().asSequence().associateWith { key ->
            try { entry(list.getJSONObject(key)) } catch (error: Exception) { throw IllegalArgumentException("帮助 $key 有误：${error.message}") }
        }
    }

    private fun entry(o: JSONObject) = HelpEntry(
        title = o.getString("title").also { require(it.isNotBlank()) { "缺少标题" } },
        summary = o.getString("summary"),
        sections = objects(o, "sections").map { it.getString("title") to strings(it.getJSONArray("lines")) },
        examples = objects(o, "examples").map { it.getString("code") to it.getString("meaning") },
        tables = objects(o, "tables").map { table ->
            table.getString("title") to arrays(table.getJSONArray("rows")).map { row ->
                require(row.size == 2) { "表格每行需要两列" }; row[0] to row[1]
            }
        },
        texts = objects(o, "texts").map { it.getString("title") to it.getString("text") },
        wideTables = objects(o, "wideTables").map { HelpWideTable(it.getString("title"), strings(it.getJSONArray("headers")),
            arrays(it.getJSONArray("rows")), it.optBoolean("collapsed")) },
    )

    private fun objects(o: JSONObject, name: String) = o.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
    private fun strings(a: JSONArray) = (0 until a.length()).map { a.getString(it) }
    private fun arrays(a: JSONArray) = (0 until a.length()).map { strings(a.getJSONArray(it)) }
}
