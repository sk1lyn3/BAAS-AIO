package moe.baas.aio

import android.os.ParcelFileDescriptor
import android.system.Os
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.UUID
import java.io.IOException

data class CompanionFrame(val metadata: String, val png: ByteArray)
class CompanionCommandException(message: String) : RuntimeException(message)

/** Android-local transport: two authorized descriptors, no desktop or ADB dependency. */
class CompanionBridge(private val broker: ISocketBroker) : AutoCloseable {
    private var input = openChannel()
    private var capture = try { openChannel() }
        catch (error: Throwable) { input.close(); throw error }

    // Called by the single workflow worker after resume, before any input is sent.
    fun reconnect() {
        close()
        input = openChannel()
        capture = try { openChannel() } catch (error: Throwable) { input.close(); throw error }
    }

    private fun openChannel(): Channel {
        var last: Throwable? = null
        repeat(3) {
            val channel = Channel(broker.openCompanionSocket())
            try {
                channel.request(JSONObject().put("cmd", "ping"))
                return channel
            } catch (error: IllegalStateException) {
                channel.close(); last = error
                if (error.cause !is IOException && error.cause !is android.system.ErrnoException) throw error
                Thread.sleep(25)
            }
        }
        throw checkNotNull(last)
    }

    fun command(json: String): String {
        val request = JSONObject(json)
        require(request.optString("cmd") != "screenshot") { "Use screenshot()" }
        return input.request(request).metadata
    }

    fun screenshot(): CompanionFrame = capture.request(JSONObject().put("cmd", "screenshot"))
    fun brokerInfo(): String = broker.ping()
    fun probeState(): String = broker.readProbeState()
    fun currentPackage(): String = broker.currentPreviewPackage()
    override fun close() { try { input.close() } finally { capture.close() } }

    private class Channel(private val descriptor: ParcelFileDescriptor) : AutoCloseable {
        @Synchronized fun request(request: JSONObject): CompanionFrame {
            try {
                val id = UUID.randomUUID().toString()
                request.put("apiVersion", 1).put("requestId", id)
                val body = request.toString().toByteArray(Charsets.UTF_8)
                require(body.size in 1..65536)
                write(ByteBuffer.allocate(4).putInt(body.size).array())
                write(body)
                val size = ByteBuffer.wrap(read(4)).int
                if (size !in 1..65536) throw IOException("Invalid Companion header size")
                val reply = JSONObject(String(read(size), Charsets.UTF_8))
                if (reply.optInt("apiVersion") != 1 || reply.optString("requestId") != id)
                    throw IOException("Companion reply mismatch")
                val payloadSize = reply.optInt("payloadBytes", 0)
                if (payloadSize !in 0..134217728) throw IOException("Invalid Companion payload size")
                val payload = read(payloadSize)
                if (!reply.optBoolean("ok")) throw CompanionCommandException(reply.optString("error", "Companion command failed"))
                return CompanionFrame(reply.toString(), payload)
            } catch (error: android.system.ErrnoException) {
                close()
                throw IllegalStateException("Companion connection failed; command was not replayed", error)
            } catch (error: IOException) {
                close()
                throw IllegalStateException("Companion connection failed; command was not replayed", error)
            } catch (error: org.json.JSONException) {
                close()
                throw IllegalStateException("Invalid Companion response; command was not replayed", error)
            }
        }

        private fun read(size: Int): ByteArray {
            val bytes = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val count = Os.read(descriptor.fileDescriptor, bytes, offset, size - offset)
                if (count <= 0) throw IOException("Companion closed its connection")
                offset += count
            }
            return bytes
        }

        private fun write(bytes: ByteArray) {
            var offset = 0
            while (offset < bytes.size) {
                val count = Os.write(descriptor.fileDescriptor, bytes, offset, bytes.size - offset)
                if (count <= 0) throw IOException("Companion write failed")
                offset += count
            }
        }

        @Synchronized override fun close() { descriptor.close() }
    }
}
