<div align="center">

<img src="docs/images/icon.png" width="112" alt="BAAS AIO 图标">

# BAAS AIO

在安卓手机上直接运行 [BAAS](https://github.com/pur1fying/blue_archive_auto_script) 的蔚蓝档案自动化应用，授权后无需电脑常驻连接。

[![最新版本](https://img.shields.io/github/v/release/sk1lyn3/BAAS-AIO?label=%E6%9C%80%E6%96%B0%E7%89%88%E6%9C%AC&color=0573B8)](https://github.com/sk1lyn3/BAAS-AIO/releases/latest)
[![Android 10+](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)](#系统要求)
[![许可证 GPL-3.0](https://img.shields.io/github/license/sk1lyn3/BAAS-AIO?label=%E8%AE%B8%E5%8F%AF%E8%AF%81&color=blue)](LICENSE)

<img src="docs/images/banner-light.webp#gh-light-mode-only" width="820" alt="主页、托管、调度和配置">
<img src="docs/images/banner-dark.webp#gh-dark-mode-only" width="820" alt="主页、托管、调度和配置">

</div>

## 简介

BAAS AIO 把 BAAS 的 Python 工作流、识图资源和 OCR 一起打包进一个安卓应用。游戏运行在应用创建的虚拟屏幕上，通过 Shizuku 授权完成点击、滑动和截图。

- 支持官服、B服、日服、国际服
- 日常任务、调度和设置沿用 BAAS 原有的配置格式
- 托管模式按计划自动唤起任务，可同时托管多个配置
- BAAS core 可以在应用内在线更新，不用重新安装

## 功能一览

- **主页**：游戏预览、启动和关闭游戏、开始或停止任务都在这里。下方列出当前配置已启用的任务和下次执行时间。
- **托管**：选择要托管的配置并开启托管，应用会按任务时间自动唤起执行。某个配置执行失败后会暂缓重试，多次失败后挂起，可以手动重试。下方的失败历史可查看错误日志和可用的现场截图。“托管前唤醒解锁”默认关闭，可以按设备需要开启。
- **调度**：查看和编辑每个任务的启用状态、执行顺序和下次执行时间，支持全部启用、立即刷新和一键推迟。支持从任务的执行参数入口跳到对应的设置分组。
- **配置**：制造、咖啡厅、竞技场、商店、日程、推图、编队、总力战等设置按分组展开编辑，带有输入校验和说明。配置可以新建、复制、导入和导出。
- **日志**：实时显示 BAAS 的运行日志，可以按级别筛选、搜索，也可以全屏查看。
- **设置**：除了各项任务设置，这里还有外观、Shizuku 授权状态、应用与电池设置、更新和关于。外观可以跟随系统，也可以固定为浅色或深色。
- **core 更新**：在“设置 → 更新”里检查新的 BAAS core，下载后重启即可使用，并显示这次的更新说明。新版本启动检查没通过时会自动回退到更新前的版本。

## 截图

<p align="center">
<img src="docs/images/real-home.jpg" width="24%" alt="主页">
<img src="docs/images/real-schedule.jpg" width="24%" alt="调度">
<img src="docs/images/real-update.jpg" width="24%" alt="发现新版本">
<img src="docs/images/real-home-dark.jpg" width="24%" alt="主页，深色">
</p>

<details>
<summary>更多截图</summary>

<p align="center">
<img src="docs/images/real-schedule-dark.jpg" width="24%" alt="调度，深色">
<img src="docs/images/real-update-dark.jpg" width="24%" alt="发现新版本，深色">
<img src="docs/images/light-logs.jpg" width="24%" alt="日志">
<img src="docs/images/light-settings.jpg" width="24%" alt="设置">
<img src="docs/images/light-update-offer.jpg" width="24%" alt="core 更新">
<img src="docs/images/light-update.jpg" width="24%" alt="发现新版本">
</p>

<p align="center">
<img src="docs/images/dark-home.jpg" width="24%" alt="主页">
<img src="docs/images/dark-managed.jpg" width="24%" alt="托管">
<img src="docs/images/dark-schedule.jpg" width="24%" alt="调度">
<img src="docs/images/dark-config.jpg" width="24%" alt="配置">
<img src="docs/images/dark-logs.jpg" width="24%" alt="日志">
<img src="docs/images/dark-settings.jpg" width="24%" alt="设置">
<img src="docs/images/dark-update-offer.jpg" width="24%" alt="core 更新">
<img src="docs/images/dark-update.jpg" width="24%" alt="发现新版本">
</p>

</details>

## 横屏

横屏时游戏画面和任务面板左右并排，也可以全屏查看游戏。

<p align="center">
<img src="docs/images/land-home.jpg" width="49%" alt="横屏主页">
<img src="docs/images/land-managed.jpg" width="49%" alt="横屏主页，任务执行中">
<img src="docs/images/land-managed-dark.jpg" width="49%" alt="横屏托管，深色">
<img src="docs/images/land-dark.jpg" width="49%" alt="横屏设置，深色">
</p>

<p align="center">
<img src="docs/images/light-land-home.jpg" width="49%" alt="横屏主页，竞技场商店购买执行中">
<img src="docs/images/dark-land-schedule.jpg" width="49%" alt="横屏调度，深色">
</p>

<details>
<summary>更多横屏截图</summary>

<p align="center">
<img src="docs/images/dark-land-home.jpg" width="49%" alt="横屏主页，深色">
<img src="docs/images/light-land-schedule.jpg" width="49%" alt="横屏调度">
<img src="docs/images/light-land-settings.jpg" width="49%" alt="横屏设置">
<img src="docs/images/dark-land-settings.jpg" width="49%" alt="横屏设置，深色">
</p>

</details>

<p align="center">
<img src="docs/images/land-game.jpg" width="98.5%" alt="全屏游戏时的悬浮状态胶囊">
</p>

## 下载

在 [Releases](https://github.com/sk1lyn3/BAAS-AIO/releases/latest) 下载最新版本，选择文件名带 `release` 的安装包即可。

带 `debug` 的是调试版，图标和主题色与正式版不同，用于排查问题。

## 系统要求

- Android 10 或更高版本
- 64 位 ARM 设备，安装包只包含 arm64-v8a
- 已安装并启动 [Shizuku](https://github.com/RikkaApps/Shizuku)，并允许 BAAS AIO 使用
- 手机上已安装对应区服的蔚蓝档案
- 使用托管模式时，需要允许“闹钟和提醒”权限

## 快速开始

1. 安装 Shizuku 并启动服务。Android 10 无 Root 时通常需要电脑 ADB 启动；Android 11 及以上可使用无线调试，方法见 [Shizuku 指南](https://shizuku.rikka.app/guide/setup/)。启动后可以断开电脑，手机重启后通常需要重新启动服务。
2. 安装 BAAS AIO 并打开，在“设置 → 应用与权限”里请求 Shizuku 授权。
3. 点右上角的“管理”新建配置，选择游戏区服。
4. 在“设置”里按需要调整各项任务，在“调度”里选择要启用的任务。
5. 回到“主页”点“开始”，就会在虚拟屏幕上启动游戏并执行任务。
6. 想让任务按时自动运行，就到“托管”里选择配置并开启托管。

## core 更新

BAAS AIO 内置了一个 BAAS core，也可以单独在线更新 core，不用重新安装应用。

- 打开“设置 → 更新”，可以看到当前的 core 版本，比如 `core-348db4c`，以及它是内置版本还是已下载的版本。
- 点“检查更新”，有新版本时会显示版本号、大小和更新说明，确认后下载并安装，重启应用后生效。
- 新版本第一次启动时会先做一次检查。检查没通过，或者连续两次没能完成检查，就会自动回退到更新前的版本。
- 想回到应用自带的版本，点“恢复内置版本”，已下载的版本会被删除。
- 如果新的 core 需要更新应用才能使用，会提示你前往下载新版本的应用。
- 访问 GitHub 不方便时，可以在“下载镜像”里填写镜像地址前缀。更新信息和更新包都带有签名，镜像无法篡改内容。

## 常见问题

**提示 Shizuku 未启动或未授权怎么办？**
先在 Shizuku 里启动服务，再回到 BAAS AIO 请求授权。如果之前拒绝过，需要在 Shizuku 的应用管理里允许 BAAS AIO。

**托管没有按时运行？**
确认已经允许“闹钟和提醒”权限，并在“设置 → 应用与电池设置”里放开后台和电池限制。

**更新 core 后提示已回退更新前版本？**
新版本没有通过启动检查，应用已经换回原来的版本，任务照常运行。可以等下一个版本，也欢迎把日志反馈给我们。

**手机锁屏后还能运行吗？**
游戏运行在虚拟屏幕上，物理屏幕锁定不会直接中止托管。如果你的设备需要亮屏解锁才能正常运行，可以在“设置 → 设备配置”里开启“托管前唤醒解锁”。

## 开发文档

开发和维护相关的说明在 [docs](docs/README.md) 目录：

- [构建](docs/building.md)
- [架构](docs/architecture.md)
- [core 在线更新](docs/core-updates.md)
- [配置目录](docs/config-catalog.md)
- [上游 core 更新后的维护](docs/maintenance.md)
- [发布流程](docs/releasing.md)

## 致谢

- [BAAS](https://github.com/pur1fying/blue_archive_auto_script)：Python 工作流与识图资源，以及安卓 OCR 预编译库
- [Shizuku](https://github.com/RikkaApps/Shizuku)：设备控制权限
- [Chaquopy](https://github.com/chaquo/chaquopy)：内嵌 Python 运行环境
- [MAA-Meow](https://github.com/Aliothmoon/MAA-Meow)：界面与交互设计参考
- [Jetpack Compose](https://developer.android.com/jetpack/compose)：界面框架
- [OpenCV](https://opencv.org/)、[NumPy](https://numpy.org/)、[Pillow](https://python-pillow.org/)：BAAS 依赖的图像处理库

## 许可证

本项目基于 [GNU General Public License v3.0](LICENSE) 发布。
