package moe.baas.aio

import android.view.Surface
import android.view.MotionEvent
import moe.baas.companion.ICompanionService
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructTimeval
import java.io.File

/** Shizuku authorizes this broker; Companion continues to accept only root/ADB peers. */
class SocketBroker : ISocketBroker.Stub() {
    companion object { const val SERVICE_VERSION = 8 }
    private var service: ICompanionService? = null
    private var clientOwner: android.os.IBinder? = null
    private val clientDeath = android.os.IBinder.DeathRecipient { kotlin.system.exitProcess(0) }

    @Synchronized override fun attachClient(owner: android.os.IBinder) {
        if (clientOwner == owner) return
        try { clientOwner?.unlinkToDeath(clientDeath, 0) } catch (_: java.util.NoSuchElementException) { }
        owner.linkToDeath(clientDeath, 0)
        clientOwner = owner
    }

    @Synchronized private fun companion(): ICompanionService {
        service?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        val authority = "moe.baas.companion.bridge"
        val manager = Class.forName("android.app.ActivityManager").getDeclaredMethod("getService").invoke(null)
        val managerType = Class.forName("android.app.IActivityManager")
        val token = android.os.Binder()
        val get = managerType.methods.single { it.name == "getContentProviderExternal" && it.parameterTypes.size == 4 }
        val holder = get.invoke(manager, authority, 0, token, "baas-aio-preview")
            ?: error("Companion bridge provider unavailable")
        try {
            val provider = holder.javaClass.getField("provider").get(holder)
            val call = Class.forName("android.content.IContentProvider").methods.single { it.name == "call" && it.parameterTypes.size == 5 }
            val attribution = android.content.AttributionSource.Builder(android.os.Process.myUid()).setPackageName("android").build()
            val result = call.invoke(provider, attribution, authority, "binder", null, null) as android.os.Bundle
            return ICompanionService.Stub.asInterface(checkNotNull(result.getBinder("service"))).also { service = it }
        } finally {
            val remove = managerType.methods.single { it.name == "removeContentProviderExternal" && it.parameterTypes.size == 2 }
            remove.invoke(manager, authority, token)
        }
    }

    @Synchronized override fun preparePreview(createIfMissing: Boolean): String {
        val service = companion()
        val state = org.json.JSONObject(service.stateJson)
        if (!state.getBoolean("alive") && createIfMissing) {
            check(service.startVirtualDisplay(1280, 720, 240) >= 0) { "Cannot create preview display" }
            check(service.launchApp("moe.baas.companion", "moe.baas.companion.ProbeActivity")) { "Cannot launch preview probe" }
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

    override fun setPreviewSurface(surface: Surface?, owner: android.os.IBinder?) {
        try { companion().setPreviewSurface(surface, owner) } finally { surface?.release() }
    }
    override fun touch(event: MotionEvent): Boolean = try { companion().touch(event) } finally { event.recycle() }

    override fun currentPreviewPackage(): String {
        val displayId = org.json.JSONObject(companion().stateJson).getInt("displayId")
        val manager = Class.forName("android.app.ActivityTaskManager").getDeclaredMethod("getService").invoke(null)
        val method = Class.forName("android.app.IActivityTaskManager").methods.single {
            it.name == "getTasks" && it.parameterTypes.size == 4
        }
        val tasks = method.invoke(manager, 20, false, false, displayId) as List<*>
        return tasks.filterIsInstance<android.app.ActivityManager.RunningTaskInfo>()
            .firstOrNull { it.javaClass.getField("displayId").getInt(it) == displayId }
            ?.topActivity?.packageName.orEmpty()
    }

    override fun openCompanionSocket(): ParcelFileDescriptor = try {
        companion() // Bind the installed, already authorized Companion without opening its UI.
        LocalSocket().use { socket ->
            socket.connect(LocalSocketAddress("baas-companion-v1", LocalSocketAddress.Namespace.ABSTRACT))
            socket.soTimeout = 20000
            Os.setsockoptTimeval(socket.fileDescriptor, OsConstants.SOL_SOCKET,
                OsConstants.SO_SNDTIMEO, StructTimeval.fromMillis(20000))
            ParcelFileDescriptor.dup(socket.fileDescriptor)
        }
    } catch (error: Exception) {
        throw IllegalStateException("Companion connection failed: ${error.message}; check Companion installation and Shizuku permission", error)
    }

    override fun ping() = "uid=${android.os.Process.myUid()} pid=${android.os.Process.myPid()}"

    // Fixed debug target, used to verify actual Android-delivered events without ADB.
    override fun readProbeState(): String = try {
        File("/data/user/0/moe.baas.companion/files/probe_state.json").readText()
    } catch (error: Exception) { throw IllegalStateException("Probe state unavailable", error) }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == 16777115) { kotlin.system.exitProcess(0) }
        return super.onTransact(code, data, reply, flags)
    }
}
