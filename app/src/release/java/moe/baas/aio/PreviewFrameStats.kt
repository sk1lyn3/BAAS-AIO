package moe.baas.aio

/** Release builds do not sample compositor statistics. */
@Suppress("UNUSED_PARAMETER")
internal object PreviewFrameStats {
    val lastSample = longArrayOf()
    fun invalidate() = Unit
    fun read(component: String) = longArrayOf()
}
