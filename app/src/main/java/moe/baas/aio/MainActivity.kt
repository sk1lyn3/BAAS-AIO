package moe.baas.aio

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
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
import java.util.concurrent.atomic.AtomicBoolean
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

class MainActivity : Activity() {
    companion object { private val probeRunning = AtomicBoolean(false) }
    private lateinit var output: TextView
    private var binding = false
    private val running get() = probeRunning.get()
    private var companionBroker: ISocketBroker? = null
    private var companionMode = false
    private var ocrMode = false
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
            companionBroker = ISocketBroker.Stub.asInterface(binder)
            runProbe(companionBroker)
        }
        override fun onServiceDisconnected(name: ComponentName) { binding = false; companionBroker = null }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (running || binding) return
        setIntent(intent)
        companionMode = intent.getStringExtra("mode") in listOf("companion", "vision")
        ocrMode = intent.getStringExtra("mode") in listOf("ocr", "vision")
        if (companionMode) connectCompanion() else runProbe(null)
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
                setOnClickListener { if (!running && !binding) { ocrMode = false; companionMode = true; connectCompanion() } }
            })
            addView(Button(this@MainActivity).apply {
                text = "Test Android OCR"
                setOnClickListener { if (!running && !binding) { ocrMode = true; companionMode = false; runProbe(null) } }
            })
            addView(Button(this@MainActivity).apply {
                text = "Test Companion screenshot recognition"
                setOnClickListener { if (!running && !binding) { ocrMode = true; companionMode = true; connectCompanion() } }
            })
            addView(ScrollView(this@MainActivity).apply { addView(output) }, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        companionMode = intent.getStringExtra("mode") in listOf("companion", "vision")
        ocrMode = intent.getStringExtra("mode") in listOf("ocr", "vision")
        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        if (!companionMode) runProbe(null)
    }

    private fun connectCompanion() {
        if (binding || running) return
        companionBroker?.let { broker ->
            if (broker.asBinder().pingBinder()) { runProbe(broker); return }
            companionBroker = null
        }
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
        if (!probeRunning.compareAndSet(false, true)) return
        val runOcr = ocrMode
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
                if (runOcr) {
                    val bundle = File(filesDir, "ocr-resources.zip")
                    assets.open("ocr-resources.zip").use { input ->
                        bundle.outputStream().use { input.copyTo(it) }
                    }
                    assets.open("ocr-reference.json").use { input ->
                        File(filesDir, "ocr-reference.json").outputStream().use { input.copyTo(it) }
                    }
                    OcrSession(applicationContext).use { ocr ->
                        if (broker == null) python.getModule("ocr_probe").callAttr("run", filesDir.absolutePath, ocr).toString()
                        else CompanionBridge(broker).use { bridge ->
                            python.getModule("ocr_probe").callAttr("run", filesDir.absolutePath, ocr, bridge).toString()
                        }
                    }
                } else if (broker == null) runtime else CompanionBridge(broker).use { bridge ->
                    python.getModule("companion_probe").callAttr("run", bridge, filesDir.absolutePath).toString()
                }
            } catch (error: Throwable) {
                org.json.JSONObject().put("ok", false)
                    .put("error", android.util.Log.getStackTraceString(error)).toString(2)
            }
            val reportName = if (runOcr) "ocr-report.json" else if (broker != null) "companion-report.json" else "probe-report.json"
            File(filesDir, reportName).writeText(report)
            android.util.Log.i("BaasAioProbe", report)
            runOnUiThread { probeRunning.set(false); if (!isDestroyed) output.text = report }
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderListener)
        if (companionBroker != null || binding) Shizuku.unbindUserService(serviceArgs, connection, false)
        super.onDestroy()
    }
}
