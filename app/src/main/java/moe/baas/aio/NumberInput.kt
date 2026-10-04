package moe.baas.aio

import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import java.math.BigDecimal

/** Inline stepper: precise decimal increments and optional direct keyboard entry. */
class NumberInput(context: Context, initial: String, label: String,
                  private val minimum: BigDecimal? = null, private val maximum: BigDecimal? = null,
                  private val step: BigDecimal = BigDecimal.ONE, private val integer: Boolean = true,
                  private val allowBlank: Boolean = false, private val changed: (String?) -> Unit = {}) : LinearLayout(context) {
    private val minus = UiTheme.text(context, "−", 20f, UiTheme.blue)
    private val input = UiTheme.input(context)
    private val plus = UiTheme.text(context, "+", 20f, UiTheme.blue)
    private var editable = true
    var valid = true
        private set
    val text get() = input.text.toString()
    val value: String? get() = if (!valid) null else
        text.trim().let { if (it.isEmpty()) it else it.toBigDecimal().stripTrailingZeros().toPlainString() }
    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipToOutline = true
        for ((button, description) in listOf(minus to "减少$label", plus to "增加$label")) {
            button.gravity = Gravity.CENTER; button.setPadding(0, 0, 0, 0)
            button.isClickable = true; button.isFocusable = true; button.contentDescription = description
            button.background = UiTheme.quietRipple(context)
        }
        input.apply {
            background = null; gravity = Gravity.CENTER; setPadding(0, 0, 0, 0)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or
                (if (integer) 0 else InputType.TYPE_NUMBER_FLAG_DECIMAL)
            setSingleLine(true); imeOptions = EditorInfo.IME_ACTION_DONE
            contentDescription = label; hint = if (allowBlank) "自动" else ""
            setText(initial)
            setOnFocusChangeListener { _, _ -> update(false) }
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) {
                    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0)
                    clearFocus(); true
                } else false
            }
        }
        addView(minus, LayoutParams(UiTheme.dp(context, 32), UiTheme.dp(context, 36)))
        addView(input, LayoutParams(0, UiTheme.dp(context, 36), 1f))
        addView(plus, LayoutParams(UiTheme.dp(context, 32), UiTheme.dp(context, 36)))
        minus.setOnClickListener { increment(-1) }; plus.setOnClickListener { increment(1) }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { update(true) }
            override fun afterTextChanged(s: Editable?) {}
        })
        update(false)
    }
    fun setValue(value: String) { input.setText(value) }
    fun setEditable(enabled: Boolean) { editable = enabled; input.isEnabled = enabled; update(false) }
    private fun update(emit: Boolean) {
        val raw = text.trim()
        val number = raw.toBigDecimalOrNull()
        valid = (raw.isEmpty() && allowBlank) || (number != null &&
            (!integer || number.stripTrailingZeros().scale() <= 0) &&
            (minimum == null || number >= minimum) && (maximum == null || number <= maximum))
        background = UiTheme.background(context, Color.WHITE, 6).apply {
            setStroke(UiTheme.dp(context, 1), if (!valid) UiTheme.danger else if (input.hasFocus()) UiTheme.blue else UiTheme.border)
        }
        minus.isEnabled = editable && valid && (number == null || minimum == null || number > minimum)
        plus.isEnabled = editable && valid && (number == null || maximum == null || number < maximum)
        minus.alpha = if (minus.isEnabled) 1f else .3f; plus.alpha = if (plus.isEnabled) 1f else .3f
        if (emit) changed(value)
    }
    private fun increment(direction: Int) {
        var number = (text.trim().toBigDecimalOrNull() ?: minimum ?: BigDecimal.ZERO) + step * direction.toBigDecimal()
        if (minimum != null && number < minimum) number = minimum
        if (maximum != null && number > maximum) number = maximum
        setValue(number.stripTrailingZeros().toPlainString())
    }
}
