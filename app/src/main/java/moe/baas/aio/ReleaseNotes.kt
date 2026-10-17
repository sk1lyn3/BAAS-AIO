package moe.baas.aio

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.BulletSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan

/** Small Markdown subset used by release notes; rendered as native, theme-aware text. */
object ReleaseNotes {
    private val heading = Regex("^(#{1,6})\\s+(.+)$")
    private val bullet = Regex("^\\s*[-*+]\\s+(.+)$")
    private val inline = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`|\\[([^]]+)]\\((https?://[^\\s)]+)\\)")

    fun view(context: Context, markdown: String) = UiTheme.text(context, "", 13f).apply {
        val output = SpannableStringBuilder()
        fun span(value: Any, start: Int, end: Int) {
            if (start < end) output.setSpan(value, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        fun appendInline(value: String) {
            var cursor = 0
            for (match in inline.findAll(value)) {
                output.append(value, cursor, match.range.first)
                val start = output.length
                val bold = match.groups[1]?.value
                val code = match.groups[2]?.value
                val link = match.groups[3]?.value
                output.append(bold ?: code ?: link.orEmpty())
                when {
                    bold != null -> span(StyleSpan(Typeface.BOLD), start, output.length)
                    code != null -> {
                        span(TypefaceSpan("monospace"), start, output.length)
                        span(BackgroundColorSpan(UiTheme.pale), start, output.length)
                    }
                    link != null -> span(URLSpan(match.groupValues[4]), start, output.length)
                }
                cursor = match.range.last + 1
            }
            output.append(value, cursor, value.length)
        }
        markdown.replace("\r\n", "\n").trim().lines().forEachIndexed { index, line ->
            if (index > 0) output.append('\n')
            val title = heading.matchEntire(line)
            val item = bullet.matchEntire(line)
            val start = output.length
            appendInline(title?.groupValues?.get(2) ?: item?.groupValues?.get(1) ?: line)
            if (title != null) {
                span(StyleSpan(Typeface.BOLD), start, output.length)
                span(RelativeSizeSpan(if (title.groupValues[1].length == 1) 1.25f else 1.12f), start, output.length)
                span(ForegroundColorSpan(UiTheme.blue), start, output.length)
            } else if (item != null) {
                span(BulletSpan(UiTheme.dp(context, 8), UiTheme.muted, UiTheme.dp(context, 2)), start, output.length)
            }
        }
        text = output
        setLineSpacing(0f, 1.25f)
        setLinkTextColor(UiTheme.blue)
        movementMethod = LinkMovementMethod.getInstance()
    }
}
