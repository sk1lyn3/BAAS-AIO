package moe.baas.aio

import androidx.compose.foundation.border

import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.draw.clip

/** LogFilters search box: magnifier, 11sp input, clear button. */
@Composable
fun LogSearch(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Row(modifier.field(focused), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp, 32.dp), contentAlignment = Alignment.Center) { Image(painterResource(R.drawable.ic_search), null) }
        BInput(query, onQuery, Modifier.weight(1f).height(32.dp).onFocusChanged { focused = it.isFocused }, hint = context.getString(R.string.log_search), size = 11f, framed = false,
            keyboard = KeyboardOptions(imeAction = ImeAction.Search), actions = KeyboardActions(onSearch = { keyboard?.hide(); focus.clearFocus() }))
        if (query.isNotEmpty()) Ui.Quiet {
            Box(Modifier.size(24.dp, 32.dp).clickable { onQuery("") }.semantics { contentDescription = context.getString(R.string.log_clear_search) }, contentAlignment = Alignment.Center) {
                BText("×", 18f, Ui.Muted, padding = NoPadding)
            }
        }
    }
}

private val levelColors = listOf(Ui.Blue, Color(0xFF2563EB), Color(0xFFB45309), Color(0xFFDC2626), Color(0xFF9333EA))
private val levelNames = listOf(R.string.log_level_all, R.string.log_level_info, R.string.log_level_warning, R.string.log_level_error, R.string.log_level_critical)

@Composable
fun LogLevels(level: Int, modifier: Modifier = Modifier, change: (Int) -> Unit) =
    Segments(listOf(stringResource(R.string.filter_all), "INFO", "WARN", "ERROR", "CRIT"), level, modifier, textSize = 10f, height = 32.dp,
        colors = levelColors, descriptions = levelNames.map { stringResource(it) }, change = change)

/** LogOutput + UiLogs: throttled (200ms) rendering, follow-to-bottom, report fallback. */
class LogModel {
    var text by mutableStateOf<AnnotatedString>(AnnotatedString(""))
        private set
    var count by mutableStateOf("")
        private set
    var renders by mutableLongStateOf(0)
        private set
    private var rendered: Triple<String, String, Int>? = null
    private var lastRenderAt = 0L
    private var window: TaskLog.Window? = null
    private var windowFilter: Pair<String, Int>? = null

    @Composable
    fun Track(value: String, query: String, level: Int) {
        val context = LocalContext.current.applicationContext
        val latest by rememberUpdatedState(Triple(value, query, level))
        LaunchedEffect(Unit) {
            // Conflate live updates, but let an in-flight archive read finish instead of restarting it per line.
            snapshotFlow { latest }.conflate().collect { request ->
                val (live, search, filter) = request
                val emptyText = context.getString(if (search.isBlank() && filter == 0) R.string.log_empty else R.string.log_no_matches)
                if (request == rendered) return@collect
                delay(maxOf(0L, 200L - (SystemClock.uptimeMillis() - lastRenderAt)))
                val previous = window
                val previousFilter = windowFilter
                val loaded = withContext(Dispatchers.IO) {
                    TaskLog.attach(context.filesDir)
                    val entries = JSONArray(live)
                    val first = entries.optJSONObject(0)?.optLong("sequence") ?: 0L
                    val base = if (previous == null || previousFilter != (search to filter) || first > previous.sequence + 1)
                        runCatching { TaskLog.tail(search, filter) }.getOrElse { TaskLog.Window(emptyList(), 0) }
                    else previous
                    val tail = ArrayDeque(base.entries)
                    var sequence = base.sequence
                    for (i in 0 until entries.length()) {
                        val entry = entries.getJSONObject(i)
                        val next = entry.optLong("sequence")
                        if (next <= base.sequence) continue
                        sequence = maxOf(sequence, next)
                        if (TaskLog.matches(entry, search, filter)) tail.addLast(entry)
                        while (tail.size > TaskLog.WINDOW) tail.removeFirst()
                    }
                    if (tail.isEmpty() && sequence == 0L && entries.length() == 0) {
                        // Older APKs have a last-run report but no journal yet.
                        val report = File(context.filesDir, "task-report.json").takeIf { it.isFile }
                            ?.let { runCatching { JSONObject(it.readText()).optJSONArray("log") }.getOrNull() }
                        if (report != null) for (i in 0 until report.length()) {
                            val entry = report.getJSONObject(i)
                            if (TaskLog.matches(entry, search, filter)) tail.addLast(entry)
                            while (tail.size > TaskLog.WINDOW) tail.removeFirst()
                        }
                    }
                    TaskLog.Window(tail.toList(), sequence)
                }
                val result = withContext(Dispatchers.Default) { render(JSONArray(loaded.entries), emptyText) }
                if (latest.second != search || latest.third != filter) return@collect
                window = loaded; windowFilter = search to filter
                count = context.getString(R.string.log_window_count, result.second)
                if (text != result.first) { text = result.first; renders++ }
                rendered = request
                lastRenderAt = SystemClock.uptimeMillis()
            }
        }
    }

