package moe.baas.aio

import org.json.JSONObject

/** WebUI's explicit task buttons; execution is delegated to upstream solve(). */
object ManualTasks {
    fun displayName(task: String, labels: Map<String, String>): String =
        if (task == "restart") "检查/恢复游戏" else labels[task] ?: names[task] ?: task

    val names = linkedMapOf(
        "explore_normal_task" to "普通图推图", "explore_hard_task" to "困难图推图",
        "main_story" to "主线剧情", "group_story" to "小组剧情", "mini_story" to "短篇剧情",
        "explore_activity_story" to "活动故事", "explore_activity_mission" to "活动任务",
        "explore_activity_challenge" to "活动挑战", "de_clothes" to "一键反和谐"
    )
    fun validate(task: String, options: JSONObject) {
        require(task in names) { "Unknown manual task: $task" }
        val activity = task.startsWith("explore_activity_")
        options.keys().forEach { key ->
            require(activity && key in setOf("activity_use_generic_entry", "activity_generic_start_at_menu", "activity_generic_total_story")) { "Unsupported task option" }
            if (key == "activity_generic_total_story") {
                require(task == "explore_activity_story" && options.get(key) is Int && options.getInt(key) in 1..99) { "故事数量应为 1–99" }
            } else require(options.get(key) is Boolean) { "Invalid activity option" }
        }
    }
}
