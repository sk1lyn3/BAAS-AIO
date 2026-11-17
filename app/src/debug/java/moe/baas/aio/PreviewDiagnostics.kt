package moe.baas.aio

import android.app.Activity
import android.os.IBinder
import android.view.SurfaceView

/** Preview probes exist only in debug builds. */
internal object PreviewDiagnostics {
    fun requested(activity: Activity) = activity.intent.getBooleanExtra("latency", false) || activity.intent.getBooleanExtra("verify", false)
    fun consumesManualInput(activity: Activity) = LatencyVerifier.consumesManualInput(activity)
    fun run(activity: Activity, surface: SurfaceView, broker: ISocketBroker, bridge: CompanionBridge,
            width: Int, height: Int, owner: IBinder, idle: () -> Unit) {
        if (activity.intent.getBooleanExtra("latency", false)) LatencyVerifier.run(activity, surface, idle)
        else PreviewVerifier.run(activity, surface, broker, bridge, width, height, owner, idle, activity.intent.getBooleanExtra("hold", false))
    }
}
