package moe.baas.aio

import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout

/** Inline search and level filters shared by both log views. */
class LogFilters(context: Context, initialQuery: String, initialLevel: Int, changed: (String, Int) -> Unit) {
    private var query = initialQuery
    private var level = initialLevel
    val search = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        isFocusableInTouchMode = true
        background = UiTheme.background(context, Color.WHITE, 8).apply { setStroke(UiTheme.dp(context, 1), UiTheme.border) }
    }
    val levels = UiTheme.segments(context, listOf("全部", "INFO", "WARN", "ERROR", "CRIT"), initialLevel,
        textSize = 10f, height = 32) { index -> level = index; changed(query, level) }.apply {
        val names = listOf("全部日志", "INFO 信息", "WARNING 警告", "ERROR 错误", "CRITICAL 严重错误")
        names.forEachIndexed { index, name -> getChildAt(index).contentDescription = name }
    }
    init {
        fun dp(value: Int) = UiTheme.dp(context, value)
        search.addView(ImageView(context).apply { setImageResource(R.drawable.ic_search) }, LinearLayout.LayoutParams(dp(28), dp(32)))
        val input = EditText(context).apply {
            hint = "搜索日志"; textSize = 11f; setSingleLine(true)
            setTextColor(UiTheme.navy); setHintTextColor(UiTheme.muted)
            background = null; setPadding(0, 0, 0, 0); minimumWidth = 0
            imeOptions = EditorInfo.IME_ACTION_SEARCH; setText(initialQuery)
        }
        search.addView(input, LinearLayout.LayoutParams(0, dp(32), 1f))
        val clear = UiTheme.text(context, "×", 18f, UiTheme.muted).apply {
            gravity = Gravity.CENTER; setPadding(0, 0, 0, 0)
            contentDescription = "清除搜索"; isClickable = true; isFocusable = true
            visibility = if (initialQuery.isEmpty()) View.GONE else View.VISIBLE
            setOnClickListener { input.text.clear() }
        }
        search.addView(clear, LinearLayout.LayoutParams(dp(24), dp(32)))
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString() ?: ""
                clear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
                changed(query, level)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
                input.clearFocus(); search.requestFocus(); true
            } else false
        }
        search.requestFocus()
    }
}
