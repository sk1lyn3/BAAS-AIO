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
        const val SERVICE_VERSION = 14
        fun serviceArgs(context: android.content.Context) =
            rikka.shizuku.Shizuku.UserServiceArgs(android.content.ComponentName(context, SocketBroker::class.java))
                .daemon(false).processNameSuffix("aio-broker").debuggable(true)
                .version(SERVICE_VERSION).tag("baas-aio-broker")
        private const val SOCKET_NAME = "baas-aio-companion-v1"
    }
    private var service: CompanionUserService? = null
    private var clientOwner: android.os.IBinder? = null
    private val clientDeath = android.os.IBinder.DeathRecipient { shutdown() }

    @Synchronized override fun attachClient(owner: android.os.IBinder) {
        if (clientOwner == owner) return
        try { clientOwner?.unlinkToDeath(clientDeath, 0) } catch (_: java.util.NoSuchElementException) { }
        owner.linkToDeath(clientDeath, 0)
        clientOwner = owner
    }

    @Synchronized private fun companion(): CompanionUserService =
        service ?: CompanionUserService().also { service = it }

    @Synchronized private fun shutdown(): Nothing {
        val runtime = service
        try {
            runtime?.launchedGamePackages()?.forEach { name ->
                try { stopGame(name) } catch (error: Exception) {
                    android.util.Log.e("BaasRuntime", "Cannot stop $name during shutdown", error)
                }
            }
        } finally {
            try { runtime?.close() } finally { kotlin.system.exitProcess(0) }
        }
    }

    @Synchronized override fun preparePreview(createIfMissing: Boolean): String {
        val service = companion()
        val state = org.json.JSONObject(service.stateJson)
        if (!state.getBoolean("alive") && createIfMissing) {
            check(service.startVirtualDisplay(1280, 720, 240) >= 0) { "Cannot create preview display" }
            val activity = if (BuildConfig.DEBUG) "moe.baas.aio.ProbeActivity" else "moe.baas.aio.PreviewIdleActivity"
            check(service.launchApp("moe.baas.aio", activity)) { "Cannot launch preview target" }
        }
        return service.stateJson
    }

    @Synchronized override fun launchPreviewGame(packageName: String): String {
        require(packageName in listOf("com.YostarJP.BlueArchive", "com.nexon.bluearchive", "com.RoamingStar.BlueArchive"))
        val service = companion()
        if (!org.json.JSONObject(service.stateJson).getBoolean("alive")) {
            check(service.startVirtualDisplay(1280, 720, 240) >= 0) { "Cannot create game preview display" }
        }
        check(service.launchApp(packageName, null)) { "Cannot launch $packageName; check game installation" }
        return service.stateJson
    }

    @Synchronized override fun closePreviewGame(): String {
        val runtime = companion()
        runtime.launchedGamePackages().forEach { stopGame(it) }
        runtime.stopVirtualDisplay()
        return runtime.stateJson
    }

    override fun gameOptions(packageName: String): String {
        require(packageName == "com.nexon.bluearchive")
        val options = File("/sdcard/Android/data/$packageName/files/DeviceOption")
        check(options.isFile) { "Global game language settings missing; launch the game and select its language first" }
        return options.readText()
    }

    override fun stopGame(packageName: String) {
        require(packageName in listOf("com.YostarJP.BlueArchive", "com.nexon.bluearchive", "com.RoamingStar.BlueArchive"))
        val manager = Class.forName("android.app.ActivityManager").getDeclaredMethod("getService").invoke(null)
        Class.forName("android.app.IActivityManager").getMethod("forceStopPackage", String::class.java, Int::class.javaPrimitiveType)
            .invoke(manager, packageName, 0)
        service?.forgetGame(packageName)
    }

    override fun setPreviewSurface(surface: Surface?, owner: android.os.IBinder?) {
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
        File("/data/user/0/moe.baas.aio/files/probe_state.json").readText()
    } catch (error: Exception) { throw IllegalStateException("Probe state unavailable", error) }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == 16777115) { shutdown() }
        return super.onTransact(code, data, reply, flags)
    }
}
