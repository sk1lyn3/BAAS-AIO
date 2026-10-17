package moe.baas.aio

import android.annotation.SuppressLint

import android.view.Surface
import android.view.MotionEvent
import moe.baas.aio.companion.CompanionUserService
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructTimeval
import java.io.File

/** AIO owns its Companion runtime inside this Shizuku root/ADB process. */
class SocketBroker : ISocketBroker.Stub() {
    companion object {
        const val SERVICE_VERSION = 42
        fun serviceArgs(context: android.content.Context) =
            rikka.shizuku.Shizuku.UserServiceArgs(android.content.ComponentName(context, SocketBroker::class.java))
                .daemon(false).processNameSuffix("aio-broker").debuggable(BuildConfig.DEBUG)
                .version(SERVICE_VERSION).tag("baas-aio-broker")
        private const val SOCKET_NAME = "baas-aio-companion-v1"
    }
    private var service: CompanionUserService? = null
    @Volatile private var managedScreenHidden = false
    @Volatile private var screenKeepAlive = false
    private val screenHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val screenPulse = object : Runnable {
        override fun run() {
            if (!screenKeepAlive) return
            try { managedActivityPulse() }
            catch (error: Exception) {
                android.util.Log.e("BaasRuntime", "Cannot keep the physical display awake", error)
                try { endManagedScreen(false) } catch (_: Exception) { }
                return
            }
            if (screenKeepAlive) screenHandler.postDelayed(this, 4000)
        }
    }
    private var clientOwner: android.os.IBinder? = null
    private var clientDeath: android.os.IBinder.DeathRecipient? = null

    @Synchronized override fun attachClient(owner: android.os.IBinder) {
        if (clientOwner == owner) return
        val death = android.os.IBinder.DeathRecipient {
            synchronized(this) { if (clientOwner == owner) shutdown() }
        }
        owner.linkToDeath(death, 0)
        clientDeath?.let { previous ->
            try { clientOwner?.unlinkToDeath(previous, 0) } catch (_: java.util.NoSuchElementException) { }
        }
        clientOwner = owner
        clientDeath = death
    }

    @Synchronized private fun companion(): CompanionUserService =
        service ?: CompanionUserService().also { service = it }

    @Synchronized private fun shutdown(): Nothing {
        if (BuildConfig.DEBUG) android.util.Log.d("BaasRuntime", "Runtime shutdown: client owner died or destroy requested; display=${service?.displayId}")
        val runtime = service
        try {
            runtime?.launchedGamePackages()?.forEach { name ->
                try { stopGame(name) } catch (error: Exception) {
                    android.util.Log.e("BaasRuntime", "Cannot stop $name during shutdown", error)
                }
            }
        } finally {
            try {
                try { endManagedScreen(false) } finally { runtime?.close() }
            } finally { kotlin.system.exitProcess(0) }
        }
    }

    @Synchronized override fun preparePreview(createIfMissing: Boolean): String {
        val service = companion()
        val state = org.json.JSONObject(service.stateJson)
        if (!state.getBoolean("alive") && createIfMissing) {
            if (BuildConfig.DEBUG) android.util.Log.d("BaasRuntime", "Create display for preview")
            check(service.startVirtualDisplay(1280, 720, 240) >= 0) { "Cannot create preview display" }
            val activity = "moe.baas.aio.PreviewIdleActivity"
            check(service.launchApp(BuildConfig.APPLICATION_ID, activity)) { "Cannot launch preview target" }
        }
        val reply = org.json.JSONObject(service.stateJson)
        val packageName = if (reply.optBoolean("alive"))
            runCatching { currentPreviewPackage(reply.optInt("displayId", -1)) }.getOrDefault("") else ""
        return reply.put("gameRunning", packageName in listOf("com.YostarJP.BlueArchive", "com.nexon.bluearchive", "com.RoamingStar.BlueArchive", "com.RoamingStar.BlueArchive.bilibili")).toString()
    }

    @Synchronized override fun launchPreviewGame(packageName: String): String {
        if (BuildConfig.DEBUG) android.util.Log.d("BaasRuntime", "Launch preview game package=$packageName display=${service?.displayId}")
        require(packageName in listOf("com.YostarJP.BlueArchive", "com.nexon.bluearchive", "com.RoamingStar.BlueArchive", "com.RoamingStar.BlueArchive.bilibili"))
        val service = companion()
        if (!org.json.JSONObject(service.stateJson).getBoolean("alive")) {
            check(service.startVirtualDisplay(1280, 720, 240) >= 0) { "Cannot create game preview display" }
        }
        check(service.launchApp(packageName, null)) { "Cannot launch $packageName; check game installation" }
        // Disable repeated launches immediately; subsequent refreshes check the actual game.
        return org.json.JSONObject(service.stateJson).put("gameRunning", true).toString()
    }

    @Synchronized override fun closePreviewGame(): String {
        if (BuildConfig.DEBUG) android.util.Log.d("BaasRuntime", "Close game/display requested display=${service?.displayId}", Throwable("Lifecycle caller"))
        val runtime = companion()
        runtime.launchedGamePackages().forEach { stopGame(it) }
        runtime.stopVirtualDisplay()
        return runtime.stateJson
    }

