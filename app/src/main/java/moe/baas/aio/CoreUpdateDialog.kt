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
            addView(UiTheme.text(activity, activity.getString(R.string.update_current_version), 13f).apply { setTypeface(null, Typeface.BOLD) })
            addView(UiTheme.text(activity, version(status.commit, status.commitTime), 14f, UiTheme.blue))
            addView(UiTheme.text(activity, when {
                status.pending -> activity.getString(R.string.update_pending)
                status.external -> activity.getString(R.string.update_downloaded)
                else -> activity.getString(R.string.update_bundled)
            }, 12f, UiTheme.muted))
            errorView?.let { addView(it) }
        })
        val mirror = UiTheme.input(activity).apply { setText(CoreUpdater.mirror(activity)); isSingleLine = true }
        content.item(UiTheme.card(activity).apply {
            addView(UiTheme.text(activity, activity.getString(R.string.update_mirror), 13f).apply { setTypeface(null, Typeface.BOLD) })
            addView(mirror, LinearLayout.LayoutParams(-1, -2))
        })
        fun action(label: String, enabled: Boolean, run: (Button) -> Unit) = content.addView(Button(activity).apply {
            text = label; isEnabled = enabled
            UiTheme.styleButton(this, primary = false)
            setOnClickListener { safely(activity) { check(CoreUpdater.idle) { activity.getString(R.string.action_wait_task) }; run(this) } }
        }, LinearLayout.LayoutParams(-1, -2).apply {
            val gap = UiTheme.dp(activity, 3); setMargins(gap, UiTheme.dp(activity, 6), gap, 0)
        })
        if (status.pending || CoreUpdater.needsRestart)
            action(activity.getString(R.string.action_restart), true) { CoreUpdater.restart(activity) }
        action(activity.getString(R.string.update_restore_bundled), status.external || status.pending || status.error != null) { button ->
            if (status.external || status.pending) {
                confirmRestart(activity, activity.getString(R.string.update_restore_bundled)) { CoreUpdater.restoreBundled(activity) }
            } else {
                CoreUpdater.clearBundledState(activity)
                errorView?.visibility = View.GONE
                button.isEnabled = false
            }
        }
        val dialog = UiTheme.dialog(activity).setTitle(activity.getString(R.string.title_update))
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton(activity.getString(R.string.update_check), null)
            .setNegativeButton(activity.getString(R.string.action_close), null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        dialog.setOnShowListener {
            content.requestFocus()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                safely(activity) {
                    check(CoreUpdater.idle) { activity.getString(R.string.action_wait_task) }
                    CoreUpdater.setMirror(activity, mirror.text.toString())
                    dialog.dismiss(); checkForUpdate(activity)
                }
            }
        }
        dialog.show()
    }

    private fun safely(activity: Activity, block: () -> Unit) = try { block() } catch (error: Exception) {
        UiTheme.dialog(activity).setMessage(error.message ?: activity.getString(R.string.error_operation_failed)).setPositiveButton(activity.getString(R.string.action_ok), null).show()
    }

    private fun confirmRestart(activity: Activity, title: String, change: () -> Unit) {
        UiTheme.dialog(activity).setTitle(title).setMessage(activity.getString(R.string.update_restart_message))
            .setPositiveButton(activity.getString(R.string.action_ok)) { _, _ -> safely(activity) { change(); CoreUpdater.restart(activity) } }
            .setNegativeButton(activity.getString(R.string.action_cancel), null).show()
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
        if (cancel != null) builder.setNegativeButton(activity.getString(R.string.action_cancel)) { _, _ -> cancel() }
        return Progress(builder.show(), bar, label)
    }

    private fun checkForUpdate(activity: Activity) {
        val waiting = progress(activity, activity.getString(R.string.update_checking))
        background(activity, waiting.dialog, { CoreUpdater.check(activity) }) { result ->
            val offer = result.offer
            if (offer == null) {
                if (result.needsAppUpdate) {
                    UiTheme.dialog(activity).setTitle(activity.getString(R.string.update_app_required)).setMessage(activity.getString(R.string.update_app_required_message))
                        .setPositiveButton(activity.getString(R.string.update_open_download)) { _, _ -> openAppDownload(activity) }
                        .setNegativeButton(activity.getString(R.string.action_close), null).show()
                } else {
                    UiTheme.dialog(activity).setMessage(activity.getString(R.string.update_already_latest)).setPositiveButton(activity.getString(R.string.action_ok), null).show()
                }
                return@background
            }
            val body = UiTheme.column(activity).apply {
                val p = UiTheme.dp(activity, 20); setPadding(p, UiTheme.dp(activity, 4), p, 0)
                addView(UiTheme.text(activity, version(offer.commit, offer.commitTime), 15f, UiTheme.blue))
                addView(UiTheme.text(activity, activity.getString(R.string.update_size, megabytes(offer.size)), 12f, UiTheme.muted))
                if (offer.notes.isNotBlank()) {
                    addView(UiTheme.text(activity, activity.getString(R.string.update_release_notes), 13f).apply { setTypeface(null, Typeface.BOLD); setPadding(paddingLeft, UiTheme.dp(activity, 10), paddingRight, 0) })
                    addView(ReleaseNotes.view(activity, offer.notes))
                }
                if (result.needsAppUpdate) addView(UiTheme.text(activity, activity.getString(R.string.update_app_available), 12f, UiTheme.muted))
            }
            val dialog = UiTheme.dialog(activity).setTitle(activity.getString(R.string.update_available)).setView(ScrollView(activity).apply { addView(body) })
                .setPositiveButton(activity.getString(R.string.update_download_install)) { _, _ -> install(activity, offer) }
                .setNegativeButton(activity.getString(R.string.action_cancel), null)
            if (result.needsAppUpdate) dialog.setNeutralButton(activity.getString(R.string.update_app)) { _, _ -> openAppDownload(activity) }
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
        val view = progress(activity, activity.getString(R.string.update_downloading)) { cancelled.set(true) }
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
                view.dialog.setTitle(activity.getString(R.string.update_installing)); view.bar.isIndeterminate = true; view.label.text = ""
                view.dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.visibility = View.GONE
            }
            CoreUpdater.install(activity, offer, zip)
        }) {
            UiTheme.dialog(activity).setTitle(activity.getString(R.string.update_installed)).setMessage(activity.getString(R.string.update_restart_to_apply))
                .setPositiveButton(activity.getString(R.string.update_restart_now)) { _, _ -> safely(activity) { check(CoreUpdater.idle) { activity.getString(R.string.action_wait_task) }; CoreUpdater.restart(activity) } }
                .setNegativeButton(activity.getString(R.string.action_later), null).show()
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
                        is java.io.IOException -> activity.getString(R.string.update_connection_failed)
                        else -> error.message
                    }
                    UiTheme.dialog(activity).setMessage(message ?: activity.getString(R.string.update_failed)).setPositiveButton(activity.getString(R.string.action_ok), null).show()
                }
            }
        }
    }
}
