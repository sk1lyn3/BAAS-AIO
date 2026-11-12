package moe.baas.aio

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.widget.TextView
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

class MainActivity : Activity() {
    override fun attachBaseContext(base: android.content.Context) { super.attachBaseContext(base); Appearance.apply(this, base) }
    private val clientOwner = android.os.Binder()
    private lateinit var output: TextView
    private var binding = false
    private val running get() = TaskRuntime.isProbeRunning
    private var companionBroker: ISocketBroker? = null
    private var companionMode = false
    private var ocrMode = false
    private val serviceArgs by lazy { SocketBroker.serviceArgs(this) }
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
            if (companionBroker?.asBinder() == binder) return
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
        val page = DebugPage(this, "Developer diagnostics")
        val runtime = page.section("Runtime checks")
        page.actions(runtime,
            "Python & Core" to { if (!running && !binding) { ocrMode = false; companionMode = false; runProbe(null) } },
            "Android OCR" to { if (!running && !binding) { ocrMode = true; companionMode = false; runProbe(null) } })
        page.actions(runtime,
            "Middleware" to { if (!running && !binding) { ocrMode = false; companionMode = true; connectCompanion() } },
            "Capture & OCR" to { if (!running && !binding) { ocrMode = true; companionMode = true; connectCompanion() } })
        val preview = page.section("Virtual display")
        page.actions(preview,
            "Preview" to { if (!running && !binding) startActivity(Intent(this, PreviewActivity::class.java)) },
            "Launch game" to {
                if (!running && !binding && !TaskRuntime.isActive) {
                    val packages = arrayOf("com.YostarJP.BlueArchive", "com.nexon.bluearchive", "com.RoamingStar.BlueArchive")
                    UiTheme.dialog(this).setTitle("Game server")
                        .setItems(arrayOf("JP", "Global", "CN")) { _, index ->
                            startActivity(Intent(this, PreviewActivity::class.java).putExtra("gamePackage", packages[index]))
                        }.show()
                } else output.text = "Wait for the current probe or stop the workflow task first"
            })
        page.actions(page.section("Workflow"), "Task diagnostics" to {
            if (!running && !binding) startActivity(Intent(this, TaskActivity::class.java))
        })
        output = page.report().apply { text = "Checking Python and Core…" }
        companionMode = intent.getStringExtra("mode") in listOf("companion", "vision")
        ocrMode = intent.getStringExtra("mode") in listOf("ocr", "vision")
        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        if (!companionMode && !TaskRuntime.isActive) runProbe(null)
    }

    private fun connectCompanion() {
        if (binding || running || TaskRuntime.isActive) return
        companionBroker?.let { broker ->
            if (broker.asBinder().pingBinder()) { runProbe(broker); return }
            companionBroker = null
        }
        if (!Shizuku.pingBinder()) { output.text = "Start Shizuku first"; return }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            output.text = "Grant Shizuku permission to connect the middleware"
            Shizuku.requestPermission(101)
            return
        }
        binding = true
        output.text = "Connecting middleware…"
        Shizuku.bindUserService(serviceArgs, connection)
    }

    private fun runProbe(broker: ISocketBroker?) {
        if (TaskRuntime.isActive) { output.text = "Stop the workflow task before running a probe"; return }
        if (!TaskRuntime.beginProbe()) return
        val runOcr = ocrMode
        thread(name = "baas-runtime-probe") {
            val report = try {
                broker?.attachClient(clientOwner)
                BundledAssets.sync(this, "baas-source.zip")
                assets.open("probe-fixtures.zip").use { input ->
                    File(filesDir, "probe-fixtures.zip").outputStream().use { input.copyTo(it) }
                }
                if (!Python.isStarted()) Python.start(AndroidPlatform(applicationContext))
                val python = Python.getInstance()
                val runtime = python.getModule("runtime_probe").callAttr("run", filesDir.absolutePath).toString()
                File(filesDir, "probe-report.json").writeText(runtime)
                if (runOcr) {
                    BundledAssets.sync(this, "ocr-resources.zip")
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
            runOnUiThread { TaskRuntime.finishProbe(); if (!isDestroyed) output.text = report }
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderListener)
        if (companionBroker != null || binding) Shizuku.unbindUserService(serviceArgs, connection, false)
        super.onDestroy()
    }
}
