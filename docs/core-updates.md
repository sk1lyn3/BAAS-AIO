# core 在线更新

应用可以在线下载 core 包，换上新的 core，不用重装 APK。这篇讲它从发布到生效的整个过程。发布操作本身见 [releasing.md](releasing.md)。

相关代码：

| 文件 | 作用 |
| --- | --- |
| `app/src/main/java/moe/baas/aio/CoreBundle.kt` | 验签、安装、状态文件、清理 |
| `app/src/main/java/moe/baas/aio/CoreUpdater.kt` | 联网、镜像、下载、重启 |
| `app/src/main/java/moe/baas/aio/CoreUpdateDialog.kt` | “设置 → 更新”里的弹窗 |
| `app/src/main/python/core_loader.py` | 每个进程选 core，执行自检和回退 |
| `app/src/main/python/core_check.py` | 新 core 的启动自检 |
| `tools/prepare_core_bundle.py` | 打 core 包、更新通道索引、签名 |
| `.github/workflows/core-package.yml` | 发布 core 包的工作流 |

## 更新通道

应用检查更新时请求的地址是：

```
https://github.com/sk1lyn3/BAAS-AIO/releases/download/core-channel/stable.json
```

这个地址在构建时写进 `BuildConfig.CORE_CHANNEL_URL`，可以用 Gradle 属性 `coreChannelUrl` 换掉。

名为 `core-channel` 的 Release 里只放两个文件：

- `stable.json`：通道索引，列出目前可用的 core 包。
- `stable.json.sig`：索引的签名。

索引长这样：

```json
{
  "format": 1,
  "packages": [
    {
      "coreApi": 1,
      "commit": "<完整提交号>",
      "commitTime": "<提交时间，git %cI 格式>",
      "url": "https://github.com/sk1lyn3/BAAS-AIO/releases/download/core-<sha7>/core-<sha12>.zip",
      "zipSha256": "...",
      "size": 0,
      "notes": "更新说明",
      "minVersionCode": null,
      "maxVersionCode": null
    }
  ]
}
```

每个 core 包单独发在一个叫 `core-<sha7>` 的 Release 里，发了就不再改动。

`coreApi`、`minVersionCode`、`maxVersionCode` 都相同的包，索引里只留最新的一个。条件不同的包会同时留着，这样旧版 APK 也能找到适合自己的包。

## core 包里有什么

`core-<sha12>.zip` 里有：

- `source/`：core 源码和资源，和 APK 里的 `baas-source.zip` 内容一样，也带 `source-manifest.json`。
- `data/`：从 core 生成的默认配置、静态数据和活动信息，还有打包时本仓库里的 `config-catalog.json`。
- `bundle-manifest.json`：包的清单，写着 `format`、`coreApi`、core 的提交号和提交时间、`minVersionCode`、`maxVersionCode`，以及每个文件的 sha256。
- `bundle-manifest.json.sig`：清单的签名。

## 签名和密钥

索引和包内清单都用 ECDSA P-256 签名，算法是 `SHA256withECDSA`。

- **私钥** 存在仓库的 secret `CORE_BUNDLE_SIGNING_KEY` 里，是 PEM 格式。
- **公钥** 写在 `app/src/main/res/raw/core_bundle_keys.json`，编译进 APK。可以放好几把，任意一把验过就算通过。现在只有一把，叫 `baas-core-2026`。

因为两样东西都签了名，镜像只能原样转发，改了内容应用就不认。

### 生成一对新密钥

```bash
openssl ecparam -name prime256v1 -genkey -noout -out core-signing.pem
openssl ec -in core-signing.pem -pubout -outform DER | base64 -w0
```

第二条命令输出的就是公钥，填进 `core_bundle_keys.json` 新加的一项：

```json
{
  "id": "baas-core-2027",
  "algorithm": "SHA256withECDSA",
  "publicKeyDerBase64": "<上面的输出>"
}
```

`core-signing.pem` 的全部内容，包括首尾两行，粘贴到 secret `CORE_BUNDLE_SIGNING_KEY`。私钥不要提交进仓库。

### 换密钥

旧 APK 只认旧公钥，所以要分两步：

1. 把新公钥加进 `core_bundle_keys.json`，旧公钥先别删，发一版 APK。
2. 等大多数用户更新之后，再把 secret 换成新私钥。

## 怎么算新版本

- 只比较 **提交时间**，按 UTC 时刻比。提交号不同、并且提交时间更晚，才算新版本。
- 新版本要同时比正在用的 core 和 APK 的内置版本都新。
- 界面上显示成 `core-<sha7> · <提交日期>`。

## coreApi 和 versionCode

`coreApi` 表示胶水代码和 core 之间的接口版本。它定义在 `app/src/main/python/core_loader.py` 的 `CORE_API`，Gradle 和 `prepare_baas_core.py` 都从这里读，现在是 `1`。

