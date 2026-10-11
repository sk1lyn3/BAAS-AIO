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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** LogFilters search box: magnifier, 11sp input, clear button. */
@Composable
fun LogSearch(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Row(modifier.field(focused), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp, 32.dp), contentAlignment = Alignment.Center) { Image(painterResource(R.drawable.ic_search), null) }
        BInput(query, onQuery, Modifier.weight(1f).height(32.dp).onFocusChanged { focused = it.isFocused }, hint = "搜索日志", size = 11f, framed = false,
            keyboard = KeyboardOptions(imeAction = ImeAction.Search), actions = KeyboardActions(onSearch = { keyboard?.hide(); focus.clearFocus() }))
        if (query.isNotEmpty()) Ui.Quiet {
            Box(Modifier.size(24.dp, 32.dp).clickable { onQuery("") }.semantics { contentDescription = "清除搜索" }, contentAlignment = Alignment.Center) {
                BText("×", 18f, Ui.Muted, padding = NoPadding)
            }
        }
    }
}

private val levelColors = listOf(Ui.Blue, Color(0xFF2563EB), Color(0xFFB45309), Color(0xFFDC2626), Color(0xFF9333EA))
private val levelNames = listOf("全部日志", "INFO 信息", "WARNING 警告", "ERROR 错误", "CRITICAL 严重错误")

@Composable
fun LogLevels(level: Int, modifier: Modifier = Modifier, change: (Int) -> Unit) =
    Segments(listOf("全部", "INFO", "WARN", "ERROR", "CRIT"), level, modifier, textSize = 10f, height = 32.dp,
        colors = levelColors, descriptions = levelNames, change = change)

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

    @Composable
    fun Track(value: String, query: String, level: Int) {
        val context = LocalContext.current.applicationContext
        LaunchedEffect(value, query, level) {
            val request = Triple(value, query, level)
            if (request == rendered && value != "[]") { renders++; return@LaunchedEffect }
            delay(maxOf(0L, 200L - (SystemClock.uptimeMillis() - lastRenderAt)))
            if (request != rendered || value == "[]") {
                val result = withContext(Dispatchers.Default) {
                    var entries = JSONArray(value)
                    if (entries.length() == 0) {
                        val report = withContext(Dispatchers.IO) {
                            File(context.filesDir, "task-report.json").takeIf { it.isFile }
                                ?.let { runCatching { it.readText() }.getOrNull() }
                        }
                        entries = report?.let { runCatching { JSONObject(it).optJSONArray("log") }.getOrNull() } ?: entries
                    }
                    render(entries, query, level)
                }
                count = "${result.third}/${result.second} 条"
                text = result.first
                rendered = request
            }
            lastRenderAt = SystemClock.uptimeMillis()
            renders++
        }
    }

    companion object {
        private val labels = arrayOf("LOG", "INFO", "WARNING", "ERROR", "CRITICAL")
        private val colors = listOf(Ui.Muted, Color(0xFF60A5FA), Color(0xFFF59E0B), Color(0xFFF87171), Color(0xFFC084FC))
        private fun render(entries: JSONArray, query: String, filter: Int): Triple<AnnotatedString, Int, Int> {
            var count = 0
            val output = buildAnnotatedString {
                for (i in 0 until entries.length()) {
                    val entry = entries.optJSONObject(i) ?: continue
                    val level = entry.optInt("level", 1).coerceIn(0, 4)
                    val message = entry.optString("message")
                    if ((filter != 0 && level != filter) || !message.contains(query, ignoreCase = true)) continue
                    if (count++ > 0) append("\n")
                    withStyle(SpanStyle(color = colors[level])) { append("[${labels[level]}] ") }
                    append(message)
                }
            }
            val text = if (output.isEmpty()) AnnotatedString(if (query.isBlank() && filter == 0) "暂无日志" else "没有匹配的日志") else output
            return Triple(text, entries.length(), count)
        }
    }
}

@Composable
fun LogCount(model: LogModel, modifier: Modifier = Modifier) =
    BText(model.count, 10f, Ui.Muted, modifier, align = TextAlign.End)

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
