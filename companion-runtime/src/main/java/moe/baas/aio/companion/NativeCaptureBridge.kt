package moe.baas.aio.companion

import android.view.Surface

object NativeCaptureBridge {
    init { System.loadLibrary("baas_companion_capture") }

    // Static entry points preserve the existing JNI symbols.
    @JvmStatic external fun setup(width: Int, height: Int): Surface?
    @JvmStatic external fun release()
    @JvmStatic external fun frameInfo(): LongArray
    @JvmStatic external fun captureFrame(): NativeFrame?
    @JvmStatic external fun saveLatestPng(path: String): Boolean
    @JvmStatic external fun setPreviewSurface(surface: Surface?)
}
