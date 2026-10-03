package moe.baas.aio

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
    override fun openCompanionSocket(): ParcelFileDescriptor = try {
        LocalSocket().use { socket ->
            socket.connect(LocalSocketAddress("baas-companion-v1", LocalSocketAddress.Namespace.ABSTRACT))
            socket.soTimeout = 20000
            Os.setsockoptTimeval(socket.fileDescriptor, OsConstants.SOL_SOCKET,
                OsConstants.SO_SNDTIMEO, StructTimeval.fromMillis(20000))
            ParcelFileDescriptor.dup(socket.fileDescriptor)
        }
    } catch (error: Exception) {
        throw IllegalStateException("Companion daemon unavailable; open Companion and grant Shizuku first", error)
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
