package moe.baas.aio

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Display the latest core-owned resource readings; timestamps remain in config.json. */
class ProfileResourcesView(context: Context, private val profile: ProfileStore.Profile) : LinearLayout(context) {
    private val counts = NumberFormat.getIntegerInstance(Locale.CHINA)
    private val scannedAt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    private val ap: TextView
    private val credits: TextView
    private val gems: TextView

    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(UiTheme.text(context, profile.server, 11f, UiTheme.muted))
        ap = resource(R.drawable.currency_icon_ap)
        credits = resource(R.drawable.currency_icon_gold)
        gems = resource(R.drawable.currency_icon_gem)
    }

    fun showResources(show: Boolean) {
        listOf(ap, credits, gems).forEach { it.visibility = if (show) View.VISIBLE else View.GONE }
        if (show) refresh()
    }

    private fun resource(icon: Int): TextView = UiTheme.text(context, "—", 11f, UiTheme.muted).apply {
        gravity = Gravity.CENTER_VERTICAL; maxLines = 1
        val drawable = context.getDrawable(icon)!!.mutate().apply {
            setBounds(0, 0, UiTheme.dp(context, 24), UiTheme.dp(context, 20))
        }
        setCompoundDrawables(drawable, null, null, null)
        compoundDrawablePadding = UiTheme.dp(context, 2)
        addView(this, LayoutParams(-2, -2).apply { leftMargin = UiTheme.dp(context, 6) })
    }

    fun refresh() {
        if (ap.visibility != View.VISIBLE) return
        // Upstream writes config.json in place; keep the last display if a read catches
        // an incomplete write instead of briefly clearing values or disrupting a task.
        val config = runCatching { JSONObject(File(profile.path, "config.json").readText()) }.getOrNull() ?: return
        update(ap, config.optJSONObject("ap"), "体力") { count, data ->
            val max = data.optLong("max", -1)
            if (max > 0) "$count/$max" else count.toString()
        }
        update(credits, config.optJSONObject("creditpoints"), "信用点") { count, _ ->
            when {
                count >= 100_000_000 -> String.format(Locale.CHINA, "%.1f亿", count / 100_000_000.0)
                count >= 10_000 -> String.format(Locale.CHINA, "%.1f万", count / 10_000.0)
                else -> counts.format(count)
            }
        }
        update(gems, config.optJSONObject("pyroxene"), "青辉石") { count, _ -> counts.format(count) }
    }

    private fun update(view: TextView, data: JSONObject?, label: String, format: (Long, JSONObject) -> String) {
        val count = data?.optLong("count", -1) ?: -1
        val time = data?.optDouble("time", 0.0) ?: 0.0
        val valid = count >= 0 && time.isFinite() && time > 0
        val value = if (valid) format(count, requireNotNull(data)) else "—"
        if (view.text.toString() != value) view.text = value
        val detail = if (valid) "$label：${counts.format(count)} · 扫描于 ${scannedAt.format(Date((time * 1000).toLong()))}" else "$label：尚未扫描"
        view.contentDescription = detail; view.tooltipText = detail
    }
}
