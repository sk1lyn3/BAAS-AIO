package moe.baas.aio

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/** filesDir copies of large APK assets, refreshed only after the installed APK changes. */
object BundledAssets {
    @Synchronized
    fun sync(context: Context, vararg names: String) {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val install = "${info.longVersionCode}:${info.lastUpdateTime}"
        for (name in names) {
            val target = File(context.filesDir, name)
            val marker = File(context.filesDir, "$name.stamp")
            if (target.isFile && marker.isFile && marker.readText() == "$install:${target.length()}") continue
            marker.delete()
            val partial = File(context.filesDir, "$name.partial")
            context.assets.open(name).use { input -> write(partial) { input.copyTo(it) } }
            check(partial.renameTo(target)) { "Cannot replace $name" }
            val stamp = File(context.filesDir, "$name.stamp.partial")
            write(stamp) { it.write("$install:${target.length()}".toByteArray()) }
            check(stamp.renameTo(marker)) { "Cannot record $name" }
        }
    }

    private fun write(file: File, body: (FileOutputStream) -> Unit) =
        FileOutputStream(file).use { body(it); it.fd.sync() }
}
