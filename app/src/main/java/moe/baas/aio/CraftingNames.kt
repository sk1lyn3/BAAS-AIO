package moe.baas.aio

/** Data from baas-core core/config/default_config.py create_default_priority. */
object CraftingNames {
    private val mapping1 = listOf(
        listOf("花", "Flower", "花弁", "花", "꽃"),
        listOf("桃桃朋友咖啡厅", "Momo Friends Cafe Set", "モモフレンズのカフェシリーズ", "Momo好友咖啡廳組合", "모모프렌즈 카페 세트"),
        listOf("果冻游戏中心", "Jellies Arcade Set", "ゼリーズゲーセンシリーズ", "果凍遊戲中心組合", "젤리즈 오락실 세트"),
        listOf("情人节", "Valentine's Set", "バレンタインシリーズ", "情人節組合", "발렌타인 세트"),
        listOf("夏日", "Summer Pool Party Set", "サマーリゾートシリーズ", "避暑勝地泳池派對組合", "여름 리조트 풀파티 세트"),
        listOf("万圣节", "Jack-O-Lantern Cafe Set", "ハロウィーンカフェシリーズ", "萬聖節南瓜咖啡廳組合", "할로윈 펌킨 카페 세트"),
        listOf("温泉浴场", "Hot Springs Resort Set", "伝統的な温泉郷シリーズ", "傳統溫泉浴場組合", "전통 온천장 세트"),
        listOf("新年", "New Year's House Set", "お正月シリーズ", "正月居家組合", "새해 하우스 세트"),
        listOf("军事露营组合", "Military Campground Set", "ミリタリーアウトドア", "軍事露營區組合", "밀리터리 캠핑장 세트"),
        listOf("海滩组合", "Beachside Set", "ビーチサイドシリーズ", "海灘組合", "비치사이드 세트"),
        listOf("大运动会主题", "Field Day Set", "大運動会シリーズ", "大運動會主題", "대운동회 세트"),
        listOf("阿拜多斯讲堂主题", "Abydos Classroom Set", "アビドス教室シリーズ", "阿拜多斯教室組合", "아비도스 교실 세트"),
        listOf("游戏开发部主题", "Game Development Department Set", "ゲーム開発部シリーズ", "遊戲開發部組合", "게임개발부 세트"),
        listOf("—", "Sunshine Resort Set", "サンシャインリゾートシリーズ", "陽光渡假勝地組合", "선샤인 리조트 세트"),
        listOf("—", "Shopping Mall Set", "デパートシリーズ", "購物中心組合", "쇼핑몰 세트"),
        listOf("—", "Gehenna Party Set", "ゲヘナパーティーシリーズ", "格黑娜派對組合", "게헨나 파티 세트"),
        listOf("—", "Trinity Classroom Set", "トリニティ教室シリーズ", "三一教室組合", "트리니티 교실 세트"),
        listOf("—", "DJ Party Venue Set", "DJパーティー会場シリーズ", "DJ派對會場組合", "DJ 파티장 세트"),
        listOf("色彩", "Colorful", "色彩", "顏色", "색깔"),
        listOf("灿烂", "Radiant", "煌めき", "燦爛", "찬란함"),
        listOf("武器部件", "Parts", "パーツ", "武器零件", "무기 부품"),
        listOf("铜", "Copper", "铜", "銅", "구리"),
        listOf("白金", "Platinum", "プラチナ", "白金", "백금"),
        listOf("黄金", "Gold", "金", "金", "금"),
        listOf("白银", "Silver", "銀", "銀", "은"),
        listOf("金属", "Metal", "金属", "金屬", "금속"),
        listOf("光芒", "Shiny", "輝き", "閃亮", "반짝임"),
        listOf("玲珑", "Brilliant", "明かり", "璀璨", "영롱함"),
        listOf("隐然", "Subtle", "瞬き", "隱約", "은은함"),
        listOf("—", "—", "百鬼夜行シリーズ", "—", "—")
    )
    private val orders1 = listOf(
        listOf("国服", "花, 桃桃朋友咖啡厅, 阿拜多斯讲堂主题, 游戏开发部主题, 果冻游戏中心, 情人节, 夏日, 万圣节, 温泉浴场, 新年, 色彩, 军事露营组合, 海滩组合, 大运动会主题, 灿烂, 武器部件, 铜, 光芒, 玲珑, 白金, 黄金, 白银, 金属, 隐然"),
        listOf("国际服EN", "Flower, Momo Friends Cafe Set, Jellies Arcade Set, Valentine's Set, Summer Pool Party Set, Jack-O-Lantern Cafe Set, Hot Springs Resort Set, New Year's House Set, Military Campground Set, Beachside Set, Field Day Set, Abydos Classroom Set, Game Development Department Set, Sunshine Resort Set, Shopping Mall Set, Gehenna Party Set, Trinity Classroom Set, DJ Party Venue Set, Colorful, Radiant, Parts, Copper, Platinum, Gold, Silver, Metal, Shiny, Brilliant, Subtle"),
        listOf("日服", "花弁, モモフレンズのカフェシリーズ, ゼリーズゲーセンシリーズ, バレンタインシリーズ, サマーリゾートシリーズ, ハロウィーンカフェシリーズ, 伝統的な温泉郷シリーズ, お正月シリーズ, ミリタリーアウトドア, ビーチサイドシリーズ, 大運動会シリーズ, アビドス教室シリーズ, ゲーム開発部シリーズ, サンシャインリゾートシリーズ, デパートシリーズ, ゲヘナパーティーシリーズ, トリニティ教室シリーズ, DJパーティー会場シリーズ, 百鬼夜行シリーズ, 色彩, 煌めき, パーツ, 輝き, 铜, 金属, 銀, 金, プラチナ, 明かり, 瞬き"),
        listOf("台服", "花, Momo好友咖啡廳組合, 果凍遊戲中心組合, 情人節組合, 避暑勝地泳池派對組合, 萬聖節南瓜咖啡廳組合, 傳統溫泉浴場組合, 正月居家組合, 軍事露營區組合, 海灘組合, 大運動會主題, 阿拜多斯教室組合, 遊戲開發部組合, 陽光渡假勝地組合, 購物中心組合, 格黑娜派對組合, 三一教室組合, DJ派對會場組合, 顏色, 燦爛, 武器零件, 銅, 白金, 金, 銀, 金屬, 閃亮, 璀璨, 隱約"),
        listOf("韩服", "꽃, 모모프렌즈 카페 세트, 젤리즈 오락실 세트, 발렌타인 세트, 여름 리조트 풀파티 세트, 할로윈 펌킨 카페 세트, 전통 온천장 세트, 새해 하우스 세트, 밀리터리 캠핑장 세트, 비치사이드 세트, 대운동회 세트, 아비도스 교실 세트, 게임개발부 세트, 선샤인 리조트 세트, 쇼핑몰 세트, 게헨나 파티 세트, 트리니티 교실 세트, DJ 파티장 세트, 색깔, 찬란함, 무기 부품, 구리, 백금, 금, 은, 금속, 반짝임, 영롱함, 은은함")
    )
    private val mapping2 = listOf(
        listOf("花", "Flower", "花弁", "花", "꽃"),
        listOf("牡丹", "Peony", "牡丹", "牡丹", "모란"),
        listOf("牵牛花", "Morning Glory", "アサガオ", "牽牛花", "나팔꽃"),
        listOf("玫瑰", "Rose", "薔薇", "玫瑰", "장미"),
        listOf("郁金香", "Tulip", "チューリップ", "鬱金香", "튤립"),
        listOf("樱花", "Cherry Blossom", "桜", "櫻花", "벚꽃"),
        listOf("水仙花", "Daffodil", "スイセン", "水仙花", "수선화"),
        listOf("紫薇花", "Zinnia", "百日紅", "百日菊", "백일홍"),
        listOf("蒲公英", "Dandelion", "タンポポ", "蒲公英", "민들레"),
        listOf("百合花", "Lily", "ユリ", "百合", "백합"),
        listOf("金盏花", "Marigold", "キンセンカ", "金盞花", "황금초"),
        listOf("桔梗花", "Bellflower", "桔梗", "桔梗", "도라지꽃"),
        listOf("木莲花", "Magnolia", "モクレン", "玉蘭花", "목련"),
        listOf("杜鹃花", "Azalea", "ツツジ", "杜鵑花", "진달래"),
        listOf("翡翠花", "Jade Blossom", "翡翠花", "翡翠花", "비취꽃"),
        listOf("灿烂", "Radiant", "煌めき", "燦爛", "찬란함"),
        listOf("光芒", "Shiny", "輝き", "閃亮", "반짝임"),
        listOf("色彩", "Colorful", "色彩", "顏色", "색깔"),
        listOf("桃桃朋友咖啡厅", "Momo Friends Cafe Set", "モモフレンズのカフェシリーズ", "Momo好友咖啡廳組合", "모모프렌즈 카페 세트"),
        listOf("果冻游戏中心", "Jellies Arcade Set", "ゼリーズゲーセンシリーズ", "果凍遊戲中心組合", "젤리즈 오락실 세트"),
        listOf("情人节", "Valentine's Set", "バレンタインシリーズ", "情人節組合", "발렌타인 세트"),
        listOf("夏日", "Summer Pool Party Set", "サマーリゾートシリーズ", "避暑勝地泳池派對組合", "여름 리조트 풀파티 세트"),
        listOf("万圣节", "Jack-O-Lantern Cafe Set", "ハロウィーンカフェシリーズ", "萬聖節南瓜咖啡廳組合", "할로윈 펌킨 카페 세트"),
        listOf("温泉浴场", "Hot Springs Resort Set", "伝統的な温泉郷シリーズ", "傳統溫泉浴場組合", "전통 온천장 세트"),
        listOf("新年", "New Year's House Set", "お正月シリーズ", "正月居家組合", "새해 하우스 세트"),
        listOf("军事露营组合", "Military Campground Set", "ミリタリーアウトドア", "軍事露營區組合", "밀리터리 캠핑장 세트"),
        listOf("海滩组合", "Beachside Set", "ビーチサイドシリーズ", "海灘組合", "비치사이드 세트"),
        listOf("大运动会主题", "Field Day Set", "大運動会シリーズ", "大運動會主題", "대운동회 세트"),
        listOf("—", "Abydos Classroom Set", "アビドス教室シリーズ", "阿拜多斯教室組合", "아비도스 교실 세트"),
        listOf("—", "Game Development Department Set", "ゲーム開発部シリーズ", "遊戲開發部組合", "게임개발부 세트"),
        listOf("—", "Sunshine Resort Set", "サンシャインリゾートシリーズ", "陽光渡假勝地組合", "선샤인 리조트 세트"),
        listOf("—", "Shopping Mall Set", "デパートシリーズ", "購物中心組合", "쇼핑몰 세트"),
        listOf("—", "Gehenna Party Set", "ゲヘナパーティーシリーズ", "格黑娜派對組合", "게헨나 파티 세트"),
        listOf("—", "Trinity Classroom Set", "トリニティ教室シリーズ", "三一教室組合", "트리니티 교실 세트"),
        listOf("—", "DJ Party Venue Set", "DJパーティー会場シリーズ", "DJ派對會場組合", "DJ 파티장 세트"),
        listOf("红色", "Red", "レッド", "紅", "빨강"),
        listOf("黄色", "Yellow", "イエロー", "黃", "노랑"),
        listOf("褐色", "Brown", "ブラウン", "褐色", "갈색"),
        listOf("橘黄色", "Orange", "アンバー", "橘色", "주황"),
        listOf("粉色", "Pink", "ピンク", "粉紅", "분홍"),
        listOf("灰色", "Gray", "グレー", "灰色", "회색"),
        listOf("黑色", "Black", "ブラック", "黑色", "검정"),
        listOf("浅绿色", "Yellow-Green", "グリーン", "淺綠色", "연두색"),
        listOf("青绿色", "Turquoise", "ブルー", "翠綠色", "청록색"),
        listOf("白色", "White", "ホワイト", "白色", "흰색"),
        listOf("紫色", "Purple", "パープル", "紫色", "보라"),
        listOf("百鬼夜行", "Hyakkiyako", "百鬼夜行", "百鬼夜行", "백귀야행"),
        listOf("红冬", "Red Winter", "レッドウィンター", "赤冬", "붉은겨울"),
        listOf("崔尼蒂", "Trinity", "トリニティ", "三一", "트리니티"),
        listOf("歌赫娜", "Gehenna", "ゲヘナ", "格黑娜", "게헨나"),
        listOf("阿拜多斯", "Abydos", "アビドス", "阿拜多斯", "아비도스"),
        listOf("千禧年", "Millennium", "ミレニアム", "千年", "밀레니엄"),
        listOf("山海经", "Shanhaijing", "山海経", "山海經", "산해경"),
        listOf("瓦尔基里", "Valkyrie", "ヴァルキューレ", "女武神", "발키리"),
        listOf("阿里乌斯", "Arius", "アリウス", "奧利斯", "아리우스"),
        listOf("金属", "Metal", "金属", "金屬", "금속"),
        listOf("白银", "Silver", "銀", "銀", "은"),
        listOf("黄金", "Gold", "金", "金", "금"),
        listOf("白金", "Platinum", "プラチナ", "白金", "백금"),
        listOf("铜", "Copper", "銅", "銅", "구리"),
        listOf("武器部件", "Weapon Parts", "パーツ", "武器零件", "무기 부품"),
        listOf("玲珑", "Brilliant", "明かり", "璀璨", "영롱함"),
        listOf("—", "—", "百鬼夜行シリーズ", "—", "—")
    )
    private val orders2 = listOf(
        listOf("国服", "花, 牡丹, 牵牛花, 玫瑰, 郁金香, 樱花, 水仙花, 紫薇花, 蒲公英, 百合花, 金盏花, 桔梗花, 木莲花, 杜鹃花, 翡翠花, 灿烂, 光芒, 色彩, 桃桃朋友咖啡厅, 果冻游戏中心, 情人节, 夏日, 万圣节, 温泉浴场, 新年, 军事露营组合, 海滩组合, 大运动会主题, 红色, 黄色, 褐色, 橘黄色, 粉色, 灰色, 黑色, 浅绿色, 青绿色, 白色, 紫色, 百鬼夜行, 红冬, 崔尼蒂, 歌赫娜, 阿拜多斯, 千禧年, 山海经, 瓦尔基里, 阿里乌斯, 金属, 白银, 黄金, 白金, 铜, 武器部件, 玲珑"),
        listOf("国际服EN", "Flower, Peony, Morning Glory, Rose, Tulip, Cherry Blossom, Daffodil, Zinnia, Dandelion, Lily, Marigold, Bellflower, Magnolia, Azalea, Jade Blossom, Radiant, Shiny, Colorful, Momo Friends Cafe Set, Jellies Arcade Set, Valentine's Set, Summer Pool Party Set, Jack-O-Lantern Cafe Set, Hot Springs Resort Set, New Year's House Set, Military Campground Set, Beachside Set, Field Day Set, Abydos Classroom Set, Game Development Department Set, Sunshine Resort Set, Shopping Mall Set, Gehenna Party Set, Trinity Classroom Set, DJ Party Venue Set, Red, Yellow, Brown, Orange, Pink, Gray, Black, Yellow-Green, Turquoise, White, Purple, Hyakkiyako, Red Winter, Trinity, Gehenna, Abydos, Millennium, Shanhaijing, Valkyrie, Arius, Metal, Silver, Gold, Platinum, Copper, Weapon Parts, Brilliant"),
        listOf("日服", "花弁, 牡丹, アサガオ, 薔薇, チューリップ, 桜, スイセン, 百日紅, タンポポ, ユリ, キンセンカ, 桔梗, モクレン, ツツジ, 翡翠花, 煌めき, 輝き, 色彩, モモフレンズのカフェシリーズ, ゼリーズゲーセンシリーズ, バレンタインシリーズ, サマーリゾートシリーズ, ハロウィーンカフェシリーズ, 伝統的な温泉郷シリーズ, お正月シリーズ, ミリタリーアウトドア, ビーチサイドシリーズ, 大運動会シリーズ, アビドス教室シリーズ, ゲーム開発部シリーズ, サンシャインリゾートシリーズ, デパートシリーズ, ゲヘナパーティーシリーズ, トリニティ教室シリーズ, DJパーティー会場シリーズ, 百鬼夜行シリーズ, レッド, イエロー, ブラウン, アンバー, ピンク, グレー, ブラック, グリーン, ブルー, ホワイト, パープル, 百鬼夜行, レッドウィンター, トリニティ, ゲヘナ, アビドス, ミレニアム, 山海経, ヴァルキューレ, アリウス, 金属, 銀, 金, プラチナ, 銅, パーツ, 明かり"),
        listOf("台服", "花, 牡丹, 牽牛花, 玫瑰, 鬱金香, 櫻花, 水仙花, 百日菊, 蒲公英, 百合, 金盞花, 桔梗, 玉蘭花, 杜鵑花, 翡翠花, 燦爛, 閃亮, 顏色, Momo好友咖啡廳組合, 果凍遊戲中心組合, 情人節組合, 避暑勝地泳池派對組合, 萬聖節南瓜咖啡廳組合, 傳統溫泉浴場組合, 正月居家組合, 軍事露營區組合, 海灘組合, 大運動會主題, 阿拜多斯教室組合, 遊戲開發部組合, 陽光渡假勝地組合, 購物中心組合, 格黑娜派對組合, 三一教室組合, DJ派對會場組合, 紅, 黃, 褐色, 橘色, 粉紅, 灰色, 黑色, 淺綠色, 翠綠色, 白色, 紫色, 百鬼夜行, 赤冬, 三一, 格黑娜, 阿拜多斯, 千年, 山海經, 女武神, 奧利斯, 金屬, 銀, 金, 白金, 銅, 武器零件, 璀璨"),
        listOf("韩服", "꽃, 모란, 나팔꽃, 장미, 튤립, 벚꽃, 수선화, 백일홍, 민들레, 백합, 황금초, 도라지꽃, 목련, 진달래, 비취꽃, 찬란함, 반짝임, 색깔, 모모프렌즈 카페 세트, 젤리즈 오락실 세트, 발렌타인 세트, 여름 리조트 풀파티 세트, 할로윈 펌킨 카페 세트, 전통 온천장 세트, 새해 하우스 세트, 밀리터리 캠핑장 세트, 비치사이드 세트, 대운동회 세트, 아비도스 교실 세트, 게임개발부 세트, 선샤인 리조트 세트, 쇼핑몰 세트, 게헨나 파티 세트, 트리니티 교실 세트, DJ 파티장 세트, 빨강, 노랑, 갈색, 주황, 분홍, 회색, 검정, 연두색, 청록색, 흰색, 보라, 백귀야행, 붉은겨울, 트리니티, 게헨나, 아비도스, 밀레니엄, 산해경, 발키리, 아리우스, 금속, 은, 금, 백금, 구리, 무기 부품, 영롱함")
    )
    private val mapping3 = listOf(
        listOf("钻石", "Diamond", "ダイヤモンド", "鑽石", "다이아몬드"),
        listOf("神秘", "Mystic", "神秘", "神秘", "신비"),
        listOf("花", "Flower", "花弁", "花", "꽃"),
        listOf("灿烂", "Radiant", "煌めき", "燦爛", "찬란함"),
        listOf("闪亮", "Shiny", "輝き", "閃亮", "반짝임"),
        listOf("颜色", "Colorful", "色彩", "顏色", "색깔"),
        listOf("富足", "Abundance", "豊かさ", "很富足", "풍족함"),
        listOf("银", "Silver", "銀", "銀", "은"),
        listOf("金", "Gold", "金", "金", "금"),
        listOf("白金", "Platinum", "プラチナ", "白金", "백금"),
        listOf("铜", "Copper", "銅", "銅", "구리"),
        listOf("武器部件", "Weapon Parts", "パーツ", "武器零件", "무기 부품"),
        listOf("百鬼夜行", "Hyakkiyako", "百鬼夜行", "百鬼夜行", "백귀야행"),
        listOf("红冬", "Red Winter", "レッドウィンター", "赤冬", "붉은겨울"),
        listOf("崔尼蒂", "Trinity", "トリニティ", "三一", "트리니티"),
        listOf("歌赫娜", "Gehenna", "ゲヘナ", "格黑娜", "게헨나"),
        listOf("阿拜多斯", "Abydos", "アビドス", "阿拜多斯", "아비도스"),
        listOf("千禧年", "Millennium", "ミレニアム", "千年", "밀레니엄"),
        listOf("山海经", "Shanhaijing", "山海経", "山海經", "산해경"),
        listOf("瓦尔基里", "Valkyrie", "ヴァルキューレ", "女武神", "발키리"),
        listOf("阿里乌斯", "Arius", "アリウス", "奧利斯", "아리우스")
    )
    private val orders3 = listOf(
        listOf("国服", "钻石, 神秘, 花, 灿烂, 闪亮, 颜色, 富足, 银, 金, 白金, 铜, 武器部件, 百鬼夜行, 红冬, 崔尼蒂, 歌赫娜, 阿拜多斯, 千禧年, 山海经, 瓦尔基里, 阿里乌斯"),
        listOf("国际服EN", "Diamond, Mystic, Flower, Radiant, Shiny, Colorful, Abundance, Silver, Gold, Platinum, Copper, Weapon Parts, Hyakkiyako, Red Winter, Trinity, Gehenna, Abydos, Millennium, Shanhaijing, Valkyrie, Arius"),
        listOf("日服", "ダイヤモンド, 神秘, 花弁, 煌めき, 輝き, 色彩, 豊かさ, 銀, 金, プラチナ, 銅, パーツ, 百鬼夜行, レッドウィンター, トリニティ, ゲヘナ, アビドス, ミレニアム, 山海経, ヴァルキューレ, アリウス"),
        listOf("台服", "鑽石, 神秘, 花, 燦爛, 閃亮, 顏色, 很富足, 銀, 金, 白金, 銅, 武器零件, 百鬼夜行, 赤冬, 三一, 格黑娜, 阿拜多斯, 千年, 山海經, 女武神, 奧利斯"),
        listOf("韩服", "다이아몬드, 신비, 꽃, 찬란함, 반짝임, 색깔, 풍족함, 은, 금, 백금, 구리, 무기 부품, 백귀야행, 붉은겨울, 트리니티, 게헨나, 아비도스, 밀레니엄, 산해경, 발키리, 아리우스")
    )
    private val headers = listOf("国服", "国际服EN", "日服", "台服", "韩服")
    private val orderHeaders = listOf("区服", "默认顺序")
    fun wideTables(phase: Int): List<HelpWideTable> {
        val map = listOf(mapping1, mapping2, mapping3)[phase - 1]
        val orders = listOf(orders1, orders2, orders3)[phase - 1]
        return listOf(
            HelpWideTable("第 $phase 阶段节点名称对照", headers, map, collapsed = true),
            HelpWideTable("第 $phase 阶段各区服默认顺序", orderHeaders, orders, collapsed = true),
        )
    }
}
