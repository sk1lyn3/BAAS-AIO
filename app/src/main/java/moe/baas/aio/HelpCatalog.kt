package moe.baas.aio

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
 * Centralized help text keyed by config key (the `key` in assets/config-catalog.json).
 * To add help to a settings entry: add one `"key" to HelpEntry(...)` line below and the key to [keys]. ConfigForm shows the ⓘ automatically.
 * For any other Compose label, place `HelpTip("key")` right after the label; from View code call `HelpDialog.show(context, "key")`.
 * Only add help for entries with real rules (formats, priority/order, option interplay, caveats); obvious entries get none.
 */
object HelpCatalog {
    /** Built on first dialog open, not when a settings page first shows its ⓘ marks. */
    private val entries: Map<String, HelpEntry> by lazy { emptyMap() }
    /** Keys that have an entry; cheap, so ConfigForm can decide on ⓘ without building any entry. */
    val keys: Set<String> = emptySet()
    fun has(key: String) = key in keys
    operator fun get(key: String) = entries[key]
}
