package moe.baas.aio.companion

class InputController {
    fun click(displayId: Int, x: Int, y: Int) = SystemAccess.click(displayId, x, y)
    fun swipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) =
        SystemAccess.swipe(displayId, x1, y1, x2, y2, durationMs)
    fun keyEvent(displayId: Int, keyCode: Int) = SystemAccess.keyEvent(displayId, keyCode)
}
