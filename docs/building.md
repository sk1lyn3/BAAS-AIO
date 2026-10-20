# 构建

## 仓库里有什么

| 路径 | 内容 |
| --- | --- |
| `app/` | 应用本体，Kotlin 和 Compose 写的界面和服务。`src/main/python/` 下是在 Chaquopy 里运行的 Python 胶水代码 |
| `companion-runtime/` | 在 Shizuku 进程里运行的 Companion，负责虚拟屏、输入和截图 |
| `baas-core/` | 子模块，指向 BAAS 的 fork [sk1lyn3/blue_archive_auto_script](https://github.com/sk1lyn3/blue_archive_auto_script) |
| `tools/` | 构建和打包脚本 |
| `.github/workflows/` | 三个手动运行的工作流，用法见 [releasing.md](releasing.md) |
| `.github/release-notes/` | APK 和 core 包的更新说明 |

## 准备环境

需要这些：

- JDK 17
- Android SDK：`platforms;android-35`、`build-tools;35.0.0`、`ndk;27.2.12479018`、`cmake;3.22.1`
- Python 3.10，Chaquopy 和 `tools/` 下的脚本都用它
- `git` 和 `openssl`

应用的 `compileSdk` 和 `targetSdk` 是 35，只打包 `arm64-v8a`。

`minSdk` 写的是 28，但当前虚拟屏输入方案依赖 Android 10 才提供的接口，因此只支持 Android 10 及以上。Android 10 的核心运行链路已通过测试，Android 9 不支持当前方案。

Python 依赖由 Chaquopy 安装，版本写在 `app/build.gradle.kts` 里：`opencv-python==4.5.1.48`、`numpy==1.26.2`、`rich==14.2.0`、`requests==2.32.5`、`pillow==11.0.0`、`psutil==7.1.3`。

## 拉取代码

```bash
git clone https://github.com/sk1lyn3/BAAS-AIO.git
cd BAAS-AIO
git submodule update --init baas-core
```

子模块是浅克隆。没有初始化子模块就构建的话，`build_apk.sh` 找不到 `baas-core/core/Baas_thread.py`，会提示你先初始化。

## 准备 OCR 预编译库

OCR 用的是 BAAS 作者提供的安卓预编译库 [pur1fy/baas_-cpp_prebuild](https://gitee.com/pur1fy/baas_-cpp_prebuild)，固定在某个提交上。这个提交号就是 `.github/workflows/build-apk.yml` 里 `ocr_revision` 的默认值。

```bash
git init ocr-prebuild
git -C ocr-prebuild remote add origin https://gitee.com/pur1fy/baas_-cpp_prebuild.git
git -C ocr-prebuild fetch --depth=1 origin <ocr_revision>
git -C ocr-prebuild update-ref refs/remotes/origin/android-arm64-v8a "$(git -C ocr-prebuild rev-parse FETCH_HEAD)"
export OCR_PREBUILD=$PWD/ocr-prebuild
```

构建脚本读的是这个仓库里的 `origin/android-arm64-v8a`，所以最后一步的 `update-ref` 不能省。

## 构建 APK

本地和工作流用的是同一个脚本：

```bash
export ANDROID_HOME=~/Android/Sdk
export OCR_PREBUILD=/path/to/ocr-prebuild
tools/build_apk.sh [BAAS 源码目录] [debug|release] [BAAS 提交]
```

三个参数都可以省略，默认是 `baas-core`、`release`、`HEAD`。比如构建一个调试版：

```bash
tools/build_apk.sh baas-core debug
```

脚本做完会检查 APK 里的 core 确实来自指定的提交，并逐个核对文件哈希。

产物默认放在 `artifacts/apk/`，文件名是 `baas-aio-<分支>-<variant>-<versionName>-<BAAS sha7>.apk`。旁边的 `build-info.json` 记下了本仓库提交、BAAS 提交、OCR 提交、签名证书和 APK 的哈希。

可以用环境变量调整的地方：

| 变量 | 作用 |
| --- | --- |
| `APK_OUTPUT_DIR` | 产物目录 |
| `OCR_NDK` | 编译 OCR 桥接库用的 NDK，默认 SDK 里的 27.2.12479018 |
| `BUILD_PYTHON` | Chaquopy 构建所用的 Python，默认 `python3`；`tools/` 脚本仍直接调用 `python3` |

## APK 签名

debug 版默认用 Gradle 的调试密钥。release 版必须签名，脚本按顺序找密钥：

1. 环境变量 `APK_KEYSTORE`、`APK_KEYSTORE_PASSWORD`、`APK_KEY_ALIAS`、`APK_KEY_PASSWORD`。`APK_KEY_PASSWORD` 不填就当作和密钥库密码一样。
2. 本地文件 `~/.local/share/baas-release-signing/baas-aio/signing.env`，里面写同样的变量。设置了 `XDG_DATA_HOME` 时放在它下面，也可以用 `AIO_SIGNING_ENV` 直接指定路径。

debug 版也认这些变量，提供了就用你的密钥签。

## debug 版和 release 版

- debug 版多了开发诊断入口和一些调试用的 Activity，代码在 `app/src/debug/`，还会打包自检用的截图和 OCR 参考数据。
- debug 版用单独的图标和粉色主题，一眼就能和正式版分开。
- 两者包名都是 `moe.baas.aio`，一台手机上只能装一个。

## 构建时自动生成的文件

Gradle 的 `preBuild` 会先跑两个准备任务，结果放在 `app/build/generated/`。平时不用管，构建报错时知道去哪看就行。

`prepareBaasCore` 调用 `tools/prepare_baas_core.py`：

- 把 core 原样打成 `baas-source.zip`，附带 `source-manifest.json`，里面有提交号、提交时间、`coreApi` 和每个文件的 sha256。
- 从 `core/config/default_config.py` 生成默认配置、静态数据和活动信息等 JSON。
- 检查胶水代码要用的 `Baas_thread` 和 `Scheduler` 方法还在不在。上游改了方法名，构建会在这里失败。

`prepareOcrRuntime` 调用 `tools/prepare_ocr_runtime.py`：

- 从 OCR 预编译库取出库文件和模型，模型打成 `ocr-resources.zip`。
- 用 NDK 编译 `app/src/main/cpp/ocr_bridge.cpp`，得到 `libbaas_aio_ocr.so`。

## Gradle 属性

直接用 Gradle 构建时，可以用 `-P` 传这些属性。注意 Gradle 不读环境变量 `OCR_PREBUILD`，要用 `-PocrPrebuild` 指定 OCR 预编译库：

| 属性 | 作用 |
| --- | --- |
| `baasSource` | core 源码目录，默认 `baas-core` |
| `baasRevision` | 要打包的提交。不填就打包工作区里当前的文件 |
| `ocrPrebuild` | OCR 预编译库目录 |
| `ocrNdk` | 编译 OCR 桥接库用的 NDK |
| `buildPython` | 构建用的 Python |
| `applicationIdSuffix` | 给包名加后缀，可以和正式版同时安装 |
| `appLabel` | 改应用名 |
| `coreChannelUrl` | 改 core 包的更新通道地址 |

后三个用来做测试构建，用法见 [core-updates.md](core-updates.md#在本地测试更新)。

## 截图测试

`app/src/test/` 下有用 Robolectric 和 Roborazzi 写的界面测试。截图默认输出到 `app/build/test-shots/`，可以用 `-DshotDir` 换目录：

```bash
./gradlew :app:testReleaseUnitTest --tests moe.baas.aio.ShotTest -DshotDir=/tmp/shots
```

跑 release 变体，截出来的是正式版的蓝色主题。
