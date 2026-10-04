package moe.baas.aio

import android.app.Activity
import android.os.IBinder
import android.view.SurfaceView

/** Release previews never dispatch synthetic verification gestures. */
@Suppress("UNUSED_PARAMETER")
internal object PreviewDiagnostics {
    fun requested(activity: Activity) = false
    fun consumesManualInput(activity: Activity) = false
    fun run(activity: Activity, surface: SurfaceView, broker: ISocketBroker, bridge: CompanionBridge,
            width: Int, height: Int, owner: IBinder, idle: () -> Unit) = Unit
}