应用只装 `coreApi` 和自己完全相同的包。胶水代码对 core 的用法，或者生成数据的格式，出现了旧版本不兼容的变化，才需要把它加一。加一之后的发布顺序见 [releasing.md](releasing.md#coreapi-加一时)。

检查更新时，索引里的包分成两类：

- **可以安装**：`coreApi` 相同，当前 `versionCode` 也在 `minVersionCode` 到 `maxVersionCode` 之间。
- **需要先更新应用**：当前 `versionCode` 不超过 `maxVersionCode`，并且满足下面任意一条：`coreApi` 更高；`coreApi` 相同但当前 `versionCode` 低于 `minVersionCode`。

两类各取最新的一个：

- 有可以安装的包，就提供下载。如果“需要先更新应用”的那个更新，弹窗会多一行“更新应用可使用更新的 core”。
- 没有可以安装的包，只有需要更新应用的，就提示更新应用，并打开 Releases 页面。

## 下载和安装

1. 下载 `stable.json` 和 `stable.json.sig`，最多 1 MB，验证签名。
2. 把 zip 下载到 `files/core/downloads/` 下的临时文件夹。每次下载用新的文件夹，取消了也不留文件。
3. 核对 zip 的 sha256 和大小，再验证包内清单的签名。然后检查：`format` 是 1，`coreApi` 一致，`versionCode` 在范围内，清单里的提交和索引一致，并且比内置版本新。
4. 解压到 `files/core/staging/`，逐个核对哈希。清单里没有的文件、指到外面的路径都不允许，解压后总共不能超过 256 MB。
5. 用 `CoreBundle.checkData` 检查 `data/`：配置目录和默认值对不对得上，商店数据在不在，默认任务列表格式对不对。
6. 改名为 `files/core/ext/<sha12>/`，在状态文件里标记为 `pending`，也就是等待启用。

任务运行、开发自检或托管正在执行期间，不能安装或切换 core。托管仅处于等待下次任务的状态时，可以更新。

## 状态文件

状态存在 `files/core/state.json`。Kotlin 和 Python 写它时，都是先写临时文件再改名，避免留下只写了一半的状态文件。

| 字段 | 含义 |
| --- | --- |
| `active` | 正在用的包。`bundled` 表示内置版本 |
| `pending` | 等待启用的新包 |
| `bootAttempts` | 新包已经试着启动了几次 |
| `lastError` | 最近一次失败的原因，显示给用户看 |
| `lastErrorDetail` | 失败的详细信息，给开发排查用 |

## 重启、自检和回退

新包要在新进程里才能生效。装完后弹窗会提供“立即重启”，由 `RestartActivity` 在 `:restart` 进程里完成，不需要额外权限。

新进程第一次启动 Python 时，`core_loader.py` 这样处理 `pending`：

1. 先把 `bootAttempts` 加一并写进文件，再导入新 core。这样就算进程崩了，也留有记录。
2. 合并 `event.json` 的迁移，见 [config-catalog.md](config-catalog.md#新任务怎么合并进-eventjson)。
3. 运行 `core_check.py`：检查胶水代码要用的类和方法，检查 `func_dict` 里的任务，再用新 core 读每个配置的 `event.json`，确认能排出任务队列。
4. 通过后，`pending` 变成 `active`，之前那个包被删掉。

出问题时会放弃新包，换回 **更新前在用的版本**。它可能是内置版本，也可能是之前下载的包。

| 情况 | 用户看到的提示 |
| --- | --- |
| 自检报错 | 新版本无法在当前应用上运行，已回退更新前版本 |
| 已经试了 2 次还没启动完 | 新版本未能完成启动检查，已回退更新前版本 |

应用每次启动还会先核对一遍已下载的包。下面几种情况会改回内置版本，并删掉这些包：

- 包的 `coreApi` 和应用不一样；
- 当前 `versionCode` 不在包要求的范围内；
- 包已经不比内置版本新，比如用户装了一个内置更新 core 的新 APK。

## 恢复内置版本

这是用户唯一能手动操作的回退。它会把 `active` 设为 `bundled`，清空 `pending`，删掉所有下载的包，然后重启应用。

## 下载镜像

在“下载镜像”里填一个前缀，必须以 `http://` 或 `https://` 开头，留空就是直连。

用了镜像后，请求地址是 `前缀/原地址`。比如前缀填 `https://mirror.example`，实际请求的是 `https://mirror.example/https://github.com/...`。索引和包都会走镜像。

## 在本地测试更新

想在不碰正式通道的情况下走一遍更新流程，可以自己搭一个测试通道：

1. 生成一对测试密钥，方法见上面。把测试公钥临时加进 `core_bundle_keys.json`，这个改动不要提交。
2. 打一个测试用的 core 包：

   ```bash
   python3 tools/prepare_core_bundle.py --baas baas-core --revision <core 提交> \
     --notes "测试" --key core-signing.pem --output out \
     --url-base https://example.com/test-core
   ```

   `out/` 里会生成 zip、`stable.json` 和 `stable.json.sig`。`--url-base` 填你放 zip 的地址前缀。
3. 把这三个文件放到能下载的地方，比如自己仓库的一个 Release。
4. 构建一个测试版 APK，换掉包名后缀、应用名和通道地址，这样可以和正式版同时安装：

   ```bash
   ./gradlew :app:assembleDebug -PocrPrebuild="$OCR_PREBUILD" -PapplicationIdSuffix=.test -PappLabel="BAAS 测试" \
     -PcoreChannelUrl=https://example.com/test-core/stable.json
   ```

测试包的提交时间要比 APK 的内置版本晚，否则应用不会认为它是新版本。
