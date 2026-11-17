package moe.baas.aio

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Ordinary task history, independent of verbose Debug diagnostics and the UI window. */
internal object TaskLog {
    const val WINDOW = 300
    val session = UUID.randomUUID().toString()
    data class Window(val entries: List<JSONObject>, val sequence: Long)
    @Volatile private var directory: File? = null
    private val worker by lazy {
        Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "baas-task-log").apply { isDaemon = true } }
    }
    private val pending = ArrayDeque<ByteArray>()
    private var pendingBytes = 0
    private var dropped = 0
    private var scheduled = false
    private const val FILE_BYTES = 5 * 1024 * 1024L

    fun attach(root: File) = synchronized(pending) {
        if (directory == null) {
            directory = File(root, "logs")
            if (pending.isNotEmpty()) worker.execute(::drain)
        }
    }

    fun append(entry: JSONObject) {
        val bytes = (entry.toString() + "\n").toByteArray(Charsets.UTF_8)
        synchronized(pending) {
            pending.addLast(bytes); pendingBytes += bytes.size
            // Bound memory even if storage is stalled; never block a workflow on log I/O.
            while (pendingBytes > 1024 * 1024) { pendingBytes -= pending.removeFirst().size; dropped++ }
            if (!scheduled) { scheduled = true; worker.schedule(::drain, 500, TimeUnit.MILLISECONDS) }
        }
    }

    fun flush() { worker.execute(::drain) }

    // Only the log worker touches archive files, including readers and rotation.
    private fun drain() {
        val root = directory ?: return
        val batch = synchronized(pending) {
            val items = pending.toMutableList()
            if (dropped > 0) items.add(0, (JSONObject().put("timeMs", System.currentTimeMillis())
                .put("level", 2).put("message", "Log buffer overflow: skipped $dropped entries").toString() + "\n")
                .toByteArray(Charsets.UTF_8))
            pending.clear(); pendingBytes = 0; dropped = 0; scheduled = false
            items
        }
        if (batch.isEmpty()) return
        var output: OutputStream? = null
        try {
            check(root.isDirectory || root.mkdirs()) { "Cannot create task log directory" }
            val file = File(root, "task.jsonl")
            val previous = File(root, "task.previous.jsonl")
            var size = file.length()
            for (bytes in batch) {
                if (size + bytes.size + 1 > FILE_BYTES) {
                    output?.close(); output = null
                    check(!previous.exists() || previous.delete()) { "Cannot remove old task log" }
                    check(!file.exists() || file.renameTo(previous)) { "Cannot rotate task log" }
                    size = 0
                }
                if (output == null) {
                    val unfinished = size > 0 && RandomAccessFile(file, "r").use { it.seek(size - 1); it.read() != 10 }
                    output = FileOutputStream(file, true).buffered(64 * 1024)
                    if (unfinished) { output.write(10); size++ }
                }
                output.write(bytes); size += bytes.size
            }
        } catch (error: Exception) {
            Log.w("BaasTaskLog", "Cannot persist task history", error)
        } finally {
            try { output?.close() } catch (error: Exception) { Log.w("BaasTaskLog", "Cannot flush task history", error) }
        }
    }

    private fun files(): List<File> = directory?.let { root ->
        listOf(File(root, "task.previous.jsonl"), File(root, "task.jsonl")).filter { it.isFile }
    }.orEmpty()

    fun matches(entry: JSONObject, query: String, level: Int) =
        (level == 0 || entry.optInt("level", 1).coerceIn(0, 4) == level) &&
            entry.optString("message").contains(query, ignoreCase = true)

    /** Called from Dispatchers.IO. Flush queued entries before taking a consistent snapshot. */
    fun tail(query: String, level: Int): Window = worker.submit<Window> {
        drain()
        val tail = ArrayDeque<JSONObject>()
        var sequence = 0L
        for (file in files()) file.useLines { lines -> lines.forEach { line ->
            val entry = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
            if (entry.optString("session") == session) sequence = maxOf(sequence, entry.optLong("sequence"))
            if (matches(entry, query, level)) {
                tail.addLast(entry)
                if (tail.size > WINDOW) tail.removeFirst()
            }
        } }
        Window(tail.toList(), sequence)
    }.get()

    fun exportTo(output: OutputStream) {
        // Snapshot at most 10 MiB locally; a slow document provider never holds up the log writer.
        val snapshot = worker.submit<List<ByteArray>> { drain(); files().map { it.readBytes() } }.get()
        snapshot.forEach { bytes ->
            output.write(bytes)
            if (bytes.isNotEmpty() && bytes.last() != 10.toByte()) output.write(10)
        }
    }
}
