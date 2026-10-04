package moe.baas.aio

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Owns profile files; BAAS remains the owner of task configuration and scheduling. */
class ProfileStore(private val context: Context) {
    companion object {
        private val eventLock = java.util.concurrent.locks.ReentrantLock()
        @JvmStatic fun lockEvents() = eventLock.lock()
        @JvmStatic fun unlockEvents() = eventLock.unlock()
    }
    data class Profile(val id: String, val name: String, val server: String, val path: File) {
        val runnable get() = server in setOf("日服", "官服", "国际服")
        val gamePackage get() = when (server) {
            "官服" -> "com.RoamingStar.BlueArchive"
            "国际服" -> "com.nexon.bluearchive"
            "日服" -> "com.YostarJP.BlueArchive"
            else -> error("Unsupported Android game: $server")
        }
        override fun toString() = "$name · $server"
    }
    private val root = File(context.filesDir, "profiles")
    private val preferences = context.getSharedPreferences("profiles", Context.MODE_PRIVATE)

    fun initialize() {
        root.mkdirs()
        val existing = list()
        if (existing.isNotEmpty()) {
            // Server identity belongs to profile metadata. Core may rewrite config.json in place.
            existing.forEach { profile ->
                val file = File(profile.path, "profile.json")
                val metadata = JSONObject(file.readText())
                if (!metadata.has("server")) write(file, metadata.put("server", profile.server).toString(2))
            }
            return
        }
        val legacy = File(context.filesDir, "daily-JP_Phone")
        val template = context.assets.open("profile-template.json").bufferedReader().use { JSONObject(it.readText()) }
        val config = if (File(legacy, "config.json").exists()) JSONObject(File(legacy, "config.json").readText()) else template.getJSONObject("config")
        val events = if (File(legacy, "event.json").exists()) JSONArray(File(legacy, "event.json").readText()) else template.getJSONArray("events")
        val profile = create("JP_Phone", config, events)
        template.optJSONArray("switch")?.let { write(File(profile.path, "switch.json"), it.toString(2)) }
        select(profile.id)
    }
    fun list(): List<Profile> = root.listFiles().orEmpty().filter { it.isDirectory && File(it, "profile.json").isFile }
        .map { dir ->
            val metadata = JSONObject(File(dir, "profile.json").readText())
            val server = metadata.optString("server").ifBlank {
                JSONObject(File(dir, "config.json").readText()).optString("server", "日服")
            }
            Profile(dir.name, metadata.getString("name"), server, dir)
        }.sortedBy { it.name }
    fun get(id: String): Profile = list().firstOrNull { it.id == id } ?: error("Configuration not found")
    fun selected(): Profile {
        initialize()
        return list().firstOrNull { it.id == preferences.getString("selected", null) } ?: list().first().also { select(it.id) }
    }
    fun select(id: String) { get(id); preferences.edit().putString("selected", id).apply() }
    fun events(profile: Profile): JSONArray {
        lockEvents()
        try { return JSONArray(File(profile.path, "event.json").readText()) }
        finally { unlockEvents() }
    }
    fun setEnabled(id: String, index: Int, enabled: Boolean) {
        lockEvents()
        try {
            val profile = get(id); val events = events(profile)
            events.getJSONObject(index).put("enabled", enabled)
            write(File(profile.path, "event.json"), events.toString(2))
        } finally { unlockEvents() }
    }
    data class ConfigSnapshot(val raw: String, val values: JSONObject)
    fun config(id: String): ConfigSnapshot {
        checkIdle()
        val raw = File(get(id).path, "config.json").readText()
        val values = JSONObject(ConfigCatalog(context).defaults.toString())
        val overrides = JSONObject(raw)
        overrides.keys().forEach { key -> values.put(key, overrides.get(key)) }
        return ConfigSnapshot(raw, values)
    }
    fun saveConfig(id: String, baseline: ConfigSnapshot, changes: JSONObject) {
        checkIdle()
        val profile = get(id); val file = File(profile.path, "config.json")
        check(file.readText() == baseline.raw) { "配置已变化，请重新打开后编辑" }
        val catalog = ConfigCatalog(context)
        val current = JSONObject(baseline.raw)
        changes.keys().forEach { key ->
            catalog.validate(key, changes.get(key)); current.put(key, changes.get(key))
        }
        if (changes.length() > 0) write(file, current.toString(2))
    }
    fun saveEvent(id: String, name: String, baseline: String, changes: JSONObject) {
        checkIdle(); lockEvents()
        try {
            val profile = get(id); val events = events(profile)
            val event = (0 until events.length()).map { events.getJSONObject(it) }.first { it.getString("func_name") == name }
            check(event.toString() == baseline) { "任务调度已变化，请重新打开后编辑" }
            val allowed = setOf("priority", "interval", "next_tick", "daily_reset", "disabled_time_range", "pre_task", "post_task")
            changes.keys().forEach { key -> require(key in allowed); event.put(key, changes.get(key)) }
            val names = (0 until events.length()).map { events.getJSONObject(it).getString("func_name") }.toSet()
            for (field in listOf("pre_task", "post_task")) {
                val tasks = event.getJSONArray(field)
                for (i in 0 until tasks.length()) require(tasks.getString(i) in names && tasks.getString(i) != name) { "无效关联任务" }
            }
            validate(JSONObject().put("server", profile.server), events)
            write(File(profile.path, "event.json"), events.toString(2))
        } finally { unlockEvents() }
    }
    fun reorder(id: String, names: List<String>) {
        lockEvents()
        try {
            val profile = get(id); val events = events(profile)
            val existing = (0 until events.length()).map { events.getJSONObject(it).getString("func_name") }
            require(names.size == existing.size && names.toSet() == existing.toSet()) { "任务列表已变化，请刷新后重试" }
            for (i in 0 until events.length()) {
                val event = events.getJSONObject(i); event.put("priority", names.indexOf(event.getString("func_name")) + 1)
            }
            write(File(profile.path, "event.json"), events.toString(2))
        } finally { unlockEvents() }
    }
    fun setAllEnabled(id: String, enabled: Boolean) {
        lockEvents()
        try {
            val profile = get(id); val events = events(profile)
            for (i in 0 until events.length()) events.getJSONObject(i).put("enabled", enabled)
            write(File(profile.path, "event.json"), events.toString(2))
        } finally { unlockEvents() }
    }
    fun resetSchedule(id: String, nextDay: Boolean = false) {
        checkIdle(); lockEvents()
        try {
            val profile = get(id); val events = events(profile); val now = System.currentTimeMillis() / 1000.0
            val tick = if (nextDay) java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 4); set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(java.util.Calendar.DAY_OF_MONTH, 1)
            }.timeInMillis / 1000.0 else now
            for (i in 0 until events.length()) {
                val event = events.getJSONObject(i)
                event.put("next_tick", tick)
            }
            write(File(profile.path, "event.json"), events.toString(2))
        } finally { unlockEvents() }
    }
    fun newProfile(name: String, server: String): Profile {
        checkIdle(); require(name.trim().isNotEmpty() && name.length <= 80); require(server in setOf("日服", "官服", "国际服"))
        val defaults = context.assets.open("profile-defaults.json").bufferedReader().use { JSONObject(it.readText()) }
        val profile = create(name.trim(), defaults.getJSONObject("config").put("server", server), defaults.getJSONArray("events"))
        write(File(profile.path, "switch.json"), defaults.getJSONArray("switch").toString(2))
        return profile
    }
    fun importJson(id: String, input: InputStream) {
        checkIdle()
        val buffer = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8192)
        while (true) {
            val size = input.read(chunk); if (size < 0) break
            buffer.write(chunk, 0, size); require(buffer.size() <= 2 * 1024 * 1024) { "Configuration file exceeds 2 MiB" }
        }
        val bytes = buffer.toByteArray()
        require(bytes.size <= 2 * 1024 * 1024) { "Configuration file exceeds 2 MiB" }
        val text = bytes.toString(Charsets.UTF_8).trim(); val profile = get(id)
        val parser = org.json.JSONTokener(text); val parsed = parser.nextValue()
        require(parser.nextClean() == '\u0000') { "JSON 文件末尾有多余内容" }
        lockEvents()
        try {
            if (parsed is JSONArray) {
                val events = parsed; validate(JSONObject().put("server", profile.server), events)
                write(File(profile.path, "event.json"), events.toString(2))
            } else {
                require(parsed is JSONObject) { "请选择 config.json 或 event.json" }
                val config = parsed; validate(config, events(profile))
                require(config.getString("server") == profile.server) { "服务器不同，请新建对应服务器的配置后导入" }
                write(File(profile.path, "config.json"), config.toString(2))
            }
        } finally { unlockEvents() }
    }
    fun duplicate(id: String): Profile {
        checkIdle()
        val old = get(id)
        val copy = create("${old.name} copy", JSONObject(File(old.path, "config.json").readText()), events(old))
        val layout = File(old.path, "switch.json")
        if (layout.exists()) write(File(copy.path, "switch.json"), layout.readText())
        return copy
    }
    fun rename(id: String, name: String) {
        checkIdle(); require(name.isNotBlank() && name.length <= 80) { "Name must be 1–80 characters" }
        require(list().none { it.id != id && it.name == name.trim() }) { "Configuration name already exists" }
        val profile = get(id)
        write(File(profile.path, "profile.json"), JSONObject().put("name", name.trim()).put("server", profile.server).toString(2))
    }
    fun delete(id: String) {
        checkIdle(); require(list().size > 1) { "Keep at least one configuration" }
        check(get(id).path.deleteRecursively()) { "Could not remove configuration" }
        selected()
    }
    /** Reads only BAAS profile filenames; never extracts arbitrary archive paths. */
    fun importZip(input: InputStream): List<Profile> {
        checkIdle()
        val groups = linkedMapOf<String, MutableMap<String, String>>()
        var total = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val parts = entry.name.split('/')
                require(!entry.name.startsWith('/') && '\\' !in entry.name && parts.none { it == ".." }) { "Invalid archive path" }
                val name = parts.last()
                if (!entry.isDirectory && name in setOf("config.json", "event.json", "switch.json")) {
                    val buffer = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (true) {
                        val size = zip.read(chunk)
                        if (size < 0) break
                        buffer.write(chunk, 0, size)
                        require(buffer.size() <= 2 * 1024 * 1024) { "Configuration file exceeds 2 MiB" }
                    }
                    val body = buffer.toByteArray()
                    require(body.size <= 2 * 1024 * 1024) { "Configuration file exceeds 2 MiB" }
                    total += body.size; require(total <= 8 * 1024 * 1024) { "Configuration archive exceeds 8 MiB" }
                    val key = parts.dropLast(1).joinToString("/")
                    val group = groups.getOrPut(key) { linkedMapOf() }
                    require(group.put(name, body.toString(Charsets.UTF_8)) == null) { "Duplicate configuration file" }
                    require(groups.size <= 32) { "Import at most 32 configurations at a time" }
                }
                zip.closeEntry()
            }
        }
        require(groups.isNotEmpty()) { "ZIP must contain config.json and event.json" }
        val parsed = groups.map { (path, files) ->
            require(files.keys.containsAll(setOf("config.json", "event.json"))) { "Missing config.json or event.json in $path" }
            val config = JSONObject(files.getValue("config.json")); val events = JSONArray(files.getValue("event.json"))
            validate(config, events)
            val layout = files["switch.json"]?.let { JSONArray(it).toString(2) }
            Triple(path.substringAfterLast('/').ifBlank { "Imported" }.take(80), config, events) to layout
        }
        val imported = mutableListOf<Profile>()
        try { parsed.forEach { (profile, layout) ->
            val (name, config, events) = profile
            val created = create(name, config, events); imported += created
            if (layout != null) write(File(created.path, "switch.json"), layout)
        } }
        catch (error: Exception) { imported.forEach { it.path.deleteRecursively() }; throw error }
        return imported
    }
    fun exportZip(id: String, output: OutputStream) {
        checkIdle(); val profile = get(id)
        ZipOutputStream(output).use { zip ->
            for (name in listOf("config.json", "event.json", "switch.json").filter { File(profile.path, it).exists() }) {
                val folder = profile.name.replace('/', '_').replace('\\', '_').let {
                    if (it == "." || it == "..") "Configuration" else it
                }
                zip.putNextEntry(ZipEntry("$folder/$name"))
                zip.write(File(profile.path, name).readBytes()); zip.closeEntry()
            }
        }
    }
    private fun create(name: String, config: JSONObject, events: JSONArray): Profile {
        validate(config, events)
        val existing = list().map { it.name }.toSet()
        var label = name; var suffix = 2
        while (label in existing) { label = "$name ($suffix)"; suffix++ }
        val id = UUID.randomUUID().toString(); val dir = File(root, id)
        check(dir.mkdirs()) { "Could not create configuration" }
        try {
            write(File(dir, "config.json"), config.toString(2))
            write(File(dir, "event.json"), events.toString(2))
            write(File(dir, "profile.json"), JSONObject().put("name", label).put("server", config.getString("server")).toString(2))
        } catch (error: Exception) { dir.deleteRecursively(); throw error }
        return get(id)
    }
    private fun validate(config: JSONObject, events: JSONArray) {
        require(config.optString("server").isNotBlank()) { "config.json is missing server" }
        require(events.length() <= 256) { "Too many tasks" }
        val names = mutableSetOf<String>()
        for (i in 0 until events.length()) {
            val event = events.getJSONObject(i); val name = event.getString("func_name")
            require(name.matches(Regex("[a-zA-Z0-9_]+")) && names.add(name)) { "Invalid or duplicate task: $name" }
            require(event.get("enabled") is Boolean && event.getString("event_name").isNotBlank()) { "Invalid task: $name" }
            for (field in listOf("priority", "interval", "next_tick")) {
                require(event.get(field) is Number && event.getDouble(field).isFinite()) { "Invalid $field in $name" }
            }
            require(event.getDouble("next_tick") >= 0) { "Invalid next_tick in $name" }
            for (field in listOf("daily_reset", "disabled_time_range", "pre_task", "post_task")) event.getJSONArray(field)
            for (field in listOf("pre_task", "post_task")) {
                val tasks = event.getJSONArray(field)
                for (n in 0 until tasks.length()) require(tasks.get(n) is String) { "Invalid $field in $name" }
            }
            val reset = event.getJSONArray("daily_reset")
            for (n in 0 until reset.length()) {
                val at = reset.getJSONArray(n)
                require(at.length() == 3 && at.getInt(0) in 0..23 && at.getInt(1) in 0..59 && at.getInt(2) in 0..59) { "Invalid daily reset in $name" }
            }
            val disabled = event.getJSONArray("disabled_time_range")
            for (n in 0 until disabled.length()) {
                val range = disabled.getJSONArray(n); require(range.length() == 2) { "Invalid disabled time range" }
                for (p in 0..1) {
                    val at = range.getJSONArray(p)
                    require(at.length() == 3 && at.getInt(0) in 0..23 && at.getInt(1) in 0..59 && at.getInt(2) in 0..59) { "Invalid disabled time range" }
                }
            }
        }
    }
    private fun checkIdle() { check(!TaskRuntime.isActive) { "Stop the workflow before changing configurations" } }
    private fun write(file: File, text: String) {
        val atomic = AtomicFile(file); val stream = atomic.startWrite()
        try { stream.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }
}