    override fun debugRuntimeState(includeFocus: Boolean): String {
        if (!BuildConfig.DEBUG) return "{}"
        val context = moe.baas.aio.companion.SystemAccess.context()
        val state = org.json.JSONObject(companion().stateJson)
            .put("interactive", context.getSystemService(android.os.PowerManager::class.java).isInteractive)
            .put("keyguardLocked", context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
            .put("managedPanelHidden", managedScreenHidden)
            .put("runtimeUid", android.os.Process.myUid())
        val systemDisplay = context.getSystemService(android.hardware.display.DisplayManager::class.java)
            .getDisplay(state.optInt("displayId", -1))
        state.put("systemDisplayPresent", systemDisplay != null).put("systemDisplayState", systemDisplay?.state)
        val gamePids = org.json.JSONObject()
        for (name in listOf("com.YostarJP.BlueArchive", "com.nexon.bluearchive", "com.RoamingStar.BlueArchive", "com.RoamingStar.BlueArchive.bilibili")) {
            runCatching {
                val process = ProcessBuilder("pidof", name).redirectErrorStream(true).start()
                if (process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
                    gamePids.put(name, process.inputStream.bufferedReader().use { it.readText().trim() })
                } else { process.destroy(); gamePids.put(name, "timeout") }
            }.onFailure { gamePids.put(name, "unavailable: ${it.message}") }
        }
        state.put("gamePids", gamePids)
        runCatching { state.put("currentPackage", currentPreviewPackage(state.optInt("displayId", -1))) }
            .onFailure { state.put("packageError", it.toString()) }
        if (includeFocus) runCatching {
            val process = ProcessBuilder("dumpsys", "-t", "1", "input").redirectErrorStream(true).start()
            val dump = process.inputStream.bufferedReader().use { it.readText() }; process.waitFor()
            val lines = dump.lines()
            state.put("inputFocus", lines.flatMapIndexed { index, line ->
                if (line.trim().startsWith("FocusedWindows:") || line.trim().startsWith("FocusedApplications:"))
                    lines.subList(index, minOf(index + 5, lines.size)) else emptyList()
            }.joinToString("\n").take(6000))

        }.onFailure { state.put("focusError", it.toString()) }
        return state.toString()
    }

    override fun awaitGameInput(packageName: String) {
        require(packageName in listOf("com.YostarJP.BlueArchive", "com.nexon.bluearchive", "com.RoamingStar.BlueArchive", "com.RoamingStar.BlueArchive.bilibili"))
        val displayId = companion().displayId
        check(displayId >= 0) { "No virtual display for game input" }
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            check(companion().displayId == displayId) { "Display changed while waiting for game input" }
            val process = ProcessBuilder("dumpsys", "-t", "1", "input").redirectErrorStream(true).start()
            val dump = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            var focusedWindows = false
            for (line in dump.lineSequence()) {
                val value = line.trim()
                if (value.startsWith("FocusedWindows:")) focusedWindows = !value.endsWith("<none>")
                else if (focusedWindows && value.startsWith("displayId=")) {
                    if (value.startsWith("displayId=$displayId,") && value.contains("$packageName/")) return
                } else focusedWindows = false
            }
            Thread.sleep(100)
        }
        error("Game input window did not become ready on display $displayId: $packageName")
    }

    @Synchronized override fun beginManagedScreen() {
        // The power key can restore the panel without updating our session state.
        check(companion().setPhysicalScreenOff(true)) { "Cannot turn off the physical panel" }
        managedScreenHidden = true
        screenKeepAlive = true
        screenHandler.removeCallbacks(screenPulse)
        screenHandler.post(screenPulse)
    }

