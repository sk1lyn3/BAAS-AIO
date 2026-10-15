package moe.baas.aio

/** One help page: [summary] first, then titled [sections] (bullet lines), two-column [tables], monospace [examples]. Must stand alone (no external docs). */
data class HelpWideTable(
    val title: String,
    val headers: List<String>,
    val rows: List<List<String>>,
    /** true: starts folded; tap the title row to expand / collapse. */
    val collapsed: Boolean = false,
)

data class HelpEntry(
    val title: String,
    val summary: String,
    val sections: List<Pair<String, List<String>>> = emptyList(),
    val examples: List<Pair<String, String>> = emptyList(),
    /** Optional two-column tables: title to (left, right) rows, e.g. node name -> yield. */
    val tables: List<Pair<String, List<Pair<String, String>>>> = emptyList(),
    /** Optional plain-text blocks at the very bottom (title to verbatim text), for long-press copying. */
    val texts: List<Pair<String, String>> = emptyList(),
    /** Optional wide tables rendered in a horizontal ScrollView (swipe left/right). */
    val wideTables: List<HelpWideTable> = emptyList(),
)

/**
 * Centralized help text keyed by config key (the `key` in assets/config-catalog.json).
 * To add help to a settings entry: add one `"key" to HelpEntry(...)` line below and the key to [keys]. ConfigForm shows the ⓘ automatically.
 * For any other Compose label, place `HelpTip("key")` right after the label; from View code call `HelpDialog.show(context, "key")`.
 * Only add help for entries with real rules (formats, priority/order, option interplay, caveats); obvious entries get none.
 */
