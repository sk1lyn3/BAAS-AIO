package moe.baas.aio

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import androidx.compose.ui.graphics.toArgb
import moe.baas.aio.UiTheme.item
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Settings entry for downloaded BAAS cores: version, manual check, restore built-in. */
object CoreUpdateDialog {
    /** core-<sha7> · <commit date>. */
    fun version(commit: String, commitTime: String) = "core-${commit.take(7)}" + if (commitTime.isNotEmpty()) " · ${commitTime.take(10)}" else ""

    fun show(activity: Activity) {
        val status = CoreUpdater.status(activity)
        val errorView = status.error?.let { UiTheme.text(activity, it, 12f, UiTheme.danger) }
        val content = UiTheme.column(activity).apply {
            val p = UiTheme.dp(activity, 12); setPadding(p, 0, p, UiTheme.dp(activity, 8))
            // Keep focus off the mirror field so the keyboard stays hidden until the user taps it.
            isFocusableInTouchMode = true; descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        }
        content.item(UiTheme.card(activity).apply {
            addView(UiTheme.text(activity, "当前版本", 13f).apply { setTypeface(null, Typeface.BOLD) })
            addView(UiTheme.text(activity, version(status.commit, status.commitTime), 14f, UiTheme.blue))
            addView(UiTheme.text(activity, when {
                status.pending -> "已下载新版本，重启后生效"
                status.external -> "已下载的版本"
                else -> "内置版本"
            }, 12f, UiTheme.muted))
            errorView?.let { addView(it) }
        })
        val mirror = UiTheme.input(activity).apply { setText(CoreUpdater.mirror(activity)); isSingleLine = true }
        content.item(UiTheme.card(activity).apply {
            addView(UiTheme.text(activity, "下载镜像", 13f).apply { setTypeface(null, Typeface.BOLD) })
            addView(mirror, LinearLayout.LayoutParams(-1, -2))
        })
        fun action(label: String, enabled: Boolean, run: (Button) -> Unit) = content.addView(Button(activity).apply {
            text = label; isEnabled = enabled
            UiTheme.styleButton(this, primary = false)
            setOnClickListener { safely(activity) { check(CoreUpdater.idle) { "请等待当前任务结束" }; run(this) } }
        }, LinearLayout.LayoutParams(-1, -2).apply {
            val gap = UiTheme.dp(activity, 3); setMargins(gap, UiTheme.dp(activity, 6), gap, 0)
        })
        if (status.pending || CoreUpdater.needsRestart)
            action("重新启动", true) { CoreUpdater.restart(activity) }
        action("恢复内置版本", status.external || status.pending || status.error != null) { button ->
            if (status.external || status.pending) {
                confirmRestart(activity, "恢复内置版本") { CoreUpdater.restoreBundled(activity) }
            } else {
                CoreUpdater.clearBundledState(activity)
                errorView?.visibility = View.GONE
                button.isEnabled = false
            }
        }
        val dialog = UiTheme.dialog(activity).setTitle("更新")
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton("检查更新", null)
            .setNegativeButton("关闭", null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        dialog.setOnShowListener {
            content.requestFocus()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                safely(activity) {
                    check(CoreUpdater.idle) { "请等待当前任务结束" }
                    CoreUpdater.setMirror(activity, mirror.text.toString())
                    dialog.dismiss(); checkForUpdate(activity)
                }
            }
        }
        dialog.show()
    }

    private fun safely(activity: Activity, block: () -> Unit) = try { block() } catch (error: Exception) {
        UiTheme.dialog(activity).setMessage(error.message ?: "操作失败").setPositiveButton("确定", null).show()
    }

    private fun confirmRestart(activity: Activity, title: String, change: () -> Unit) {
        UiTheme.dialog(activity).setTitle(title).setMessage("应用会重新启动")
            .setPositiveButton("确定") { _, _ -> safely(activity) { change(); CoreUpdater.restart(activity) } }
            .setNegativeButton("取消", null).show()
    }

    private class Progress(val dialog: AlertDialog, val bar: ProgressBar, val label: android.widget.TextView)

