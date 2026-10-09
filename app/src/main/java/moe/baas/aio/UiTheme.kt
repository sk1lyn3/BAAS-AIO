package moe.baas.aio

import android.app.AlertDialog
import android.content.Context
import android.app.Activity
import android.os.Build
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.View
import android.view.WindowInsets
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.Switch
import android.widget.LinearLayout
import android.widget.TextView

/** Shared native styling for the portrait UI, using the BAAS WebUI palette. */
object UiTheme {
    val blue = Color.rgb(0, 143, 211)
    val navy = Color.rgb(32, 57, 78)
    val muted = Color.rgb(101, 122, 139)
    val pale = Color.rgb(237, 247, 253)
    val border = Color.rgb(218, 232, 241)
    val danger = Color.rgb(220, 72, 82)
    @Suppress("DEPRECATION")
    fun setContentView(activity: Activity, root: View, landscapeCutoutPadding: Int? = null) {
        val window = activity.window
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.statusBarColor = pale
        window.navigationBarColor = pale
        val left = root.paddingLeft; val top = root.paddingTop
        val right = root.paddingRight; val bottom = root.paddingBottom
        root.setOnApplyWindowInsetsListener { view, insets ->
            val cutout = insets.displayCutout
            val edgePadding = landscapeCutoutPadding?.takeIf {
                activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            }
            val baseLeft = if (edgePadding != null && (cutout?.safeInsetLeft ?: 0) > 0) dp(activity, edgePadding) else left
            val baseRight = if (edgePadding != null && (cutout?.safeInsetRight ?: 0) > 0) dp(activity, edgePadding) else right
            if (Build.VERSION.SDK_INT >= 30) {
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or
                    WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                view.setPadding(baseLeft + safe.left, top + safe.top, baseRight + safe.right, bottom + safe.bottom)
                WindowInsets.CONSUMED
            } else {
                view.setPadding(baseLeft + maxOf(insets.systemWindowInsetLeft, cutout?.safeInsetLeft ?: 0),
                    top + maxOf(insets.systemWindowInsetTop, cutout?.safeInsetTop ?: 0),
                    baseRight + maxOf(insets.systemWindowInsetRight, cutout?.safeInsetRight ?: 0),
                    bottom + maxOf(insets.systemWindowInsetBottom, cutout?.safeInsetBottom ?: 0))
                insets.consumeSystemWindowInsets().consumeDisplayCutout()
            }
        }
        activity.setContentView(root)
        root.requestApplyInsets()
    }
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun column(context: Context) = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row(context: Context, gravity: Int = Gravity.NO_GRAVITY) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; this.gravity = gravity
    }
    fun page(context: Context) = column(context).apply {
        setBackgroundColor(pale)
        val p = dp(context, 12); setPadding(p, p, p, p)
    }
    fun background(context: Context, color: Int, radius: Int = 12) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(context, radius).toFloat()
    }
    fun text(context: Context, value: String, size: Float = 14f, color: Int = navy) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color); typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        setPadding(dp(context, 4), dp(context, 6), dp(context, 4), dp(context, 6))
    }
    fun button(context: Context, label: String, primary: Boolean = false, action: () -> Unit) = Button(context).apply {
        text = label; isAllCaps = false; textSize = 13f; minHeight = dp(context, 48); minimumHeight = dp(context, 48)
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(muted, if (primary) Color.WHITE else blue)))
        val shape = background(context, if (primary) blue else Color.WHITE, 10)
        if (!primary) shape.setStroke(dp(context, 1), border)
        background = RippleDrawable(ColorStateList.valueOf(0x22008fd3), shape, null)
        setPadding(dp(context, 8), dp(context, 4), dp(context, 8), dp(context, 4))
        setOnClickListener { action() }
    }
    fun flatButton(context: Context, label: String, action: () -> Unit) = button(context, label, action = action).apply {
        minHeight = dp(context, 36); minimumHeight = dp(context, 36)
        textSize = 12f; stateListAnimator = null
        setPadding(dp(context, 10), dp(context, 4), dp(context, 10), dp(context, 4))
    }
    fun fieldBackground(context: Context, focused: Boolean = false, invalid: Boolean = false) =
        background(context, Color.WHITE, 10).apply {
            val color = when { invalid -> danger; focused -> blue; else -> border }
            setStroke(dp(context, if (focused || invalid) 2 else 1), color)
        }
    fun input(context: Context) = BaasInput(context)
    fun dialog(context: Context) = object : AlertDialog.Builder(context) {
        override fun create(): AlertDialog = super.create().apply {
            window?.apply {
                setBackgroundDrawable(InsetDrawable(background(context, Color.WHITE, 16), dp(context, 8)))
                decorView.clipToOutline = true
            }
        }
    }
    fun inputPanel(context: Context, input: View) = column(context).apply {
        setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 8))
        input.minimumHeight = dp(context, 44)
        addView(input, LinearLayout.LayoutParams(-1, -2))
    }
    fun compactSwitch(context: Context, checked: Boolean, label: String) = Switch(context).apply {
        contentDescription = label; showText = false
        background = null; splitTrack = false
        switchMinWidth = dp(context, 32); setPadding(dp(context, 4), 0, dp(context, 4), 0)
        thumbTintList = null; trackTintList = null
        thumbDrawable = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setSize(dp(context, 16), dp(context, 16)) }
        fun track(color: Int) = GradientDrawable().apply {
            cornerRadius = dp(context, 9).toFloat(); setSize(dp(context, 32), dp(context, 18)); setColor(color)
        }
        trackDrawable = StateListDrawable().apply {
            setEnterFadeDuration(180); setExitFadeDuration(180)
            addState(intArrayOf(android.R.attr.state_checked), track(blue))
            addState(intArrayOf(), track(border))
        }
        isChecked = checked
        jumpDrawablesToCurrentState()
    }
    fun chevron(context: Context) = ImageView(context).apply {
        setImageResource(R.drawable.ic_chevron); scaleType = ImageView.ScaleType.CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    fun quietRipple(context: Context, color: Int = Color.TRANSPARENT, radius: Int = 6) =
        RippleDrawable(ColorStateList.valueOf(0x10657a8b), background(context, color, radius), null)
    fun setToggleSelected(button: Button, selected: Boolean) {
        button.isSelected = selected
        button.setTextColor(if (selected) blue else muted)
        button.background = background(button.context, if (selected) pale else Color.WHITE, 8).apply {
            setStroke(dp(button.context, 1), if (selected) blue else border)
        }
    }
    fun segments(context: Context, labels: List<String>, selected: Int, textSize: Float = 12f, height: Int = 36,
                 colors: List<Int>? = null, change: (Int) -> Unit): LinearLayout {
        val group = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            if (colors == null) background = background(context, Color.WHITE, 8).apply { setStroke(dp(context, 1), border) }
            clipToOutline = colors == null
        }
        val buttons = mutableListOf<TextView>()
        var current = selected
        fun update() = buttons.forEachIndexed { index, view ->
            view.isSelected = index == current
            val color = colors?.get(index)
            if (color == null) {
                view.setTextColor(if (view.isSelected) Color.WHITE else muted)
                view.background = RippleDrawable(ColorStateList.valueOf(0x22008fd3),
                    background(context, if (view.isSelected) blue else Color.TRANSPARENT, 0), null)
            } else {
                view.setTextColor(color)
                view.setTypeface(null, if (view.isSelected) Typeface.BOLD else Typeface.NORMAL)
                val fill = (color and 0x00ffffff) or (if (view.isSelected) 0x30000000 else 0x10000000)
                val stroke = (color and 0x00ffffff) or (if (view.isSelected) 0xb0000000.toInt() else 0x40000000)
                view.background = RippleDrawable(ColorStateList.valueOf((color and 0x00ffffff) or 0x22000000),
                    background(context, fill, 6).apply { setStroke(dp(context, 1), stroke) }, null)
            }
        }
        labels.forEachIndexed { index, label ->
            val view = text(context, label, textSize).apply {
                gravity = Gravity.CENTER; minHeight = dp(context, height); maxLines = 1
                isClickable = true; isFocusable = true
                setOnClickListener { if (current != index) { current = index; update(); change(index) } }
            }
            buttons += view
            group.addView(view, LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (colors != null) {
                    leftMargin = if (index == 0) 0 else dp(context, 2)
                    rightMargin = if (index == labels.lastIndex) 0 else dp(context, 2)
                }
            })
        }
        update(); return group
    }
    fun card(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = background(context, Color.WHITE)
        setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
    }
    fun LinearLayout.item(view: View, weight: Float = 0f) {
        addView(view, LinearLayout.LayoutParams(if (weight > 0) 0 else -1, -2, weight).apply {
            val gap = dp(context, 3); setMargins(gap, dp(context, 4), gap, dp(context, 4))
        })
    }
}
