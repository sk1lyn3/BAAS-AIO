package moe.baas.aio

import android.app.Activity
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var output: TextView
    private var binding = false
    private var running = false
    private var companionMode = false
    private val serviceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(this, SocketBroker::class.java))
            .daemon(false).processNameSuffix("aio-broker").debuggable(true).version(1).tag("baas-aio-broker")
    }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == 101) {
            if (result == PackageManager.PERMISSION_GRANTED) connectCompanion()
            else output.text = "Shizuku permission denied"
        }
    }
    private val binderListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { if (companionMode && !running) connectCompanion() }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            binding = false
            runProbe(ISocketBroker.Stub.asInterface(binder))
        }
        override fun onServiceDisconnected(name: ComponentName) { binding = false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply {
            text = "BAAS AIO\nValidating Python and BAAS…"
            textSize = 16f
            gravity = Gravity.CENTER
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Button(this@MainActivity).apply {
                text = "Test Companion connection"
                setOnClickListener { companionMode = true; connectCompanion() }
            })
            addView(ScrollView(this@MainActivity).apply { addView(output) }, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        companionMode = intent.getStringExtra("mode") == "companion"
        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        if (!companionMode) runProbe(null)
    }

    private fun connectCompanion() {
        if (binding || running) return
        if (!Shizuku.pingBinder()) { output.text = "Start Shizuku first"; return }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            output.text = "Grant Shizuku permission to connect Companion"
            Shizuku.requestPermission(101)
            return
        }
        binding = true
        output.text = "Connecting Companion…"
        Shizuku.bindUserService(serviceArgs, connection)
    }

    private fun runProbe(broker: ISocketBroker?) {
        if (running) return
        running = true
        thread(name = "baas-runtime-probe") {
            val report = try {
                val source = File(filesDir, "baas-source.zip")
                assets.open("baas-source.zip").use { input ->
                    source.outputStream().use { input.copyTo(it) }
                }
                if (!Python.isStarted()) Python.start(AndroidPlatform(applicationContext))
                val python = Python.getInstance()
                val runtime = python.getModule("runtime_probe").callAttr("run", filesDir.absolutePath).toString()
                File(filesDir, "probe-report.json").writeText(runtime)
                if (broker == null) runtime else CompanionBridge(broker).use { bridge ->
                    python.getModule("companion_probe").callAttr("run", bridge, filesDir.absolutePath).toString()
                }
            } catch (error: Throwable) {
                org.json.JSONObject().put("ok", false)
                    .put("error", android.util.Log.getStackTraceString(error)).toString(2)
            }
            File(filesDir, if (broker == null) "probe-report.json" else "companion-report.json").writeText(report)
            android.util.Log.i("BaasAioProbe", report)
            runOnUiThread { running = false; if (!isDestroyed) output.text = report }
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderListener)
        if (companionMode) Shizuku.unbindUserService(serviceArgs, connection, false)
        super.onDestroy()
    }
}
