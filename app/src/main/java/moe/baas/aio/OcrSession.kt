package moe.baas.aio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Bind from a worker thread; closing releases only our ordinary Android service. */
class OcrSession(private val context: Context) : AutoCloseable {
    private val connected = CountDownLatch(1)
    private val exited = CountDownLatch(1)
    private var service: IOcrService? = null
    private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IOcrService.Stub.asInterface(binder)
            binder.linkToDeath({ exited.countDown() }, 0)
            connected.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName) { exited.countDown() }
        override fun onNullBinding(name: ComponentName) { connected.countDown() }
        override fun onBindingDied(name: ComponentName) { connected.countDown(); exited.countDown() }
    }
    init {
        bound = context.bindService(Intent(context, OcrService::class.java), connection, Context.BIND_AUTO_CREATE)
        try {
            check(bound && connected.await(15, TimeUnit.SECONDS) && service != null) { "Cannot bind Android OCR service" }
        } catch (error: Throwable) { close(); throw error }
    }
    fun start(projectDir: String): String {
        checkNotNull(service).start(projectDir)
        return checkNotNull(service).identity()
    }
    fun stop() {
        checkNotNull(service).stop()
        close()
        check(exited.await(5, TimeUnit.SECONDS)) { "OCR service did not exit" }
    }
    override fun close() {
        if (bound) { context.unbindService(connection); bound = false }
    }
}
