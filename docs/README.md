# 开发文档

这里是 BAAS AIO 的开发和维护文档，写给参与开发、打包和发版的人。使用说明见仓库根目录的 [README](../README.md)。

## 先认识两个词

- **core**：BAAS 本身，也就是 BAAS 的 Python 源码、图片资源，以及从它生成的数据。代码来自子模块 `baas-core`。
- **core 包**：把某个提交的 core 打成的 zip。用户在应用里在线下载它，不用重装 APK 就能换上新的 core。

APK 里也自带一份 core，文档里叫它 **内置版本**。

## 文档列表

| 文档 | 内容 |
| --- | --- |
| [building.md](building.md) | 准备环境，在本地构建 APK |
| [architecture.md](architecture.md) | 应用由哪些部分组成，它们怎么配合 |
| [core-updates.md](core-updates.md) | core 包的在线更新：通道、签名、版本判断、自检和回退 |
| [config-catalog.md](config-catalog.md) | 配置页由 `config-catalog.json` 决定，以及它怎么写、怎么校验 |
| [help-catalog.md](help-catalog.md) | 设置项旁边的说明写在哪里，怎么随 core 包发布 |
| [maintenance.md](maintenance.md) | 上游 BAAS 有改动时，判断该发 core 包还是 APK |
| [releasing.md](releasing.md) | 发布 APK 和 core 包的具体步骤 |

第一次接手，建议按 building → architecture → maintenance → releasing 的顺序读，用到时再查另外三篇。

`images/` 里是 README 用的截图，一部分是真机截的，一部分是用 Robolectric 和 Roborazzi 渲染的。
