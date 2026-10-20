# 配置目录

配置页上显示哪些设置、叫什么名字、有哪些选项，都由 `app/src/main/assets/config-catalog.json` 决定，文档里叫它 **配置目录**。

设置项的名称和选项沿用 BAAS WebUI。保存的值保持 BAAS `config.json` 原来的类型，桌面版 BAAS 也能直接读。

配置目录会跟着 core 包一起发布，打包时用的是 `aio_ref` 指向的那个版本里的文件。所以给设置项改名、换分组、加选项，一般发个 core 包就够了，不用发 APK。

相关代码：

| 文件 | 作用 |
| --- | --- |
| `ConfigCatalog.kt` | 读取配置目录，算出“其他参数”，校验输入 |
| `ConfigForm.kt` | 把设置项画出来 |
| `SettingsPanel.kt` | 配置页的分组、折叠和顺序 |
| `ProfileStore.kt` | 保存配置，校验任务列表 |
| `HelpCatalog.kt` | 设置项旁边 ⓘ 里的说明 |
| `tools/prepare_core_bundle.py` | 打包时检查配置目录 |
| `app/src/main/python/profile_migration.py` | 把新 core 增加的任务合并进已有配置 |

前五个文件都在 `app/src/main/java/moe/baas/aio/` 下。

## 文件结构

顶层有四个字段：

| 字段 | 内容 |
| --- | --- |
| `groups` | 配置页上的各个分组 |
| `hidden` | 不在界面上显示的键 |
| `other` | “其他参数”里的显示名称 |
| `tasks` | 每个任务在调度页上的参数入口和标签 |

### groups

按顺序列出分组。每组有 `title` 和 `fields`，每个字段可以写这些：

| 字段 | 含义 |
| --- | --- |
| `key` | `config.json` 里的键 |
| `label` | 显示的名称 |
| `options` | 选项列表，每项是 `{ "value", "label" }`。留空表示自由输入 |
| `min`、`max` | 数值范围，可以写数字或数字字符串。`null` 表示不限 |
| `numeric` | 按数字输入 |
| `boolean` | 显示成开关 |
| `kind` | 需要特殊编辑方式时才写，见下表 |
| `goods` | `kind` 为 `shop` 时，对应 `static-config.json` 里的哪张商品表 |

目前有三种 `kind`：

| kind | 用在 | 编辑方式 |
| --- | --- | --- |
| `shop` | `CommonShopList`、`TacticalChallengeShopList` | 在商品网格里勾选，每项存 0 或 1 |
| `teams` | `preset_team_attribute`、`side_team_attribute` | 选编队属性。前者每组 5 个，后者每组 4 个 |
| `slots` | `lesson_times`、`drill_difficulty_list`、`drill_fight_formation_list` | 每个位置填一个整数 |

新的 `kind` 要在 `ConfigForm.kt` 里写编辑器，所以需要发 APK。

下面是“扫荡次数”分组里的一个字段：

```json
{
  "key": "activity_sweep_use_generic_entry",
  "label": "活动扫荡使用通用入口",
  "options": [],
  "min": null,
  "max": null,
  "numeric": false,
  "boolean": true
}
```

### hidden

不在界面上显示的键。比如账号资源 `ap`、`pyroxene`，只有桌面端才用的 `adbIP`、`program_address` 和各种推送设置，还有 `new_event_enable_state`。

### other

给“其他参数”里的键起个名字：

```json
"clear_friend_white_list": { "label": "好友白名单", "kind": "list" }
```

`kind` 写 `list` 时，按字符串列表编辑。

### tasks

键是任务名，也就是 `func_name`。每个任务可以写两项：

- `group`：在调度页点“执行参数”时，打开哪个分组。填分组的 `title`，或者 `其他参数`。
- `tag`：调度列表里任务旁边的标签，比如 `咖啡厅`、`商店`。

```json
"activity_sweep": { "group": "扫荡次数", "tag": "活动" }
```

没写在这里的任务，就没有“执行参数”按钮，也没有标签。

## 配置页的排列顺序

1. `groups` 里的各个分组。应用会在两处加东西：“设备配置”最后加上“托管前唤醒解锁”开关，“编队配置”后面插入“活动图”。
2. “其他功能”面板。它由应用的 `ManualTaskPanel` 提供，和配置目录无关。
3. “其他参数”。只有存在没归类的键时才显示。

有些分组里还有应用自己加的控件，比如“关卡推图与剧情”里的推图和剧情选择。

## 其他参数

默认配置 `profile-defaults.json` 里，既不属于任何分组、也不在 `hidden` 里的键，都会自动放进“其他参数”：

- 在 `other` 里起了名字的排在前面，其余的按键名排序。没起名字的直接显示键名。
- 值是对象，或者是数组但不是字符串列表，就只能看不能改。

所以上游加了新设置项，就算配置目录还没跟上，用户也能在“其他参数”里找到并修改。

## 保存时的规则

- 打开配置时，`config.json` 里缺的键会用默认值补上。
- 保存时只写改过的键。写之前会和打开时的值比一下，如果 core 在这期间改了同一个键，就提示“配置已变化，请重新打开后编辑”。core 写入的资源数量和任务状态不受影响。
- 用户以前存的值，就算新的配置目录里已经没有这个选项，也照样保留，不会被强行改掉。
- 改了“游戏区服”，会同步更新 `profile.json`。

## 校验

同一套规则在三个地方检查。

**打包时**，`tools/prepare_core_bundle.py` 的 `check_catalog` 会检查：

- 分组里的每个字段，在这个 core 的默认配置里都有默认值；
- 默认值在字段允许的范围内，`teams` 和 `slots` 逐项检查；
- `shop` 字段的商品表在 `CN`、`Global`、`JP` 三种模式下都存在。

不通过，工作流就会失败，包不会发出去。

**安装 core 包时**，`CoreBundle.checkData` 会再查一遍：

- `ConfigCatalog.check()` 做和打包时一样的检查，并用 `validate` 过一遍每个默认值；
- `ProfileStore.validateEvents` 检查默认任务列表：任务名只能由字母、数字和下划线组成，不能重复，最多 256 个。`enabled`、`priority`、`interval`、`next_tick`、`daily_reset`、`disabled_time_range`、`pre_task`、`post_task` 的格式要对。

**用户编辑时**，`ConfigCatalog.validate` 检查：类型和默认值一致，数值在范围内，该是整数的是整数，选项在列表里。列表最多 256 项，文本最长 10000 字。

## 新任务怎么合并进 event.json

新 core 增加的任务和字段，由 `profile_migration.py` 合并进每个配置的 `event.json`。它在两个时候运行：新 core 自检之前，以及每个进程第一次选定 core 的时候。

- 只加不删。已有的任务只补上缺的字段，原来的值不动。
- 新任务插在它在默认列表里的位置。
- 新任务开不开，看这个配置的 `new_event_enable_state`：`on` 就开，`off` 就关，其他值就按默认配置里的 `enabled`。
- 不是官服或B服的配置，新任务的每日重置时间提前一小时。
- 改之前会把原文件备份到这个配置的文件夹里，也就是 `files/profiles/<id>/.core-backup/event-<时间戳>.json`，只留最近两份。
- 合并失败只记日志，不影响运行。
