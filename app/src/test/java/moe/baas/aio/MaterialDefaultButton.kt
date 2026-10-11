package moe.baas.aio

import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.only
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Test-only: original View UI button look for side-by-side render mockups.
/**
 * The platform Widget.Material.Button look (btn_default_material): #D6D7D7 fill inset 4/6dp,
 * radius 2dp, raised 2dp (+4dp pressed), 14sp medium all-caps unless restyled.
 */
@Composable
fun MaterialDefaultButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    textSize: Float = 14f,
    textColor: Color = Color(0xDE000000),
    disabledTextColor: Color = Color(0x42000000),
    allCaps: Boolean = true,
    minHeight: Dp = 48.dp,
    /** Total View padding: 4/6 drawable inset + 8/4 shape padding by default. */
    padding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val elevation by animateDpAsState(if (!enabled) 0.dp else if (pressed) 6.dp else 2.dp, tween(100), label = "elevation")
    val shape = RoundedCornerShape(2.dp)
    Box(modifier.defaultMinSize(88.dp, minHeight).clickable(source, null, enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        // Background is inset 4dp horizontally / 6dp vertically like the platform drawable.
        Box(Modifier.matchParentSize().padding(horizontal = 4.dp, vertical = 6.dp).shadow(elevation, shape).background(if (enabled) Color(0xFFD6D7D7) else Color(0x42D6D7D7), shape))
        // Pressed highlight stays inside the inset shape, like the platform ripple mask.
        if (pressed) Box(Modifier.matchParentSize().padding(horizontal = 4.dp, vertical = 6.dp).background(Color(0x1F000000), shape))
        androidx.compose.material3.Text(if (allCaps) text.uppercase() else text,
            Modifier.padding(padding),
            style = textStyle(textSize, if (enabled) textColor else disabledTextColor, FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
