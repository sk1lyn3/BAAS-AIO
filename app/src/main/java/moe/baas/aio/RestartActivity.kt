package moe.baas.aio

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Process

/**
 * Runs in the :restart process: ends the app process that started it, opens HomeActivity in a fresh one,
 * then ends itself. Needs no permission, unlike an exact alarm, which Android 14 does not grant by default.
 */
class RestartActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val pid = intent.getIntExtra(PID, 0)
        if (pid > 0 && pid != Process.myPid()) Process.killProcess(pid)
        startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
        Process.killProcess(Process.myPid())
    }

    companion object { const val PID = "pid" }
}
