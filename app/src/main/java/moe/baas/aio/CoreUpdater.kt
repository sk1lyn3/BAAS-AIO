package moe.baas.aio

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Android side of downloaded BAAS cores: network, APK facts, activation by process restart. */
object CoreUpdater {
    private const val MAX_INDEX = 1L shl 20
    @Volatile private var changing = false
    @Volatile private var restartRequired = false

    val canStart get() = !changing && !restartRequired
    fun requireCanStart() { check(canStart) { if (changing) "正在切换 core，请稍后重试" else "请先重启应用使 core 切换生效" } }

    fun root(context: Context) = File(context.filesDir, "core")
    private fun keys(context: Context) =
        CoreBundle.publicKeys(context.resources.openRawResource(R.raw.core_bundle_keys).bufferedReader().use { it.readText() })
    private fun versionCode(context: Context): Long {
        @Suppress("DEPRECATION")
        return context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    }
    fun bundled(context: Context): JSONObject =
        context.assets.open("core-bundled.json").bufferedReader().use { JSONObject(it.readText()) }

    private fun preferences(context: Context) = context.getSharedPreferences("core_update", Context.MODE_PRIVATE)
    fun mirror(context: Context) = preferences(context).getString("mirror", "").orEmpty()
    fun setMirror(context: Context, value: String) {
        val prefix = value.trim()
        require(prefix.isEmpty() || prefix.startsWith("https://") || prefix.startsWith("http://")) { "镜像地址需要以 http:// 或 https:// 开头" }
        preferences(context).edit().putString("mirror", prefix).apply()
    }
    /** Mirrors only relay bytes: the index and the package are both signed, so a mirror cannot alter them. */
    private fun through(context: Context, url: String) = mirror(context).let { if (it.isEmpty()) url else it.trimEnd('/') + "/" + url }

    val idle get() = !changing && !TaskRuntime.isActive && !TaskRuntime.isProbeRunning && !ManagedMode.executionActive

    /** Task startup uses the same monitor; extraction never holds it or blocks the UI thread. */
    private fun <T> change(action: () -> T): T {
        synchronized(this) { check(idle) { "请等待当前任务结束" }; changing = true }
        try {
            return action().also { restartRequired = true }
        } finally { changing = false }
    }

    /** Discard installed packages this APK cannot run before Python or Kotlin selects a core. */
    fun reconcile(context: Context) {
        CoreBundle.reconcile(root(context), BuildConfig.CORE_API, bundled(context), versionCode(context))
    }

    data class Status(val commit: String, val commitTime: String, val external: Boolean, val pending: Boolean, val error: String?)

    fun status(context: Context): Status {
        val root = root(context); val state = CoreBundle.readState(root)
        val id = CoreBundle.effective(root); val manifest = CoreBundle.manifest(root, id)
        val core = manifest?.getJSONObject("core")
        val bundled = bundled(context)
        return Status(
            core?.getString("commit") ?: bundled.getString("head"),
            core?.getString("commitTime") ?: bundled.getString("commitTime"),
            manifest != null,
            CoreBundle.id(state, "pending") != null,
            CoreBundle.id(state, "lastError"))
    }

