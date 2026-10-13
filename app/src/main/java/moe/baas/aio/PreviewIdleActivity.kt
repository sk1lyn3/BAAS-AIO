package moe.baas.aio

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/** Content for a newly created display before a workflow launches its game. */
class PreviewIdleActivity : Activity() {
    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(newBase); Appearance.apply(this, newBase) }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(TextView(this).apply {
            text = "BAAS AIO\nChoose a configuration and start a task."
            gravity = Gravity.CENTER
            textSize = 24f
        })
    }
}
