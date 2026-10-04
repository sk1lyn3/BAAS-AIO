package moe.baas.aio.companion

class AppController {
    fun launch(packageName: String, activityName: String?, displayId: Int) =
        SystemAccess.launchOnDisplay(packageName, activityName, displayId)
}
