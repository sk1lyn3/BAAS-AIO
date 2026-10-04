package moe.baas.aio.companion

class CompanionState {
    @Volatile var displayId = SystemAccess.DISPLAY_NONE
    var width = 0
    var height = 0
    var dpi = 0
    var lastError = ""

    fun setDisplay(displayId: Int, width: Int, height: Int, dpi: Int) {
        this.displayId = displayId
        this.width = width
        this.height = height
        this.dpi = dpi
        lastError = ""
    }

    fun clearDisplay() { displayId = SystemAccess.DISPLAY_NONE }
    fun setError(error: Throwable) { lastError = "${error.javaClass.simpleName}: ${error.message}" }
    fun status(displayAlive: Boolean) =
        "displayId=$displayId size=${width}x$height/$dpi alive=$displayAlive error=$lastError"
}
