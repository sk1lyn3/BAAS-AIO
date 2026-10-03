package moe.baas.aio

/** Only starts/stops the existing BAAS OCR engine; inference remains unchanged. */
object NativeOcr {
    init {
        System.loadLibrary("c++_shared")
        System.loadLibrary("baas_aio_ocr")
    }
    @JvmStatic external fun start(projectDir: String)
    @JvmStatic external fun stop()
}
