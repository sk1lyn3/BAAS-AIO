package moe.baas.aio

import android.app.Activity
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** In-app messages share the existing palette and need no overlay permission (same look/timing as before). */
object UiNotice {
    private const val TAG = "baas-notice"
    fun show(activity: Activity, message: String, actionLabel: String? = null,
             error: Boolean = false, action: (() -> Unit)? = null) = activity.runOnUiThread {
        if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
        val host = activity.findViewById<FrameLayout>(android.R.id.content)
        host.findViewWithTag<View>(TAG)?.let { host.removeView(it) }
        val duration = if (action != null) 8000 else 4500
        val timeout = if (Build.VERSION.SDK_INT >= 29) activity.getSystemService(AccessibilityManager::class.java)
            .getRecommendedTimeoutMillis(duration, AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_CONTROLS)
        else duration
        val accent = if (error) Ui.Danger else Ui.Blue
        val bar = ComposeView(activity).apply { tag = TAG }
        bar.setContent {
            val density = LocalDensity.current
            val alpha = remember { Animatable(0f) }
            val offset = remember { Animatable(with(density) { 12.dp.toPx() }) }
            var leaving by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            fun dismiss() {
                if (leaving) return
                leaving = true
                scope.launch {
                    launch { offset.animateTo(with(density) { 8.dp.toPx() }, tween(140)) }
                    alpha.animateTo(0f, tween(140))
                    (bar.parent as? FrameLayout)?.removeView(bar)
                }
            }
            LaunchedEffect(Unit) {
                launch { offset.animateTo(0f, tween(180)) }
                alpha.animateTo(1f, tween(180))
                delay(timeout.toLong())
                dismiss()
            }
            val shape = RoundedCornerShape(12.dp)
            // 6dp inner inset leaves room for the elevation shadow inside the ComposeView.
            Box(Modifier.padding(6.dp)) { Row(Modifier.fillMaxWidth().graphicsLayer { this.alpha = alpha.value; translationY = offset.value }
                .shadow(6.dp, shape).background(Color.White, shape).border(1.dp, Ui.Border, shape)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(start = 10.dp, top = 6.dp, end = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                BText(if (error) "!" else "i", 17f, accent, Modifier.width(24.dp), weight = FontWeight.Bold, align = TextAlign.Center)
                BText(message, 13f, modifier = Modifier.weight(1f), maxLines = 4)
                if (actionLabel != null && action != null) BButton(actionLabel, flat = true) { dismiss(); action() }
                Ui.Quiet {
                    Box(Modifier.size(40.dp, 44.dp).clickable { dismiss() }.semantics { contentDescription = "关闭提示" }, contentAlignment = Alignment.Center) {
                        BText("×", 20f, Ui.Muted)
                    }
                }
            } }
        }
        val dp = activity.resources.displayMetrics.density
        host.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = (6 * dp).toInt(); rightMargin = (6 * dp).toInt()
            bottomMargin = ((if (activity is HomeActivity) 66 else 10) * dp).toInt()
        })
    }
}
