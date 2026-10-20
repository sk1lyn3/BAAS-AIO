# 上游有改动时怎么维护

BAAS 的上游仓库是 [pur1fying/blue_archive_auto_script](https://github.com/pur1fying/blue_archive_auto_script)。我们用的是自己的 fork [sk1lyn3/blue_archive_auto_script](https://github.com/sk1lyn3/blue_archive_auto_script)，子模块 `baas-core` 和 core 包都从 fork 拉代码。

大多数时候，上游的改动只需要发一个 core 包。只有动到应用本身的代码，才要发 APK。

## 一般流程

### 1. 把上游合并进 fork

使用 merge 合并上游，保留已发布 core 对应的提交，避免 rebase 后改写发布历史。

下例约定 `origin` 指向自己的 fork，`upstream` 指向上游。先用 `git remote -v` 核对；如果命名不同，请替换命令中的远端名。

```bash
cd blue_archive_auto_script
git remote -v
git remote add upstream https://github.com/pur1fying/blue_archive_auto_script.git   # 第一次才需要
git fetch upstream
git merge upstream/master
git push origin master
```

fork 的历史里都是这样的合并提交，比如 `348db4cb Merge upstream Global activity updates`。

### 2. 看看改了什么

```bash
git diff <上次发布的提交>..<新提交> --stat
```

重点看这几处：

- `core/config/default_config.py`：设置项、任务列表和活动数据都在这里；
- `core/Baas_thread.py` 和 `core/scheduler.py`：胶水代码直接调用它们的方法；
- 新增的模块，看有没有用到新的 Python 依赖。

### 3. 对照下表决定发什么

见下一节。

### 4. 发布

按 [releasing.md](releasing.md) 发 core 包或 APK。

### 5. 需要时更新子模块

子模块决定了下一版 APK 的内置版本。只发 core 包时可以不急着更新，但下次发 APK 前一定要更新：

```bash
git -C baas-core fetch origin <新提交>
git -C baas-core checkout <新提交>
git add baas-core
git commit -m "Update core"
```

## 发 core 包还是发 APK

| 上游改了什么 | 发什么 |
| --- | --- |
| 任务逻辑或图片 | core 包 |
| 静态数据，比如当前活动 | core 包 |
| 新增设置项 | core 包，可以顺便改配置目录 |
| 设置项的选项变了 | core 包，同时改配置目录 |
| 设置项的类型变了 | 一般是 core 包。需要新的编辑方式时发 APK |
| 新增任务 | core 包，可以顺便改配置目录 |
| 设置项的说明要改 | APK |
| 新的 Python 依赖，或者胶水代码要用的类、方法变了 | APK，旧 APK 跑不了新 core 时还要把 `coreApi` 加一 |

下面逐条说明。

**任务逻辑或图片。** 比如修识别、调流程、换图。直接发 core 包，本仓库不用改。

**静态数据。** 比如 `default_config.py` 里的 `current_game_activity` 换成了新活动。直接发 core 包，`activities-info.json` 和 `static-config.json` 打包时会重新生成。

**新增设置项。** `DEFAULT_CONFIG` 里多了一个键。什么都不做也能用，它会出现在“其他参数”里，显示键名。想让它有个好名字、放进合适的分组，就在 `config-catalog.json` 里加上这个字段，提交后用包含这次修改的 `aio_ref` 发 core 包。

**选项变了。** 新增或删掉了可选值。改配置目录里这个字段的 `options`。打包时会检查默认值在不在选项里。用户已经存的旧值会保留。

**类型变了。** 比如字符串变成了列表。改配置目录里的字段，让它和新的默认值类型一致。如果现有的编辑方式都不合适，要加新的 `kind`，那就得改 `ConfigForm.kt` 并发 APK。

**新增任务。** `EVENT_DEFAULT_CONFIG` 里多了一个任务。它会自动合并进用户已有的配置，规则见 [config-catalog.md](config-catalog.md#新任务怎么合并进-eventjson)。最好让上游默认 `enabled: false`。在配置目录的 `tasks` 里给它写上 `group` 和 `tag`，调度页才会有“执行参数”按钮和标签。

**说明要改。** 设置项旁边的说明写在 `HelpCatalog.kt` 里，编译进 APK，只能发 APK 更新。

**新依赖或接口变化。** 比如 `Baas_thread`、`Scheduler` 的方法改了名。要改胶水代码，或者在 `app/build.gradle.kts` 里加依赖，然后发 APK。如果旧 APK 跑不了新 core，就把 `core_loader.py` 里的 `CORE_API` 加一，发布顺序见 [releasing.md](releasing.md#coreapi-加一时)。

有一部分问题会被自动发现：

- 构建 APK 时，`prepare_baas_core.py` 检查胶水代码要用的方法还在不在；
- 打 core 包时，`prepare_core_bundle.py` 检查配置目录和新的默认值对不对得上；
- 用户安装 core 包时和新 core 第一次启动时，还会再查一遍，见 [core-updates.md](core-updates.md)。

## 几个实际的例子

### 国际服换活动：只发 core 包

上游提交 `6d981cfd update: Global activity` 把国际服的 `current_game_activity` 从 `AFlowerBloomsAmongtheHundred` 改成了 `TheCalmAftertheStorm`。同时加了活动脚本 `module/activities/TheCalmAftertheStorm.py`、关卡数据和三套入口图片。

这些都是逻辑、图片和静态数据。合并进 fork 成为 `348db4cb` 后，直接发了 `core-348db4c`，本仓库一行没改。

后来 fork 上的 `36692fc9 fix: correct Global activity image paths` 修了图片路径，同样只发了 core 包 `core-36692fc`。

### 通用活动扫荡入口：新增设置项

fork 的提交 `d50599e1 Add optional generic entry for activity sweeping` 在 `DEFAULT_CONFIG` 里加了 `activity_sweep_use_generic_entry`，默认是 `false`。

不改配置目录也能用，它会以键名出现在“其他参数”里。本仓库的提交 `a776c5f` 把子模块更新到 `d50599e`，又在配置目录的“扫荡次数”分组里加了这个字段，显示成“活动扫荡使用通用入口”。

### 悬赏通缉次数：逻辑在 core，说明在 APK

上游提交 `a8b1bf15 Fix: pad missing bounty sweep counts with zeros` 改了 `module/rewarded_task.py`，没填的区域按 0 次处理。这个修复随 core 包发布就行。

但“悬赏通缉次数”旁边的说明写在 `HelpCatalog.kt` 里。本仓库的提交 `a618b0a` 补上了“0 = 跳过该区域，未填写的区域也不执行”这些规则，这部分要等下一版 APK 才能让用户看到。