    private fun open(context: Context, url: String): HttpURLConnection =
        (URL(through(context, url)).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 30000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "BAAS-AIO/${BuildConfig.VERSION_NAME}")
            if (responseCode !in 200..299) { disconnect(); throw CoreBundle.Rejected("下载失败：HTTP $responseCode") }
        }

    private fun fetch(context: Context, url: String): ByteArray = open(context, url).run {
        try {
            inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break; out.write(buffer, 0, n); if (out.size() > MAX_INDEX) throw CoreBundle.Rejected("更新信息过大") }
                out.toByteArray()
            }
        } finally { disconnect() }
    }

    data class CheckResult(val offer: CoreBundle.Offer?, val needsAppUpdate: Boolean)

    /** Report newer compatible cores and newer cores blocked by the app version independently. */
    fun check(context: Context, channelUrl: String = BuildConfig.CORE_CHANNEL_URL): CheckResult {
        val body = fetch(context, channelUrl); val signature = fetch(context, "$channelUrl.sig")
        val updates = CoreBundle.updates(body, signature, keys(context), BuildConfig.CORE_API, versionCode(context))
        val current = status(context); val bundled = bundled(context)
        fun newer(it: CoreBundle.Offer) =
            CoreBundle.newer(it.commit, it.commitTime, current.commit, current.commitTime) &&
                CoreBundle.newer(it.commit, it.commitTime, bundled.getString("head"), bundled.getString("commitTime"))
        val offer = updates.compatible?.takeIf(::newer)
        val needsAppUpdate = updates.needsAppUpdate?.let {
            newer(it) && (offer == null || CoreBundle.newer(it.commit, it.commitTime, offer.commit, offer.commitTime))
        } == true
        return CheckResult(offer, needsAppUpdate)
    }

    class Cancelled : Exception()

    /** [cancelled] is polled between chunks; a cancelled download leaves nothing behind. */
    fun download(context: Context, offer: CoreBundle.Offer, cancelled: () -> Boolean, progress: (Long, Long) -> Unit): File {
        val directory = File(root(context), "downloads").apply { deleteRecursively(); mkdirs() }
        val partial = File(directory, "core-${offer.commit.take(12)}.zip.partial")
        open(context, offer.url).run {
            try {
                inputStream.use { input ->
                    FileOutputStream(partial).use { output ->
                        val buffer = ByteArray(1 shl 16); var done = 0L
                        while (true) {
                            if (cancelled()) throw Cancelled()
                            val n = input.read(buffer); if (n < 0) break
                            done += n; if (done > offer.size) throw CoreBundle.Rejected("下载的文件大小不对")
                            output.write(buffer, 0, n); progress(done, offer.size)
                        }
                        output.fd.sync()
                    }
                }
            } catch (error: Exception) {
                directory.deleteRecursively(); throw error
            } finally { disconnect() }
        }
        return File(directory, "core-${offer.commit.take(12)}.zip").also { check(partial.renameTo(it)) { "无法保存下载" } }
    }

    fun install(context: Context, offer: CoreBundle.Offer, zip: File): String {
        try {
            return change {
                CoreBundle.install(zip, offer, root(context), keys(context), BuildConfig.CORE_API, versionCode(context),
                    bundled(context), CoreBundle::checkData)
            }
        } finally { zip.parentFile?.deleteRecursively() }
    }

    fun restoreBundled(context: Context) = change { CoreBundle.restoreBundled(root(context)) }

    fun needsStartupCheck(context: Context) = !restartRequired && status(context).pending

    /** Shared with task startup so both paths settle on the same Python core before reading its data. */
    fun prepare(context: Context) {
        val app = context.applicationContext
        reconcile(app)
        BundledAssets.sync(app, "baas-source.zip")
        synchronized(Python::class.java) { if (!Python.isStarted()) Python.start(AndroidPlatform(app)) }
        Python.getInstance().getModule("core_loader").callAttr("prepare", app.filesDir.absolutePath)
    }

    /**
     * A pending core is self-tested by core_loader on the first Python start of a process. Run that start now,
     * off the UI thread, so a broken package is rejected before the user starts a task. [done] gets true when
     * the pending core became active.
     */
    fun testPending(context: Context, done: (Boolean, String?) -> Unit) {
        val app = context.applicationContext
        kotlin.concurrent.thread(name = "baas-core-check") {
            val passed = try {
                prepare(app)
                !status(app).pending && status(app).error == null
            } catch (_: Throwable) { false }
            val error = status(app).error
            android.os.Handler(android.os.Looper.getMainLooper()).post { done(passed, error) }
        }
    }

    /**
     * Python and Kotlin both lock their core choice per process, so a change applies in a fresh process.
     * RestartActivity, in its own process, ends this one and opens the app again.
     */
    fun restart(activity: Activity) {
        activity.startActivity(Intent(activity, RestartActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(RestartActivity.PID, android.os.Process.myPid()))
    }
}
