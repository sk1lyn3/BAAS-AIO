package moe.baas.aio

/** Server names in profiles map to the CN / Global / JP keys of core's static tables. */
object GameServer {
    val MODES = listOf("CN", "Global", "JP")
    fun mode(server: String) = when (server) { "日服", "日服PC端" -> "JP"; "官服", "B服" -> "CN"; else -> "Global" }
}
