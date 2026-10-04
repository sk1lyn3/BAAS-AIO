package moe.baas.aio

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import org.json.JSONObject

/** Compact portrait counterpart of a WebUI scheduler table row. */
class ScheduleTaskRow(
    context: Context,
    private val event: JSONObject,
    draggable: Boolean,
    interval: String,
    next: String,
    private val nextColor: () -> Int,
    configure: () -> Unit,
    drag: (View) -> Boolean,
    enabled: (Boolean) -> Unit,
    error: (Exception) -> Unit
) : LinearLayout(context) {
    val enabled get() = event.getBoolean("enabled")
    val priority = UiTheme.text(context, event.optInt("priority").toString(), 11f, UiTheme.muted)
    private fun dp(value: Int) = UiTheme.dp(context, value)
    init {
        orientation = VERTICAL; tag = event.getString("func_name")
        setBackgroundColor(Color.WHITE)
        val content = LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(66); setPadding(dp(6), dp(3), dp(6), dp(3))
        }
        addView(content, LayoutParams(-1, -2))
        if (draggable) content.addView(UiTheme.text(context, "⠿", 20f, UiTheme.muted).apply {
            gravity = Gravity.CENTER; setPadding(0, 0, 0, 0)
            contentDescription = "拖动调整${event.getString("event_name")}顺序"
            setOnLongClickListener { drag(it) }
        }, LayoutParams(dp(24), dp(48)))
        priority.gravity = Gravity.CENTER; priority.setPadding(0, 0, 0, 0)
        content.addView(priority, LayoutParams(dp(24), -2))
        val details = LinearLayout(context).apply {
            orientation = VERTICAL; setPadding(dp(4), 0, dp(4), 0)
        }
        details.addView(UiTheme.text(context, event.getString("event_name"), 13f).apply {
            setPadding(0, 0, 0, dp(4)); maxLines = 2
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        val metadata = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val category = categories[event.getString("func_name")]
        if (category != null) metadata.addView(UiTheme.text(context, category.first, 9f, category.second).apply {
            setPadding(dp(4), dp(2), dp(4), dp(2))
            background = UiTheme.background(context, (category.second and 0x00ffffff) or 0x18000000, 4)
        }, LayoutParams(-2, -2).apply { rightMargin = dp(5) })
        metadata.addView(UiTheme.text(context, interval, 10f, UiTheme.muted).apply { setPadding(0, 0, 0, 0); maxLines = 1 })
        details.addView(metadata); content.addView(details, LayoutParams(0, -2, 1f))
        val nextLabel = UiTheme.text(context, next, 10.5f).apply {
            setPadding(dp(2), 0, dp(2), 0); maxLines = 2
        }
        content.addView(nextLabel, LayoutParams(dp(78), -2))
        fun updateEnabled() {
            val active = event.getBoolean("enabled")
            details.alpha = if (active) 1f else .5f
            priority.alpha = details.alpha
            nextLabel.setTextColor(nextColor())
        }
        var restoring = false
        content.addView(Switch(context).apply {
            contentDescription = "启用${event.getString("event_name")}"; showText = false
            switchMinWidth = dp(32); setPadding(dp(4), 0, dp(4), 0)
            thumbDrawable = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setSize(dp(16), dp(16)) }
            trackDrawable = GradientDrawable().apply { cornerRadius = dp(9).toFloat(); setSize(dp(32), dp(18)) }
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(UiTheme.blue, UiTheme.border))
            isChecked = event.getBoolean("enabled")
            setOnCheckedChangeListener { _, checked ->
                if (!restoring) try {
                    enabled(checked); event.put("enabled", checked); updateEnabled()
                } catch (failure: Exception) {
                    restoring = true; isChecked = !checked; restoring = false; error(failure)
                }
            }
        }, LayoutParams(dp(42), dp(44)))
        content.addView(UiTheme.text(context, "⚙\uFE0E", 20f, UiTheme.muted).apply {
            gravity = Gravity.CENTER; setPadding(0, 0, 0, 0)
            contentDescription = "配置${event.getString("event_name")}"
            isClickable = true; isFocusable = true
            setOnClickListener { configure() }
        }, LayoutParams(dp(30), dp(44)))
        addView(View(context).apply { setBackgroundColor(UiTheme.border) }, LayoutParams(-1, dp(1)))
        updateEnabled()
    }
    companion object {
        private val categories = mapOf(
            "cafe_reward" to ("咖啡厅" to 0xffdb2777.toInt()), "group" to ("社团" to 0xff16a34a.toInt()),
            "lesson" to ("日程" to 0xff9333ea.toInt()), "collect_reward" to ("奖励" to 0xffd97706.toInt()),
            "mail" to ("邮箱" to 0xff0284c7.toInt()), "arena" to ("竞技" to 0xffdc2626.toInt()),
            "common_shop" to ("商店" to 0xff4f46e5.toInt()), "tactical_challenge_shop" to ("商店" to 0xff4f46e5.toInt()),
            "create" to ("制造" to 0xffea580c.toInt()), "rewarded_task" to ("通缉" to 0xffdc2626.toInt()),
            "total_assault" to ("总力战" to UiTheme.blue), "scrimmage" to ("交流会" to 0xff0d9488.toInt()),
            "joint_firing_drill" to ("演习" to 0xffea580c.toInt()), "activity_sweep" to ("活动" to 0xffc026d3.toInt())
        )
    }
}
