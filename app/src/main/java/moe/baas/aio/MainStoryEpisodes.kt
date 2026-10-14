package moe.baas.aio

/**
 * Main story episode numbers (help table only; the setting itself is a plain comma string) as used by core module/main_story.py (process_regions → push_episode).
 * Numbers = src/images/<server>/main_story/episode<N>.png; availability = static main_story_available_episodes[server].
 */
object MainStoryEpisodes {
    val names = listOf(
        1 to "序章", 2 to "第 1 章 对策委员会篇", 3 to "第 2 章 发条之花的帕凡舞曲篇",
        4 to "第 3 章 伊甸园条约篇", 5 to "第 4 章 卡班诺的兔子篇", 6 to "最终篇",
        7 to "第 5 章 百花缭乱篇", 8 to "Ex 神名十文字篇",
        9 to "第 6 章", 10 to "第二部 序章",
        11 to "Vol.0 联邦学生会篇", 12 to "Ex 罗亚追迹篇",
    )

    /**
     * main_story_regions text input → the comma string core main_story.process_regions splits on ",".
     * Accepts full-width commas / spaces; "" = push every chapter available on the server.
     */
    fun normalize(raw: String): String {
        val parts = raw.trim().split(Regex("[,，\\s]+")).filter { it.isNotEmpty() }
        require(parts.all { (it.toIntOrNull() ?: 0) > 0 }) { "请输入逗号分隔的章节编号，如 2,3,4；留空推全部章节" }
        return parts.joinToString(",")
    }
}
