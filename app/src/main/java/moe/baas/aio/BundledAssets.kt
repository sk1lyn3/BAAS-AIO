package moe.baas.aio

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Reuse verified extracted assets; ZIPs exist only while rebuilding their cache. */
object BundledAssets {
    @Synchronized
    fun sync(context: Context, vararg names: String) {
        for (name in names) {
            val root = File(context.filesDir, when (name) {
                "baas-source.zip" -> "baas-core"
                "ocr-resources.zip" -> "workflow-ocr"
                else -> error("Unknown asset bundle: $name")
            })
            val index = context.assets.open(name.removeSuffix(".zip") + ".index.json")
                .use { it.readBytes() }
            val entries = JSONArray(String(index, Charsets.UTF_8))
            val signature = hex(MessageDigest.getInstance("SHA-256").digest(index))
            // Older APKs replace only resource/; keep its completion marker with the models.
            val marker = if (name == "ocr-resources.zip") "resource/.asset-index.sha256" else ".asset-index.sha256"
            val cached = File(root, marker).let { it.isFile && it.readText() == signature } &&
                (0 until entries.length()).all { i ->
                    val row = entries.getJSONArray(i)
                    File(root, row.getString(0)).let { it.isFile && it.length() == row.getLong(1) }
                }
            if (!cached) {
                val partial = File(context.filesDir, "$name.partial")
                val staging = File(context.filesDir, root.name + ".staging")
                staging.deleteRecursively()
                try {
                    context.assets.open(name).use { input -> write(partial) { input.copyTo(it) } }
                    ZipFile(partial).use { archive ->
                        val base = staging.canonicalPath + File.separator
                        for (i in 0 until entries.length()) {
                            val row = entries.getJSONArray(i)
                            val path = row.getString(0)
                            val target = File(staging, path)
                            check(target.canonicalPath.startsWith(base)) { "Invalid asset path: $path" }
                            val entry = checkNotNull(archive.getEntry(path)) { "Missing asset: $path" }
                            target.parentFile!!.mkdirs()
                            val digest = MessageDigest.getInstance("SHA-256")
                            val size = DigestInputStream(archive.getInputStream(entry), digest).use { input ->
                                target.outputStream().use { input.copyTo(it, 1 shl 16) }
                            }
                            check(size == row.getLong(1) && hex(digest.digest()) == row.getString(2)) {
                                "Asset verification failed: $path"
                            }
                        }
                    }
                    write(File(staging, marker)) { it.write(signature.toByteArray()) }
                    if (root.exists()) check(root.deleteRecursively()) { "Cannot replace ${root.name}" }
                    check(staging.renameTo(root)) { "Cannot install ${root.name}" }
                } finally {
                    partial.delete()
                    staging.deleteRecursively()
                }
            }
            // Remove copies/stamps left by older APKs, including on a cache hit.
            for (suffix in listOf("", ".stamp", ".partial", ".stamp.partial")) {
                File(context.filesDir, name + suffix).delete()
            }
            File(context.filesDir, root.name + ".staging").deleteRecursively()
        }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun write(file: File, body: (FileOutputStream) -> Unit) =
        FileOutputStream(file).use { body(it); it.fd.sync() }
}
