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
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
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
            styleButton(this, primary)
            (layoutParams as? ViewGroup.MarginLayoutParams)?.let { it.marginStart = dp(context, 8); layoutParams = it }
        }
        style(dialog.getButton(AlertDialog.BUTTON_POSITIVE), true)
        style(dialog.getButton(AlertDialog.BUTTON_NEGATIVE), false)
        style(dialog.getButton(AlertDialog.BUTTON_NEUTRAL), false)
        (dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.parent as? View)?.setPadding(dp(context, 16), dp(context, 4), dp(context, 16), dp(context, 14))
    }
    /** v3 dialog button look: gradient primary or outlined secondary; a button disabled before styling gets a pale fill. */
    fun styleButton(button: Button, primary: Boolean) = button.apply {
        isAllCaps = false; textSize = 14f; stateListAnimator = null
        typeface = Typeface.create("sans-serif", if (primary) Typeface.BOLD else Typeface.NORMAL)
        minHeight = dp(context, 40); minimumHeight = dp(context, 40); minWidth = dp(context, 76); minimumWidth = dp(context, 76)
        setPadding(dp(context, 16), 0, dp(context, 16), 0)
        val shape = if (primary) GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.Cyan.toArgb(), Ui.Blue.toArgb(), Ui.BlueDeep.toArgb())).apply {
            cornerRadius = dp(context, 12).toFloat()
        } else background(context, Color.TRANSPARENT, 12).apply { setStroke(dp(context, 1), border) }
        background = if (isEnabled) RippleDrawable(ColorStateList.valueOf(if (primary) 0x33FFFFFF else if (BuildConfig.DEBUG) Ui.Blue.copy(alpha = .10f).toArgb() else 0x1A0A93D8), shape, null)
            else background(context, pale, 12).apply { setStroke(dp(context, 1), border) }
        setTextColor(if (!isEnabled) muted else if (primary) Color.WHITE else blue)
    }
    fun inputPanel(context: Context, input: View) = column(context).apply {
        setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 8))
        input.minimumHeight = dp(context, 44)
        addView(input, LinearLayout.LayoutParams(-1, -2))
    }
    fun chevron(context: Context) = ImageView(context).apply {
        setImageResource(R.drawable.ic_chevron); scaleType = ImageView.ScaleType.CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    fun quietRipple(context: Context, color: Int = Color.TRANSPARENT, radius: Int = 6) =
        RippleDrawable(ColorStateList.valueOf(0x10657a8b), background(context, color, radius), null)
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
