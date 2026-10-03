package moe.baas.aio

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val output = TextView(this).apply {
            text = "BAAS AIO\nValidating Python and BAAS…"
            textSize = 16f
            gravity = Gravity.CENTER
        }
        setContentView(output)
        Thread({
            val report = try {
                val source = File(filesDir, "baas-source.zip")
                assets.open("baas-source.zip").use { input ->
                    source.outputStream().use { input.copyTo(it) }
                }
                if (!Python.isStarted()) Python.start(AndroidPlatform(applicationContext))
                Python.getInstance().getModule("runtime_probe")
                    .callAttr("run", filesDir.absolutePath).toString()
            } catch (error: Throwable) {
                org.json.JSONObject().put("ok", false)
                    .put("error", android.util.Log.getStackTraceString(error)).toString(2)
            }
            File(filesDir, "probe-report.json").writeText(report)
            android.util.Log.i("BaasAioProbe", report)
            runOnUiThread { output.text = report }
        }, "baas-runtime-probe").start()
    }
}
