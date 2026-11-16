package moe.baas.aio

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Prefer actual presentation history; unsupported devices retain submission-rate sampling. */
internal object PreviewFps {
    fun install(activity: ComponentActivity, host: FrameLayout, broker: () -> ISocketBroker?) {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + .5f).toInt()
        val label = TextView(activity).apply {
            text = "FPS —"
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            setPadding(dp(5), dp(2), dp(5), dp(2))
            background = GradientDrawable().apply {
                setColor(0x99060B14.toInt())
                cornerRadius = dp(4).toFloat()
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = false
            isFocusable = false
        }
        host.addView(label, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ).apply { setMargins(dp(8), dp(8), 0, 0) })
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var previous: LongArray? = null
                while (true) {
                    val sample = if (label.isShown) withContext(Dispatchers.IO) {
                        runCatching { broker()?.previewFrameSample(activity.componentName.flattenToString())
                            ?.takeIf { it.size >= 5 } }.getOrNull()
                    } else null
                    val before = previous
                    // [source, sampleTime, layerKey, submittedCount, readCost, ...presentTimes].
                    val fps = if (sample != null && before != null && sample[0] == before[0] &&
                        sample[2] == before[2] && sample[1] > before[1]) {
                        val frames = if (sample[0] == 1L) {
                            val last = if (before.size > 5) before.last() else before[1]
                            // Never turn an overrun of SF's finite history into an understated FPS.
                            if (sample.size >= 130 && sample[5] > last) null
                            else (5 until sample.size).count { sample[it] > last }.toLong()
                        } else (sample[3] - before[3]).takeIf { it >= 0 }
                        frames?.let { it * 1e9 / (sample[1] - before[1]) }
                    } else null
                    label.text = fps?.let { String.format(Locale.US, "FPS %.0f", it) } ?: "FPS —"
                    previous = sample
                    delay(1000)
                }
            }
        }
    }
}
