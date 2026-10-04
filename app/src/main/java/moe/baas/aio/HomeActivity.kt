package moe.baas.aio

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Application entry point; engine and diagnostic screens remain separate hosts. */
class HomeActivity : Activity() {
    private lateinit var summary: TextView
    private val changed: (String) -> Unit = { if (!isDestroyed) refresh() }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); ProfileStore(this).initialize()
        summary = TextView(this).apply { textSize = 18f }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        layout.addView(TextView(this).apply { text = "BAAS AIO"; textSize = 28f })
        layout.addView(summary)
        fun entry(label: String, target: Class<out Activity>) {
            layout.addView(Button(this).apply { text = label; setOnClickListener { startActivity(Intent(this@HomeActivity, target)) } })
        }
        entry("Configurations", ProfileActivity::class.java)
        entry("Tasks / managed mode", TaskActivity::class.java)
        entry("Logs", LogActivity::class.java)
        entry("Virtual display preview", PreviewActivity::class.java)
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            entry("Diagnostics", MainActivity::class.java)
        setContentView(ScrollView(this).apply { addView(layout) })
        TaskRuntime.observe(changed)
    }
    override fun onResume() { super.onResume(); refresh() }
    private fun refresh() {
        summary.text = "Configuration: ${ProfileStore(this).selected()}\nTask: ${TaskRuntime.state}"
    }
    override fun onDestroy() { TaskRuntime.remove(changed); super.onDestroy() }
}
