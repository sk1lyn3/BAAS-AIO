package moe.baas.aio.companion

class CaptureController {
    fun capturePng(path: String) = if (NativeCaptureBridge.saveLatestPng(path)) path else ""
}
