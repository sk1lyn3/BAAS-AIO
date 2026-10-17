package moe.baas.aio

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.OffsetDateTime
import java.util.Base64
import java.util.zip.ZipFile

/**
 * Signed BAAS core packages under files/core: verification, installation and the state shared with
 * core_loader.py. Free of Android UI so the install and restore rules can run on a plain JVM.
 */
object CoreBundle {
    const val BUNDLED = "bundled"
    private const val MAX_UNPACKED = 256L shl 20

    class Rejected(message: String) : Exception(message)

    data class Offer(val commit: String, val commitTime: String, val url: String, val zipSha256: String, val size: Long, val notes: String)
    data class Updates(val compatible: Offer?, val needsAppUpdate: Offer?)

    fun publicKeys(json: String): List<PublicKey> {
        val keys = JSONObject(json).getJSONArray("keys")
        return (0 until keys.length()).map {
            val der = Base64.getDecoder().decode(keys.getJSONObject(it).getString("publicKeyDerBase64"))
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
        }
    }

    fun verified(keys: List<PublicKey>, payload: ByteArray, signature: ByteArray) = keys.any { key ->
        try {
            Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(payload); verify(signature) }
        } catch (_: Exception) { false }
    }

    /** Keep compatible updates separate from packages which require a newer app. Older APIs do not. */
    fun updates(body: ByteArray, signature: ByteArray, keys: List<PublicKey>, coreApi: Int, versionCode: Long): Updates {
        if (!verified(keys, body, signature)) throw Rejected("更新信息签名无效")
        val packages = JSONObject(String(body, Charsets.UTF_8)).getJSONArray("packages")
        val entries = (0 until packages.length()).map { packages.getJSONObject(it) }
        fun latest(items: List<JSONObject>) = items.maxByOrNull { instant(it.getString("commitTime")) }?.let {
            Offer(it.getString("commit"), it.getString("commitTime"), it.getString("url"),
                it.getString("zipSha256").lowercase(), it.getLong("size"), it.optString("notes"))
        }
        return Updates(
            latest(entries.filter { it.getInt("coreApi") == coreApi && supports(it, versionCode) }),
            latest(entries.filter {
                val api = it.getInt("coreApi")
                (it.isNull("maxVersionCode") || versionCode <= it.getLong("maxVersionCode")) &&
                    (api > coreApi || api == coreApi && !it.isNull("minVersionCode") && versionCode < it.getLong("minVersionCode"))
            }))
    }

    /** Committer time with offset (git %cI); compared as a UTC instant. */
    fun instant(time: String): Long = OffsetDateTime.parse(time).toInstant().epochSecond

    /** Newer only if a different commit with a strictly later commit time. */
    fun newer(commit: String, time: String, baseCommit: String, baseTime: String) =
        commit != baseCommit && instant(time) > instant(baseTime)

    private fun supports(item: JSONObject, versionCode: Long) =
        (item.isNull("minVersionCode") || versionCode >= item.getLong("minVersionCode")) &&
            (item.isNull("maxVersionCode") || versionCode <= item.getLong("maxVersionCode"))

    fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(1 shl 16)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        hex(digest.digest())
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    /**
     * Verify [zip] against [offer] and the embedded signed manifest, unpack it into files/core/ext and mark it
     * pending. Nothing outside files/core/staging changes until every check has passed.
     */
    fun install(zip: File, offer: Offer, root: File, keys: List<PublicKey>, coreApi: Int, versionCode: Long,
                bundled: JSONObject, checkData: (File) -> Unit): String {
        if (sha256(zip) != offer.zipSha256) throw Rejected("下载的文件不完整")
        val staging = File(root, "staging")
        staging.deleteRecursively()
        try {
            ZipFile(zip).use { archive ->
                val manifestBytes = archive.getEntry("bundle-manifest.json")?.let { e -> archive.getInputStream(e).use { it.readBytes() } }
                    ?: throw Rejected("更新包缺少清单")
                val signature = archive.getEntry("bundle-manifest.json.sig")?.let { e -> archive.getInputStream(e).use { it.readBytes() } }
                    ?: throw Rejected("更新包缺少签名")
                if (!verified(keys, manifestBytes, signature)) throw Rejected("更新包签名无效")
                val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
                if (manifest.getInt("format") != 1) throw Rejected("更新包格式不受支持")
                if (manifest.getInt("coreApi") != coreApi) throw Rejected("这个版本需要先更新应用")
                if (!supports(manifest, versionCode)) throw Rejected("这个版本不适用于当前应用")
                val core = manifest.getJSONObject("core")
                if (core.getString("commit") != offer.commit || instant(core.getString("commitTime")) != instant(offer.commitTime))
                    throw Rejected("更新包和更新信息不一致")
                if (!newer(offer.commit, offer.commitTime, bundled.getString("head"), bundled.getString("commitTime"))) throw Rejected("内置版本已经更新")
                val files = manifest.getJSONObject("files")
                val seen = HashSet<String>(); var unpacked = 0L
                staging.mkdirs()
                val base = staging.canonicalFile
                for (entry in archive.entries()) {
                    val name = entry.name
                    if (name == "bundle-manifest.json" || name == "bundle-manifest.json.sig") continue
                    if (entry.isDirectory) continue
                    if (!files.has(name)) throw Rejected("更新包含有清单外的文件")
                    val target = File(staging, name)
                    if (!target.canonicalPath.startsWith(base.path + File.separator)) throw Rejected("更新包路径无效")
                    target.parentFile!!.mkdirs()
                    val digest = MessageDigest.getInstance("SHA-256")
                    archive.getInputStream(entry).use { input ->
                        FileOutputStream(target).use { output ->
                            val buffer = ByteArray(1 shl 16)
                            while (true) {
                                val n = input.read(buffer); if (n < 0) break
                                unpacked += n
                                if (unpacked > MAX_UNPACKED) throw Rejected("更新包过大")
                                digest.update(buffer, 0, n); output.write(buffer, 0, n)
                            }
                        }
                    }
                    if (hex(digest.digest()) != files.getString(name)) throw Rejected("更新包文件校验失败")
                    seen += name
                }
                if (seen.size != files.length()) throw Rejected("更新包缺少文件")
                File(staging, "bundle-manifest.json").writeBytes(manifestBytes)
                File(staging, "bundle-manifest.json.sig").writeBytes(signature)
            }
            checkData(File(staging, "data"))
            val id = offer.commit.take(12)
            val target = File(root, "ext/$id")
            target.parentFile!!.mkdirs()
            target.deleteRecursively()
            if (!staging.renameTo(target)) throw Rejected("无法写入更新")
            val state = readState(root)
            state.put("pending", id).put("bootAttempts", 0).put("lastError", JSONObject.NULL)
            writeState(root, state)
            prune(root, state)
            return id
        } finally {
            staging.deleteRecursively()
        }
    }

    fun readState(root: File): JSONObject = try {
        JSONObject(File(root, "state.json").readText())
    } catch (_: Exception) { JSONObject().put("active", BUNDLED) }

    /** Same replace-by-rename protocol as core_loader.write_state; never leaves a half-written state. */
    fun writeState(root: File, state: JSONObject) {
        root.mkdirs()
        val partial = File(root, "state.json.partial")
        FileOutputStream(partial).use { it.write(state.toString(2).toByteArray()); it.fd.sync() }
        if (!partial.renameTo(File(root, "state.json"))) throw Rejected("无法保存更新状态")
    }

    fun manifest(root: File, id: String?): JSONObject? =
        if (id == null || id == BUNDLED) null
        else try { JSONObject(File(root, "ext/$id/bundle-manifest.json").readText()) } catch (_: Exception) { null }

    fun id(state: JSONObject, key: String) = state.optString(key).takeIf { !state.isNull(key) && it.isNotEmpty() }

    /** Core the next Python process will try first, also shown in the update dialog. */
    fun effective(root: File): String { val s = readState(root); return id(s, "pending") ?: id(s, "active") ?: BUNDLED }

    /**
     * Drop downloaded cores this APK cannot use: missing, different coreApi, or not newer than the built-in
     * snapshot (e.g. after an APK upgrade that ships a newer core). Also deletes packages the state no longer
     * names, such as the one a self-tested update replaced.
     */
    fun reconcile(root: File, coreApi: Int, bundled: JSONObject, versionCode: Long) {
        val state = readState(root); var changed = false
        fun usable(id: String) = manifest(root, id)?.let { it.optInt("coreApi") == coreApi && supports(it, versionCode) && it.getJSONObject("core").let { core ->
            newer(core.getString("commit"), core.getString("commitTime"), bundled.getString("head"), bundled.getString("commitTime")) } } == true
        for (key in listOf("pending", "active")) {
            val id = id(state, key) ?: continue
            if (id == BUNDLED || usable(id)) continue
            if (key == "active") state.put("active", BUNDLED) else state.put(key, JSONObject.NULL)
            state.put("lastError", "已下载的版本不适用于当前应用，已改用内置版本")
            changed = true
        }
        if (changed) writeState(root, state)
        prune(root, state)
    }

    /** Back to the APK's core; every downloaded package is deleted. */
    fun restoreBundled(root: File) {
        val state = readState(root)
        state.put("active", BUNDLED).put("pending", JSONObject.NULL).put("bootAttempts", 0).put("lastError", JSONObject.NULL)
        writeState(root, state); prune(root, state)
    }

    /** Keep only the packages the state still refers to. */
    fun prune(root: File, state: JSONObject) {
        val keep = listOf("active", "pending").mapNotNull { id(state, it) }.toSet()
        File(root, "ext").listFiles().orEmpty().filter { it.name !in keep }.forEach { it.deleteRecursively() }
    }

    /**
     * The package's catalog must fit the defaults and static tables it ships with, and its tasks must be valid.
     * Saved profiles are not compared with the new defaults: core's self-test loads every profile with the new core.
     */
    fun checkData(data: File) {
        fun read(name: String) = try { File(data, name).readText() } catch (_: Exception) { throw Rejected("更新包缺少 $name") }
        val defaults = read("profile-defaults.json"); val catalog = read("config-catalog.json"); val static = read("static-config.json")
        JSONObject(read("activities-info.json"))
        try { ConfigCatalog(catalog, defaults, static).check() }
        catch (error: Exception) { throw Rejected("新版本的设置数据有误：${error.message}") }
        try { ProfileStore.validateEvents(JSONObject(defaults).getJSONArray("events")) }
        catch (error: Exception) { throw Rejected("新版本的任务格式不受支持：${error.message}") }
    }
}
