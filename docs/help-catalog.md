# 帮助文本

设置项旁边的 ⓘ 点开是一页说明，这些说明都写在 `app/src/main/assets/help-catalog.json` 里，文档里叫它 **帮助文本**。

它和配置目录走同一条路：打 core 包时，`aio_ref` 指向的那个版本里的文件会被放进包里的 `data/help-catalog.json`，跟着 core 包一起签名、下发。所以改一条说明不用发 APK，等下一个 core 包发出去，用户就能看到。

相关代码：

| 文件 | 作用 |
| --- | --- |
| `HelpCatalog.kt` | 读取帮助文本，第一次用到时解析一次 |
| `HelpDialog.kt` | 画出说明页，以及设置项旁边的 ⓘ |
| `CoreBundle.kt` | 安装 core 包时检查帮助文本 |
| `tools/prepare_core_bundle.py` | 打包时检查帮助文本 |

## 文件结构

```json
{
  "format": 1,
  "appKeys": ["managed_wake_before_run"],
  "entries": {
    "hardPriority": {
      "title": "困难图扫荡关卡",
      "summary": "格式同普通图：「区域-关卡-次数」，逗号隔开。",
      "sections": [{ "title": "规则", "lines": ["关卡号 1–3，每关次数最多 3"] }],
      "examples": [{ "code": "10-1-3,12-3-3", "meaning": "10-1、12-3 各三次" }],
      "_src": ["src: gui/components/expand/mainlinePriority.py:23"]
    }
  }
}
```

- `format` 现在是 `1`。应用不认识的格式会被当成读不了。
- `entries` 的键就是设置项的 `key`，和配置目录里的一致。ⓘ 只出现在有说明的设置项旁边。
- `appKeys` 列出不属于 core 配置、由应用自己保存的设置，比如托管前唤醒解锁。其他的键都必须是配置目录里的字段。

每条说明里，只有 `title` 和 `summary` 必填，别的按需要加，在说明页上从上往下依次是：

| 字段 | 显示成 |
| --- | --- |
| `summary` | 标题下面的一段话 |
| `sections` | 带小标题的要点列表，每个有 `title` 和 `lines` |
| `tables` | 两列的表，每个有 `title` 和 `rows`，每行正好两格 |
| `examples` | 等宽字体的示例和它的意思，`code` 加 `meaning` |
| `texts` | 整段原样显示的文字，方便长按复制，`title` 加 `text` |
| `wideTables` | 可以左右滑动的宽表，有 `title`、`headers`、`rows`，`collapsed` 为 `true` 时默认收起 |

`_src` 记下这条说明依据的是 core 里哪段代码，应用不读它。改说明前先看一眼，改完如果依据变了，顺手更新。

## 什么样的设置值得写说明

只给有规则可讲的设置写：填写格式、先后顺序、几个选项之间怎么互相影响、容易踩的坑。一眼就能看懂的设置不用写。

说明页要能单独看懂，不要让用户去翻别的文档。

## 读取和回退

应用读当前生效的 core 里的那份。core 包还在等待启用时，用的仍是之前那一份。

下面几种情况会改用 APK 里自带的那份：

- 用的是内置版本，或者下载的包里没有这个文件，旧的 core 包就没有；
- 文件读不了，或者格式不认识。

解析一次以后就留在内存里，所以换了 core 之后要重启应用才会看到新说明。

## 校验

打包时，`prepare_core_bundle.py` 会检查：

- `format` 是 1；
- 每个键都是配置目录里的字段，或者写在 `appKeys` 里；
- 每条都有 `title` 和 `summary`，各部分的字段齐全且类型正确，两列表格每行正好两格。

有问题就打包失败。安装 core 包时，`CoreBundle.checkData` 会再解析一遍。包里没有这个文件可以装，有但解析不了就拒绝安装。

## 只改了说明怎么发

发过的 core 包不能替换，同一个 core 提交也不能再发一次。所以只改说明的话，把修改提交到 `aio_ref` 要用的分支，等下一次发 core 包时一起带出去。急的话，也可以随下一版 APK 发出去，APK 自带的那份同样会更新。
