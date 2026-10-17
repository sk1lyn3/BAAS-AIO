package moe.baas.aio

import android.content.Context
import java.io.File

/** Generated core data (defaults, static tables, activities) from the core this process runs. */
object CoreData {
    @Volatile private var directory: File? = null
    @Volatile private var resolved = false

    @Synchronized
    private fun directory(context: Context): File? {
        if (!resolved) {
            directory = try {
                CoreUpdater.reconcile(context)
                val root = CoreUpdater.root(context)
                // Pending data becomes visible only after Python has accepted the core.
                val active = CoreBundle.id(CoreBundle.readState(root), "active") ?: CoreBundle.BUNDLED
                active.takeIf { it != CoreBundle.BUNDLED }?.let { File(root, "ext/$it/data") }?.takeIf { it.isDirectory }
            } catch (_: Exception) { null }
            resolved = true
        }
        return directory
    }

    fun read(context: Context, name: String): String =
        directory(context)?.let { File(it, name) }?.takeIf { it.isFile }?.readText()
            ?: context.assets.open(name).bufferedReader().use { it.readText() }
}
