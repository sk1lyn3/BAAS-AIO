package moe.baas.aio

import android.app.AlertDialog
import android.content.Context
import android.app.Activity
import android.os.Build
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import androidx.compose.ui.graphics.toArgb
import android.graphics.Typeface
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.Switch
import android.widget.LinearLayout
import android.widget.TextView

/** Shared native styling for the portrait UI, using the BAAS WebUI palette. */
object UiTheme {
    // v3: View-side mirrors of the Compose tokens in Ui (follow Ui.night).
    val blue get() = Ui.Blue.toArgb()
    val navy get() = Ui.Navy.toArgb()
    val muted get() = Ui.Muted.toArgb()
    val pale get() = Ui.Pale.toArgb()
    val border get() = Ui.Border.toArgb()
    val danger get() = Ui.Danger.toArgb()
    val surface get() = Ui.Card.toArgb()
    val bgTop get() = Ui.BgTop.toArgb()
    fun syncNight(context: Context) {
        // The activity context is overridden by Appearance.apply, so its uiMode already reflects the manual choice.
        Ui.night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
    /** Bars, root backdrop and window background for the current Ui.night (startup and live switch). */
    @Suppress("DEPRECATION")
    fun styleWindow(activity: Activity) {
        val window = activity.window
        val light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.decorView.systemUiVisibility = if (Ui.night) window.decorView.systemUiVisibility and light.inv()
            else window.decorView.systemUiVisibility or light
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Ui.BgTop.toArgb()))
        (window.decorView.findViewById<android.view.ViewGroup>(android.R.id.content))?.getChildAt(0)?.let { root ->
            if (root.background is GradientDrawable) root.background = backdrop()
        }
    }
    @Suppress("DEPRECATION")
    fun setContentView(activity: Activity, root: View, landscapeCutoutPadding: Int? = null) {
        val window = activity.window
        syncNight(activity)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            (if (Ui.night) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
        if (Ui.night) window.decorView.systemUiVisibility = window.decorView.systemUiVisibility and
            (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR).inv()
        // Edge-to-edge: bars are transparent and the halo backdrop (root background) runs behind them.
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) { window.isNavigationBarContrastEnforced = false; window.isStatusBarContrastEnforced = false }
        root.background = backdrop()
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
    /** Root backdrop for View containers: the same sky gradient as Modifier.haloBackdrop (rings are drawn by Compose). */
    fun backdrop() = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Ui.BgTop.toArgb(), Ui.BgBottom.toArgb()))
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun column(context: Context) = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row(context: Context, gravity: Int = Gravity.NO_GRAVITY) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; this.gravity = gravity
    }
    fun page(context: Context) = column(context).apply {
        background = backdrop()
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
        val shape = background(context, if (primary) blue else surface, 12)
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
        background(context, surface, 12).apply {
            val color = when { invalid -> danger; focused -> blue; else -> border }
            setStroke(dp(context, if (focused || invalid) 2 else 1), color)
        }
    fun input(context: Context) = BaasInput(context)
    fun dialog(context: Context) = object : AlertDialog.Builder(context,
        if (Ui.night) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert) {
        override fun create(): AlertDialog = super.create().also { styleDialog(it) }
    }
    /** Test hook: render the pre-v3 native look for before/after comparisons. */
    @JvmStatic var v3Dialogs = true
    /**
     * v3 look for every platform AlertDialog (incl. Date/TimePickerDialog) without touching behavior: rounded card
     * surface with hairline border, v3 title/message typography, gradient primary + outlined secondary buttons.
     * Applied once the dialog's views exist (decor attach), so callers' own OnShowListeners stay untouched.
     */
    fun styleDialog(dialog: AlertDialog) {
        val context = dialog.context
        val window = dialog.window ?: return
        if (!v3Dialogs) {
            window.setBackgroundDrawable(InsetDrawable(background(context, surface, 20), dp(context, 8)))
            window.decorView.clipToOutline = true; return
        }
        window.setBackgroundDrawable(InsetDrawable(background(context, surface, 22).apply { setStroke(dp(context, 1), border) }, dp(context, 12)))
        window.decorView.clipToOutline = true
        window.decorView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { v.removeOnAttachStateChangeListener(this); v.post { decorate(dialog) } }
            override fun onViewDetachedFromWindow(v: View) {}
        })
    }
    private fun decorate(dialog: AlertDialog) {
        val context = dialog.context
        dialog.findViewById<TextView>(context.resources.getIdentifier("alertTitle", "id", "android"))?.apply {
            textSize = 17f; setTextColor(navy); typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        dialog.findViewById<TextView>(android.R.id.message)?.apply { textSize = 14f; setTextColor(navy); setLineSpacing(0f, 1.25f) }
        fun style(button: Button?, primary: Boolean) = button?.apply {
            if (text.isNullOrEmpty()) return@apply
            isAllCaps = false; textSize = 14f; stateListAnimator = null
            typeface = Typeface.create("sans-serif", if (primary) Typeface.BOLD else Typeface.NORMAL)
            minHeight = dp(context, 40); minimumHeight = dp(context, 40); minWidth = dp(context, 76); minimumWidth = dp(context, 76)
            setPadding(dp(context, 16), 0, dp(context, 16), 0)
            val shape = if (primary) GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.Cyan.toArgb(), Ui.Blue.toArgb(), Ui.BlueDeep.toArgb())).apply {
                cornerRadius = dp(context, 12).toFloat()
            } else background(context, Color.TRANSPARENT, 12).apply { setStroke(dp(context, 1), border) }
            background = RippleDrawable(ColorStateList.valueOf(if (primary) 0x33FFFFFF else 0x1A0A93D8), shape, null)
            setTextColor(if (primary) Color.WHITE else blue)
            (layoutParams as? ViewGroup.MarginLayoutParams)?.let { it.marginStart = dp(context, 8); layoutParams = it }
        }
        style(dialog.getButton(AlertDialog.BUTTON_POSITIVE), true)
        style(dialog.getButton(AlertDialog.BUTTON_NEGATIVE), false)
        style(dialog.getButton(AlertDialog.BUTTON_NEUTRAL), false)
        (dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.parent as? View)?.setPadding(dp(context, 16), dp(context, 4), dp(context, 16), dp(context, 14))
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
        button.background = background(button.context, if (selected) pale else surface, 8).apply {
            setStroke(dp(button.context, 1), if (selected) blue else border)
        }
    }
    fun segments(context: Context, labels: List<String>, selected: Int, textSize: Float = 12f, height: Int = 36,
                 colors: List<Int>? = null, change: (Int) -> Unit): LinearLayout {
        val group = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            if (colors == null) background = background(context, surface, 8).apply { setStroke(dp(context, 1), border) }
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
        background = background(context, surface, 16)
        setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
    }
    fun LinearLayout.item(view: View, weight: Float = 0f) {
        addView(view, LinearLayout.LayoutParams(if (weight > 0) 0 else -1, -2, weight).apply {
            val gap = dp(context, 3); setMargins(gap, dp(context, 4), gap, dp(context, 4))
        })
    }
}