    /** Title, v3 progress bar and a status line; [cancel] adds a 取消 button. */
    private fun progress(activity: Activity, title: String, cancel: (() -> Unit)? = null): Progress {
        val radius = UiTheme.dp(activity, 4).toFloat()
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; isIndeterminate = true
            val track = GradientDrawable().apply { setColor(UiTheme.border); cornerRadius = radius }
            val fill = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Ui.Cyan.toArgb(), Ui.Blue.toArgb())).apply { cornerRadius = radius }
            progressDrawable = LayerDrawable(arrayOf(track, ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL))).apply {
                setId(0, android.R.id.background); setId(1, android.R.id.progress)
            }
            indeterminateTintList = android.content.res.ColorStateList.valueOf(UiTheme.blue)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(UiTheme.border)
        }
        val label = UiTheme.text(activity, "", 12f, UiTheme.muted)
        val body = UiTheme.column(activity).apply {
            val p = UiTheme.dp(activity, 24); setPadding(p, UiTheme.dp(activity, 8), p, 0)
            addView(bar, LinearLayout.LayoutParams(-1, UiTheme.dp(activity, 8)).apply { topMargin = UiTheme.dp(activity, 6) })
            addView(label, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiTheme.dp(activity, 4) })
        }
        val builder = UiTheme.dialog(activity).setTitle(title).setView(body).setCancelable(false)
        if (cancel != null) builder.setNegativeButton("取消") { _, _ -> cancel() }
        return Progress(builder.show(), bar, label)
    }

    private fun checkForUpdate(activity: Activity) {
        val waiting = progress(activity, "正在检查更新")
        background(activity, waiting.dialog, { CoreUpdater.check(activity) }) { result ->
            val offer = result.offer
            if (offer == null) {
                if (result.needsAppUpdate) {
                    UiTheme.dialog(activity).setTitle("需要更新应用").setMessage("有新的 core，需要先更新应用。")
                        .setPositiveButton("前往下载") { _, _ -> openAppDownload(activity) }
                        .setNegativeButton("关闭", null).show()
                } else {
                    UiTheme.dialog(activity).setMessage("当前 core 已是可用的最新版本").setPositiveButton("确定", null).show()
                }
                return@background
            }
            val body = UiTheme.column(activity).apply {
                val p = UiTheme.dp(activity, 20); setPadding(p, UiTheme.dp(activity, 4), p, 0)
                addView(UiTheme.text(activity, version(offer.commit, offer.commitTime), 15f, UiTheme.blue))
                addView(UiTheme.text(activity, "大小 ${megabytes(offer.size)} MB", 12f, UiTheme.muted))
                if (offer.notes.isNotBlank()) {
                    addView(UiTheme.text(activity, "更新说明", 13f).apply { setTypeface(null, Typeface.BOLD); setPadding(paddingLeft, UiTheme.dp(activity, 10), paddingRight, 0) })
                    addView(ReleaseNotes.view(activity, offer.notes))
                }
                if (result.needsAppUpdate) addView(UiTheme.text(activity, "更新应用可使用更新的 core", 12f, UiTheme.muted))
            }
            val dialog = UiTheme.dialog(activity).setTitle("发现新版本").setView(ScrollView(activity).apply { addView(body) })
                .setPositiveButton("下载并安装") { _, _ -> install(activity, offer) }
                .setNegativeButton("取消", null)
            if (result.needsAppUpdate) dialog.setNeutralButton("更新应用") { _, _ -> openAppDownload(activity) }
            dialog.show()
        }
    }

    private fun openAppDownload(activity: Activity) = safely(activity) {
        val url = BuildConfig.CORE_CHANNEL_URL.substringBefore("/releases/") + "/releases/latest"
        activity.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    }

    private fun megabytes(bytes: Long) = String.format(Locale.ROOT, "%.1f", bytes / 1048576.0)

    private fun install(activity: Activity, offer: CoreBundle.Offer) {
        val cancelled = AtomicBoolean(false)
        val view = progress(activity, "正在下载") { cancelled.set(true) }
        view.bar.isIndeterminate = false
        view.label.text = "0.0 / ${megabytes(offer.size)} MB"
        var shown = -1
        background(activity, view.dialog, {
            val zip = CoreUpdater.download(activity, offer, cancelled::get) { done, total ->
                val permille = (done * 1000 / maxOf(total, 1)).toInt()
                if (permille / 5 != shown) {
                    shown = permille / 5
                    activity.runOnUiThread { view.bar.progress = permille; view.label.text = "${megabytes(done)} / ${megabytes(total)} MB" }
                }
            }
            activity.runOnUiThread {
                view.dialog.setTitle("正在安装"); view.bar.isIndeterminate = true; view.label.text = ""
                view.dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.visibility = View.GONE
            }
            CoreUpdater.install(activity, offer, zip)
        }) {
            UiTheme.dialog(activity).setTitle("安装完成").setMessage("重启应用后生效")
                .setPositiveButton("立即重启") { _, _ -> safely(activity) { check(CoreUpdater.idle) { "请等待当前任务结束" }; CoreUpdater.restart(activity) } }
                .setNegativeButton("稍后", null).show()
        }
    }

    private fun <T> background(activity: Activity, waiting: AlertDialog, work: () -> T, done: (T) -> Unit) {
        kotlin.concurrent.thread(name = "baas-core-update") {
            val result = runCatching(work)
            activity.runOnUiThread {
                waiting.dismiss()
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                result.fold(done) { error ->
                    if (error is CoreUpdater.Cancelled) return@fold
                    val message = when (error) {
                        is CoreBundle.Rejected -> error.message
                        is java.io.IOException -> "无法连接更新服务器"
                        else -> error.message
                    }
                    UiTheme.dialog(activity).setMessage(message ?: "更新失败").setPositiveButton("确定", null).show()
                }
            }
        }
    }
}
