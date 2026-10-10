package moe.baas.aio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import java.math.BigDecimal

private val selection = TextSelectionColors(handleColor = Ui.Blue, backgroundColor = Color(0x33008FD3))

/** BaasInput: 13sp field with focus border, blue 2dp cursor. */
@Composable
fun BInput(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "",
    size: Float = 13f,
    singleLine: Boolean = true,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    actions: KeyboardActions = KeyboardActions.Default,
    enabled: Boolean = true,
    framed: Boolean = true,
    align: TextAlign = TextAlign.Start,
    description: String? = null,
    onFocus: (Boolean) -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    CompositionLocalProvider(LocalTextSelectionColors provides selection) {
        BasicTextField(
            value, onChange,
            modifier.onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
                .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
            enabled = enabled, singleLine = singleLine, maxLines = if (singleLine) 1 else 4,
            textStyle = textStyle(size, if (enabled) Ui.Navy else Ui.Muted).copy(textAlign = align),
            cursorBrush = SolidColor(Ui.Blue), keyboardOptions = keyboard, keyboardActions = actions,
            decorationBox = { inner ->
                Box(
                    (if (framed) Modifier.field(focused).defaultMinSize(minHeight = 36.dp).padding(horizontal = 12.dp, vertical = 8.dp) else Modifier),
                    contentAlignment = if (align == TextAlign.Center) Alignment.Center else Alignment.CenterStart,
                ) {
                    if (value.isEmpty() && hint.isNotEmpty()) androidx.compose.material3.Text(hint, style = textStyle(size, Ui.Muted).copy(textAlign = align),
                        modifier = if (align == TextAlign.Center) Modifier.fillMaxWidth() else Modifier)
                    inner()
                }
            },
        )
    }
}

/** NumberInput: −/+ stepper with direct keyboard entry; same validity rules. */
class NumberRules(val minimum: BigDecimal? = null, val maximum: BigDecimal? = null, val step: BigDecimal = BigDecimal.ONE,
                  val integer: Boolean = true, val allowBlank: Boolean = false) {
    fun valid(text: String): Boolean {
        val raw = text.trim(); val number = raw.toBigDecimalOrNull()
        return (raw.isEmpty() && allowBlank) || (number != null && (!integer || number.stripTrailingZeros().scale() <= 0) &&
            (minimum == null || number >= minimum) && (maximum == null || number <= maximum))
    }
    fun value(text: String): String? = if (!valid(text)) null else
        text.trim().let { if (it.isEmpty()) it else it.toBigDecimal().stripTrailingZeros().toPlainString() }
    fun increment(text: String, direction: Int): String {
        var number = (text.trim().toBigDecimalOrNull() ?: minimum ?: BigDecimal.ZERO) + step * direction.toBigDecimal()
        if (minimum != null && number < minimum) number = minimum
        if (maximum != null && number > maximum) number = maximum
        return number.stripTrailingZeros().toPlainString()
    }
}

@Composable
fun NumberField(text: String, onText: (String) -> Unit, label: String, rules: NumberRules, modifier: Modifier = Modifier, editable: Boolean = true) {
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val valid = rules.valid(text)
    val number = text.trim().toBigDecimalOrNull()
    val minusOn = editable && valid && (number == null || rules.minimum == null || number > rules.minimum)
    val plusOn = editable && valid && (number == null || rules.maximum == null || number < rules.maximum)
    Row(modifier.field(focused, !valid).clip(RoundedCornerShape(10.dp)), verticalAlignment = Alignment.CenterVertically) {
        @Composable fun step(symbol: String, description: String, on: Boolean, direction: Int) = Ui.Quiet {
            Box(Modifier.width(32.dp).height(36.dp).alpha(if (on) 1f else .3f)
                .clickable(enabled = on, role = Role.Button) { onText(rules.increment(text, direction)) }
                .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
                BText(symbol, 20f, Ui.Blue, padding = NoPadding)
            }
        }
        step("−", "减少$label", minusOn, -1)
        BInput(text, onText, Modifier.weight(1f).height(36.dp), hint = if (rules.allowBlank) "自动" else "", framed = false,
            align = TextAlign.Center, enabled = editable, description = label, onFocus = { focused = it },
            keyboard = KeyboardOptions(keyboardType = if (rules.integer) KeyboardType.Number else KeyboardType.Decimal, imeAction = ImeAction.Done),
            actions = KeyboardActions(onDone = { focus.clearFocus() }))
        step("+", "增加$label", plusOn, 1)
    }
}

/** Positions a popup below [anchor] with [gap]; END aligned (ChoiceMenu) or START (ListPopupWindow). */
class DropPosition(private val gap: Int, private val alignEnd: Boolean) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = if (alignEnd) anchorBounds.right - popupContentSize.width else anchorBounds.left
        var y = anchorBounds.bottom + gap
        if (y + popupContentSize.height > windowSize.height) y = (anchorBounds.top - gap - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x.coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width)), y)
    }
}

/** ChoiceMenu: anchored compact choices (40dp rows, ✓ on the current value). Place inside the anchor Box. */
@Composable
fun ChoicePopup(labels: List<String>, selected: Int, width: Dp, onDismiss: () -> Unit, choose: (Int) -> Unit) {
    val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
    Popup(popupPositionProvider = remember { DropPosition(gap, true) }, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        val shape = RoundedCornerShape(14.dp)
        Column(Modifier.width(width).heightIn(max = minOf(labels.size * 40 + 8, 288).dp).softShadow(12.dp, shape, 0.2f).background(Ui.Card, shape)
            .border(1.dp, Ui.Border, shape).clip(shape).let { m -> val sc = rememberScrollState(); m.fadingScrollbar(sc).verticalScroll(sc) }.padding(4.dp)) {
            labels.forEachIndexed { index, label ->
                val on = index == selected
                Ui.Quiet {
                    Row(Modifier.fillMaxWidth().height(40.dp).background(if (on) Ui.Pale else Ui.Card, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp))
                        .clickable { onDismiss(); choose(index) }.semantics(mergeDescendants = true) { contentDescription = label + if (on) "，已选择" else "" },
                        verticalAlignment = Alignment.CenterVertically) {
                        BText(label, 12f, if (on) Ui.Blue else Ui.Navy, Modifier.weight(1f), weight = if (on) FontWeight.Bold else FontWeight.Normal,
                            padding = androidx.compose.foundation.layout.PaddingValues(start = 8.dp, end = 4.dp), maxLines = 2)
                        if (on) BText("✓", 13f, Ui.Blue, Modifier.width(24.dp), padding = NoPadding, align = TextAlign.Center)
                    }
                }
            }
        }
    }
}