object HelpCatalog {
    /** Built on first dialog open, not when a settings page first shows its ⓘ marks. */
    private val entries: Map<String, HelpEntry> by lazy { mapOf(
        // ── 制造 ──
        // src: core/Baas_thread.py:518-530 update_create_priority（每次任务启动时补全）；module/create.py:107-117
        "createPriority_phase1" to createPriority(1),
        "createPriority_phase2" to createPriority(2),
        "createPriority_phase3" to createPriority(3),
        // ── 咖啡厅 ──
        // src: module/cafe_reward.py:248（cafe_duplicate-invite-notice）
        "cafe_reward_allow_duplicate_invite" to HelpEntry("允许重复邀请", "目标学生已在另一个咖啡厅时仍然邀请；关闭则跳过。"),
        // src: module/cafe_reward.py:244（cafe_switch-clothes-notice）
        "cafe_reward_allow_exchange_student" to HelpEntry("允许换装邀请", "提示会更换学生服装时仍然邀请。"),
        // src: module/cafe_reward.py:387-400（按顺序尝试；为空时退回最低好感）
        "favorStudent1" to favorStudent("一号咖啡厅 · 指定学生"),
        "favorStudent2" to favorStudent("二号咖啡厅 · 指定学生"),
        // ── 竞技场 ──
        // src: gui/components/expand/arenaPriority.py:19；核心 module/arena.py:49
        "ArenaLevelDiff" to HelpEntry("对手等级差筛选", "要求对手比你低几级；接受比你高的对手则填负数。"),
        // ── 扫荡次数 ──
        // src: gui/components/expand/mainlinePriority.py:21；src/descriptions/zh_CN/（原版 html，已过时）普通图扫荡填写说明.html；核心 sweep_task.py:182 read_task
        // max：read_task 不区分区服（sweep_task.py:194），经用户确认国服可用
        "mainlinePriority" to HelpEntry(
            "普通图扫荡关卡",
            "按「区域-关卡-次数」填写，多项用逗号隔开，按顺序扫荡。",
            listOf(
                "规则" to listOf(
                    "关卡号 1–5，教程-1 与 5 图后的地图可扫荡",
                    "次数可写 max，即用完体力，各区服都支持",
                    "体力不足时结束扫荡",
                ),
            ),
            listOf("1-1-1,1-2-3" to "1-1 打一次，然后 1-2 打三次", "15-3-3,20-3-max" to "15-3 三次，剩余体力全扫 20-3") ,
        ),
        // src: gui/components/expand/mainlinePriority.py:23；核心 sweep_task.py:187（困难关卡 1–3）
        "hardPriority" to HelpEntry(
            "困难图扫荡关卡",
            "格式同普通图：「区域-关卡-次数」，逗号隔开。",
            listOf("规则" to listOf("关卡号 1–3，每关次数最多 3", "次数可写 max，各区服都支持")),
            listOf("10-1-3,12-3-3" to "10-1、12-3 各三次"),
        ),
        // src: module/clear_special_task_power.py:129-148 get_task_count（项数少于要求则整项跳过；"max" 不区分区服）
        //      rewarded_task.py:8,19,162-166（日服 5 个区域，其他 3 个；核心 54292e6 起）；scrimmage.py:9,23（固定 3 个）；clear_special_task_power.py:7,16（固定 2 个）
        //      max：点扫荡界面的最大按钮（rewarded_task.py:127、scrimmage.py:99、clear_special_task_power.py:101），完成后结束整个任务
        "rewarded_task_times" to sweepCounts("悬赏通缉次数", "国服、国际服 3 个区域，日服 5 个区域", 3, "2,2,2" to "三个区域各 2 次", "2,2,2,1,1" to "日服：前三个区域各 2 次，后两个各 1 次"),
        "scrimmage_times" to sweepCounts("学园交流会次数", "共 3 个区域：崔尼蒂、歌赫娜、千禧年", 3, "2,2,2" to "三个区域各 2 次"),
        "special_task_times" to sweepCounts("特别委托次数", "只有 2 个区域：据点防御、信用回收", 2, "1,1" to "两个区域各 1 次"),
        // src: src/descriptions/zh_CN/活动扫荡填写说明.html；核心 module/activities/activity_utils.py:257
        "activity_sweep_task_number" to HelpEntry(
            "活动关卡编号",
            "1 到最高难度之间的整数，多关用逗号隔开，与「活动扫荡次数」一一对应。",
            examples = listOf("9,10,11" to "依次扫第 9、10、11 关"),
        ),
        // src: src/descriptions/zh_CN/活动扫荡填写说明.html
        // src: module/activities/activity_utils.py:257-300 activity_sweep；:757-782 preprocess_activity_sweep_times
        "activity_sweep_times" to HelpEntry(
            "活动扫荡次数",
            "与「活动关卡编号」逐项对应，英文逗号隔开，每项可以是：",
            listOf(
                "写法" to listOf(
                    "整数：扫荡次数；超过 50 视为「当前体力能扫多少就扫多少」",
                    "小数 / 分数：按开始时体力的比例换算次数，如 0.5、1/3；大于 1 按 1 算",
                    "只写一个 -1：用光全部体力，平均分给所列关卡，每关次数 = 体力 ÷ 各关单次体力之和",
                ),
                "注意" to listOf(
                    "次数一律向下取整",
                    "体力只在开始时读一次，各项比例都按这个值计算，比例加起来超过 1 时后面的关卡会体力不足",
                ),
            ),
            listOf(
                "0.5,3,1/3" to "体力 999、每关 20 体力：24 次、3 次、16 次",
                "-1" to "体力 999、两关各 20 体力：每关 24 次",
            ),
        ),
        // ── 日程课 ──
        // src: gui/components/expand/schedulePriority.py:70；含义经用户确认
        "lesson_relationship_first" to HelpEntry("优先最多学生", "优先选择能获得最多学生好感的区域和日程。"),
        // src: module/lesson.py:invite_favor_student; core/lesson_students.py
        "lesson_favorStudent" to HelpEntry(
            "指定学生",
            "开启「优先邀请指定学生上课」时生效。逗号隔开，越靠前越优先；剩余课程券再按各区域上课次数使用。",
            listOf("注意" to listOf(
                "各区服均可填写简体中文名，无需跟随游戏语言更换名字",
                "同一学生出现在多个学园时，可能使用多张课程券",
            )),
            listOf("爱丽丝（女仆）, 爱丽丝" to "先找女仆爱丽丝，再找爱丽丝"),
        ),
        // src: module/lesson.py:42-47（for k in range(len(lesson_times))，0 跳过，按下标取 static lesson_region_name[区服]）
        //      区域数：default_config.py lesson_region_name — 国服 11、国际服/日服 12；超出时 region_name[k] 越界报错
        "lesson_times" to HelpEntry(
            "各区域上课次数",
            "按课程表区域顺序逐项填写次数，0 = 跳过；课程券不足时提前结束。",
            listOf("长度" to listOf(
                "可以只写前几项，没写到的区域不上课",
                "最多写到本区服的区域数：国服 11 个，国际服、日服 12 个；写多了任务会出错",
            )),
            examples = listOf("1, 0, 2" to "第 1 区 1 次，跳过第 2 区，第 3 区 2 次，其余区域不上课"),
        ),
        // ── 推图与剧情 ──
        // src: gui/components/expand/exploreConfig.py:29；src/descriptions/zh_CN/普通图自动推图说明.html
        // 核心 module/explore_tasks/explore_task.py:14-70：只接受「区域」或「区域-关卡」（3 的倍数区域可写 区域-A）
        "explore_normal_task_list" to HelpEntry(
            "普通图推图关卡列表",
            "填数字推整个区域，填「区域-关卡」推指定关卡，逗号隔开按顺序执行。支持 4–30 图。",
            listOf(
                "注意" to listOf(
                    "需要已解锁自动结束回合和自动战斗",
                    "移动路线固定，出击队伍编号必须由小到大",
                    "队伍按「编队配置」和区域所需属性挑选",
                ),
            ),
            listOf("15,16-3" to "15-1 到 15-5，然后 16-3"),
            // src: explore_task.py:41-58（A = 第 6 关，region % 3 == 0）；baas-core src/explore_task_data/normal_task/*.json "start"（swipe 是拖动地图，不算队伍：module/explore_tasks/task_utils.py:429,437,450）
            wideTables = listOf(ExploreAttributes.normal),
        ),
        // src: gui/components/expand/exploreConfig.py:37；src/descriptions/zh_CN/困难图配置说明.html
        // 注意：原版文档里的 sss/present/task 关键字，当前核心 explore_task.py:54 已不接受，这里只保留数字写法
        "explore_hard_task_list" to HelpEntry(
            "困难图推图关卡列表",
            "写法同普通图，区域内为 1–3 关，已 sss、已拿礼物的关卡自动跳过。",
            listOf("注意" to listOf("选队逻辑同普通图")),
            listOf("15,12-2" to "15-1 到 15-3，然后 12-2"),
            wideTables = listOf(ExploreAttributes.hard),
        ),
        // src: module/main_story.py:13-17（为空时用 static main_story_available_episodes[区服]）、:291 push_episode、:20-29 episode_stage
        // 编号 = src/images/<区服>/main_story/episode<N>.png 的章节标题；6 = main_story_final_episode_num（最终篇）
        // 原版 html（已过时）的示例“1,3,5 = 第一、三章、最终章”与编号不符，按核心编号重写
        "main_story_regions" to HelpEntry(
            "主线剧情需要推的章节编号",
            "填章节编号，逗号隔开，按填写顺序推；留空则推本区服全部章节。最终篇部分战斗无法自动完成。",
            tables = listOf(
                "章节编号" to MainStoryEpisodes.names.map { (n, name) -> n.toString() to name },
                "留空时的默认" to listOf("国服" to "1–8", "国际服、日服" to "1–12"),
            ),
            examples = listOf("2,3,4" to "依次推第 1、2、3 章"),
        ),
        // ── 编队 ──
        // src: module/explore_tasks/task_utils.py:162-170, 416-434
        "choose_team_method" to HelpEntry(
            "选队方式",
            "推图时如何挑选出击队伍：",
            listOf(
                "方式" to listOf(
                    "按侧边栏顺序：依次用 1、2、3… 号队，不看属性",
                    "按侧栏属性编队：按「侧栏属性顺序」挑克制关卡的队伍",
                    "使用预设编队：按「预设编队属性」挑选",
                    "属性队伍不够时自动退回按顺序",
                ),
            ),
        ),
        // src: module/explore_tasks/task_utils.py:164-170
        "side_team_attribute" to HelpEntry(
            "侧栏属性顺序",
            "仅「按侧栏属性编队」时生效。按侧栏从上到下填每队的主要属性，不用的选「未使用」。BAAS 内置各区域所需属性，会自动挑选克制的队伍。",
        ),
        // src: module/explore_tasks/task_utils.py:161-170 convert_team_config（第 i 组 → 预设标签 i，第 j 格 → 该标签里 j 号预设）
        //      task_utils.py:452-500 employ_units（编队 → 预设 → 第 i 个标签 → 找到 j 号预设并使用）；:412 属性不够时按 爆发→神秘→振动→贯穿→爆发 借用
        "preset_team_attribute" to HelpEntry(
            "预设编队属性",
            "仅「选队方式」为「使用预设编队」时生效。告诉 BAAS 你的游戏预设里，哪个预设是什么属性的队伍。",
            listOf(
                "对应关系" to listOf(
                    "第 N 组 = 游戏「编队 → 预设」页上方的第 N 个标签",
                    "组内第 M 格 = 该标签里的 M 号预设，M 为 1–5",
                    "填该预设队伍的主要属性；空着的预设选「未使用」",
                    "关卡需要某属性时，BAAS 会打开对应标签选中那个预设出击；该属性不够时依次借用 神秘→振动→贯穿→爆发 的队伍",
                ),
            ),
            listOf("第 1 组：爆发, 贯穿, 未使用, 未使用, 未使用" to "第 1 个标签里，1 号预设是爆发队，2 号预设是贯穿队；需要爆发队时就用 1 号预设"),
        ),
        // ── 总力战 ──
        // src: module/total_assault.py（只使用游戏内自动战斗），经用户确认
        "totalForceFightDifficulty" to HelpEntry("最高挑战难度", "只支持自动战斗，请先在游戏里开启总力战的自动战斗。"),
    ) }
    // src: core/Baas_thread.py:518-530（保留合法名称的顺序，再按区服默认顺序补齐其余节点，写回配置）
    //      默认顺序 = core/config/default_config.py:739 create_default_priority[区服][phaseN]；module/create.py:107 非中/英/日客户端会把英文名转换成对应语言
    // ref: https://bluearchive.wiki/wiki/Crafting （节点产物）；https://game8.jp/blue-archive/642736
    // 已核对: 灿烂=Radiant(T4)、光芒=Shiny(T3)、玲珑=Brilliant(T2)、隐然=Subtle(T1)。依据 create_default_priority 中与 Global 按位置对齐的
    //        Global_zh-tw(燦爛/閃亮/璀璨/隱約)、Global_ko-kr(찬란함=燦爛/반짝임/영롱함=玲瓏/은은함=隱隱) 列表（module/create.py:107 按位置转换），产物档位见 wiki Crafting
    // 已修复: AIO 保存时把这三项拆成列表（ConfigCatalog.splitList），避免核心按单个字符匹配
    private fun createPriority(phase: Int) = HelpEntry(
        "制造优先级",
        "每次制造时，在随机出现的 5 个节点里按这个顺序挑选，越靠前越优先。填节点名称，逗号隔开。",
        listOf(
            "规则" to listOf(
                "不用写全：任务启动时，BAAS 会保留你写的合法名称，再按默认顺序补齐其余节点并写回配置",
                "名称必须和本区服节点名一致：国服用中文、国际服用英文、日服用日文；不认识的名称会被丢弃",
                "默认顺序以「花」开头，即默认优先做礼物",
            ),
        ),
        tables = listOf(
            "常见节点产物" to listOf(
                "花" to "学生礼物",
                "色彩" to "随机系列的咖啡厅家具",
                "××主题 / 组合" to "该系列家具，如桃桃朋友咖啡厅、温泉浴场",
                "灿烂" to "最上级紫色材料",
                "光芒" to "上级金色材料",
                "玲珑" to "中级蓝色材料",
                "隐然" to "初级白色材料",
                "金属" to "成长材料：报告、遗物、BD、技术笔记等",
                "铜" to "战术教育 BD",
                "白银" to "经验活动报告",
                "黄金" to "遗物 Oopart",
                "白金" to "技术笔记",
                "武器部件" to "专武成长材料，不含撞针",
            ),
            "第 2 阶段花的礼物类别" to listOf(
                "牡丹" to "寝具", "牵牛花" to "运动", "玫瑰" to "电子产品", "郁金香" to "书籍",
                "樱花" to "化妆品", "水仙花" to "甜点", "紫薇花" to "玩具", "蒲公英" to "古董",
                "百合花" to "门票", "金盏花" to "日用品", "桔梗花" to "园艺", "木莲花" to "时尚", "杜鹃花" to "健康食品",
                "翡翠花" to "海滩",
                "百鬼夜行、红冬等学校" to "该学校的 BD 或技术笔记",
            ),
        ),
        wideTables = CraftingNames.wideTables(phase),
    )
    private fun favorStudent(title: String) = HelpEntry(
        title,
        "该功能暂时无法使用。邀请模式为「指定学生」时生效。逗号隔开，按顺序查找并邀请；列表为空则改邀好感最低的学生。",
    )
    private fun sweepCounts(title: String, regions: String, min: Int, vararg examples: Pair<String, String>) = HelpEntry(
        title,
        "按区域顺序填写各区域扫荡次数，英文逗号隔开。$regions；少于 $min 项时整个任务不执行。",
        listOf("规则" to listOf(
            "0 = 跳过该区域",
            "可写 max：扫到不能再扫为止，之后不再扫后面的区域，各区服都一样",
        )),
        examples.toList(),
    )
    /** Keys that have an entry; cheap, so ConfigForm can decide on ⓘ without building any entry. */
    val keys: Set<String> = setOf(
        "createPriority_phase1", "createPriority_phase2", "createPriority_phase3", "cafe_reward_allow_duplicate_invite",
        "cafe_reward_allow_exchange_student", "favorStudent1", "favorStudent2", "ArenaLevelDiff", "mainlinePriority",
        "hardPriority", "rewarded_task_times", "scrimmage_times", "special_task_times", "activity_sweep_task_number",
        "activity_sweep_times", "lesson_relationship_first", "lesson_favorStudent", "lesson_times", "explore_normal_task_list",
        "explore_hard_task_list", "main_story_regions", "choose_team_method", "side_team_attribute", "preset_team_attribute",
        "totalForceFightDifficulty",
    )
    fun has(key: String) = key in keys
    operator fun get(key: String) = entries[key]
}