    companion object {
        private val labels = arrayOf("LOG", "INFO", "WARNING", "ERROR", "CRITICAL")
        private val colors = listOf(Ui.Muted, Color(0xFF60A5FA), Color(0xFFF59E0B), Color(0xFFF87171), Color(0xFFC084FC))
        private fun render(entries: JSONArray, emptyText: String): Pair<AnnotatedString, Int> {
            var count = 0
            val output = buildAnnotatedString {
                for (i in 0 until entries.length()) {
                    val entry = entries.optJSONObject(i) ?: continue
                    val level = entry.optInt("level", 1).coerceIn(0, 4)
                    val message = entry.optString("message")
                    if (count++ > 0) append("\n")
                    withStyle(SpanStyle(color = colors[level])) { append("[${labels[level]}] ") }
                    append(message)
                }
            }
            val text = if (output.isEmpty()) AnnotatedString(emptyText) else output
            return text to count
        }
    }
}

@Composable
fun LogCount(model: LogModel, modifier: Modifier = Modifier) =
    BText(model.count, 10f, Ui.Muted, modifier, align = TextAlign.End)

@Composable
fun LogExportButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current as? android.app.Activity ?: return
    val scope = rememberCoroutineScope()
    val label = stringResource(R.string.log_export)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) { uri ->
        if (uri != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    TaskLog.attach(context.filesDir)
                    context.contentResolver.openOutputStream(uri, "wt").use { TaskLog.exportTo(requireNotNull(it)) }
                }
                UiNotice.show(context, context.getString(R.string.log_exported))
            } catch (error: Exception) {
                UiNotice.show(context, error.message ?: context.getString(R.string.error_operation_failed), error = true)
            }
        }
    }
    Box(modifier.background(Ui.Card, RoundedCornerShape(10.dp)).border(1.dp, Ui.Border, RoundedCornerShape(10.dp))
        .clip(RoundedCornerShape(8.dp)).clickable {
            launcher.launch("BAAS-logs-${java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.jsonl")
        }.semantics { contentDescription = label }.padding(5.dp), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.ic_download), null, colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Ui.Blue))
    }
}

/** Dark rounded scroll area; monospace 12sp, line spacing +3dp × 1.15, selectable. */
@Composable
fun LogOutput(model: LogModel, follows: Boolean, padding: Dp, modifier: Modifier = Modifier) {
    // Start at the bottom when following, so reopening the tab never shows the top first.
    val scroll = rememberScrollState(if (follows) Int.MAX_VALUE else 0)
    LaunchedEffect(model.renders, follows) {
        if (!follows) return@LaunchedEffect
        withFrameNanos { }
        scroll.scrollTo(scroll.maxValue)
    }
    Box(modifier.softShadow(6.dp, RoundedCornerShape(16.dp), 0.18f).background(Color(if (Ui.night) 0xFF060C17 else 0xFF0F172A), RoundedCornerShape(16.dp))
        .border(1.dp, Ui.Border.copy(alpha = if (Ui.night) 1f else 0f), RoundedCornerShape(16.dp)).padding(padding).fadingScrollbar(scroll).verticalScroll(scroll)) {
        SelectionContainer {
            androidx.compose.material3.Text(model.text, Modifier.fillMaxWidth(),
                style = textStyle(12f, Color(0xFFCBD5E1), family = FontFamily.Monospace).copy(lineHeight = 19.2.sp))
        }
    }
}
