package moe.baas.aio

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import rikka.shizuku.Shizuku

/** Shared permission guidance; Shizuku owns the actual authorization dialog. */
object ShizukuAccess {
    private const val MANAGER = "moe.shizuku.privileged.api"
    enum class State(val label: String, val action: String, val detail: String) {
        MISSING("Shizuku 未安装", "下载 Shizuku", "请先安装并启动 Shizuku，再返回 BAAS 授权。"),
        STOPPED("Shizuku 未启动", "打开 Shizuku", "请在 Shizuku 中启动服务，再返回 BAAS。手机重启后通常需要重新启动服务。"),
        UNAUTHORIZED("Shizuku 未授权", "请求授权", "BAAS 需要 Shizuku 授权才能控制游戏。"),
        BLOCKED("Shizuku 授权已拒绝", "打开 Shizuku", "请在 Shizuku 的应用管理中允许 BAAS 使用 Shizuku，再返回 BAAS。"),
        READY("Shizuku 已授权", "检查连接", "")
    }
    fun state(context: Context): State {
        try {
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return State.READY
                return if (Shizuku.shouldShowRequestPermissionRationale()) State.BLOCKED else State.UNAUTHORIZED
            }
        } catch (_: Exception) { }
        return try {
            context.packageManager.getApplicationInfo(MANAGER, 0)
            State.STOPPED
        } catch (_: PackageManager.NameNotFoundException) { State.MISSING }
    }
    fun guide(activity: Activity, state: State, request: () -> Unit) {
        if (state == State.UNAUTHORIZED) { request(); return }
        if (state == State.READY) return
        UiNotice.show(activity, state.detail, state.action) {
            try {
                val intent = if (state == State.MISSING) null else activity.packageManager.getLaunchIntentForPackage(MANAGER)
                activity.startActivity(intent ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
            } catch (_: Exception) { UiNotice.show(activity, "无法打开，请手动安装或打开 Shizuku", error = true) }
        }
    }
}