    @Synchronized override fun endManagedScreen(sleep: Boolean) {
        if (!sleep && !managedScreenHidden) return
        val runtime = companion()
        if (sleep) {
            // Sleep the primary display, independently of the virtual display's lifetime.
            val power = moe.baas.aio.companion.SystemAccess.context()
                .getSystemService(android.os.PowerManager::class.java)
            if (power.isInteractive) {
                check(moe.baas.aio.companion.SystemAccess.keyEvent(0, android.view.KeyEvent.KEYCODE_SLEEP)) {
                    "Cannot put the device to sleep"
                }
                val deadline = android.os.SystemClock.elapsedRealtime() + 2000
                while (power.isInteractive && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
                check(!power.isInteractive) { "Device did not enter sleep" }
            }
        } else {
            check(runtime.setPhysicalScreenOff(false)) { "Cannot restore the physical panel" }
        }
        managedScreenHidden = false
        screenKeepAlive = false
        screenHandler.removeCallbacks(screenPulse)
    }

    @Synchronized override fun finishManagedScreen() {
        // Leave panel and lock state to the user; allow normal idle timeout again.
        screenKeepAlive = false
        screenHandler.removeCallbacks(screenPulse)
    }

    override fun managedActivityPulse() {
        moe.baas.aio.companion.SystemAccess.userActivity(0)
    }

    override fun gameOptions(packageName: String): String {
        require(packageName == "com.nexon.bluearchive")
        val options = File("/sdcard/Android/data/$packageName/files/DeviceOption")
        check(options.isFile) { "Global game language settings missing; launch the game and select its language first" }
        return options.readText()
    }

    override fun writeLocalizeConfig(packageName: String, content: String) {
        require(packageName in listOf("com.RoamingStar.BlueArchive", "com.RoamingStar.BlueArchive.bilibili")) {
            "Anti-censorship is only supported for CN and Bilibili"
        }
        require(content.toByteArray(Charsets.UTF_8).size in 1..4096 &&
            content.lineSequence().any { it.trim() == "IsLocalize=false" }) { "Invalid LocalizeConfig" }
        val directory = File("/sdcard/Android/data/$packageName/files")
        check(directory.isDirectory) { "请先启动一次对应游戏，再使用一键反和谐" }
        // Shizuku supplies root/shell access to the game's external data directory.
        val temporary = File(directory, "LocalizeConfig.txt.baas-tmp")
        try {
            temporary.writeText(content, Charsets.UTF_8)
            check(temporary.readText(Charsets.UTF_8) == content) { "LocalizeConfig verification failed" }
            check(temporary.renameTo(File(directory, "LocalizeConfig.txt"))) { "Cannot replace LocalizeConfig" }
        } finally {
            temporary.delete()
        }
    }

    override fun stopGame(packageName: String) {
        if (BuildConfig.DEBUG) android.util.Log.d("BaasRuntime", "Force-stop game package=$packageName display=${service?.displayId}", Throwable("Lifecycle caller"))
        require(packageName in listOf("com.YostarJP.BlueArchive", "com.nexon.bluearchive", "com.RoamingStar.BlueArchive", "com.RoamingStar.BlueArchive.bilibili"))
        val manager = Class.forName("android.app.ActivityManager").getDeclaredMethod("getService").invoke(null)
        Class.forName("android.app.IActivityManager").getMethod("forceStopPackage", String::class.java, Int::class.javaPrimitiveType)
            .invoke(manager, packageName, 0)
        service?.forgetGame(packageName)
    }

    override fun setPreviewSurface(surface: Surface?, owner: android.os.IBinder?) {
        if (BuildConfig.DEBUG) android.util.Log.d("BaasRuntime", "Preview surface ${if (surface == null) "detach" else "attach"} valid=${surface?.isValid} owner=$owner ownerAlive=${owner?.isBinderAlive} display=${service?.displayId}")
        // Direct calls transfer ownership to the runtime, which releases this Parcel copy.
        companion().setPreviewSurface(surface, owner)
    }
    override fun touch(event: MotionEvent): Boolean = companion().touch(event)

    // Runs in the Shizuku root/ADB process, never the application's UID.
    @SuppressLint("BlockedPrivateApi")
    override fun currentPreviewPackage(displayId: Int): String {
        if (displayId < 0) return ""
        val manager = Class.forName("android.app.ActivityTaskManager").getDeclaredMethod("getService").invoke(null)
        val method = Class.forName("android.app.IActivityTaskManager").methods.single {
            it.name == "getTasks" && it.parameterTypes.size == 4
        }
        val tasks = method.invoke(manager, 20, false, false, displayId) as List<*>
        return tasks.filterIsInstance<android.app.ActivityManager.RunningTaskInfo>()
            .firstOrNull { it.javaClass.getField("displayId").getInt(it) == displayId }
            ?.topActivity?.packageName.orEmpty()
    }

    private fun connectSocket(): ParcelFileDescriptor =
        LocalSocket().use { socket ->
            socket.connect(LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT))
            socket.soTimeout = 20000
            Os.setsockoptTimeval(socket.fileDescriptor, OsConstants.SOL_SOCKET,
                OsConstants.SO_SNDTIMEO, StructTimeval.fromMillis(20000))
            ParcelFileDescriptor.dup(socket.fileDescriptor)
        }

    override fun openCompanionSocket(): ParcelFileDescriptor = try {
        companion()
        connectSocket()
    } catch (error: Exception) {
        val cause = (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
        throw IllegalStateException("AIO device runtime failed: ${cause.message ?: cause.javaClass.simpleName}; check Shizuku permission", cause)
    }

    override fun ping() = "uid=${android.os.Process.myUid()} pid=${android.os.Process.myPid()} runtime=embedded"

    // Fixed debug target, used to verify actual Android-delivered events without ADB.
    override fun readProbeState(): String = try {
        check(BuildConfig.DEBUG) { "Probe state is only available in debug builds" }
        File("/data/user/0/moe.baas.aio/files/probe_state.json").readText()
    } catch (error: Exception) { throw IllegalStateException("Probe state unavailable", error) }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == 16777115) { shutdown() }
        return super.onTransact(code, data, reply, flags)
    }
}
