# 发布

有两种发布：

| | 包含什么 | 用户怎么拿到 |
| --- | --- | --- |
| **APK** | 应用本体和内置版本的 core | 去 Releases 下载，重新安装 |
| **core 包** | core 和配置目录 | 在应用里在线更新 |

能用 core 包解决的就发 core 包。只有改了应用代码才需要发 APK，怎么判断见 [maintenance.md](maintenance.md)。

三个工作流都在 Actions 页面手动运行：

| 工作流 | 文件 | 用途 |
| --- | --- | --- |
| Build AIO APK | `build-apk.yml` | 构建测试用的 APK，不发布 |
| Prepare AIO Release | `release-apk.yml` | 为版本标签构建 APK，建草稿 Release |
| Publish core package | `core-package.yml` | 打包、签名、发布 core 包，并更新通道 |

## 需要配置的 secrets

| 名称 | 内容 |
| --- | --- |
| `APK_KEYSTORE_B64` | APK 签名密钥库，base64 编码 |
| `APK_KEYSTORE_PASSWORD` | 密钥库密码 |
| `APK_KEY_ALIAS` | 密钥别名 |
| `APK_KEY_PASSWORD` | 密钥密码 |
| `CORE_BUNDLE_SIGNING_KEY` | core 包签名私钥，生成方法见 [core-updates.md](core-updates.md#生成一对新密钥) |

把密钥库转成 base64：

```bash
base64 -w0 release.keystore
```

## 更新说明

更新说明都放在 `.github/release-notes/`：

- APK：`v<版本号>.md`，比如 `v1.2.0.md`
- core 包：`core-<sha7>.md`，比如 `core-348db4c.md`

统一分两节，哪节没内容就省掉：

```markdown
## 新的功能

- 支持在线更新 BAAS core 和配套资源，无需重新安装应用。

## 问题修复

- 修复繁中、韩语商店刷新弹窗识别失败的问题。
```

core 包的说明会显示在应用的更新弹窗里。弹窗支持 `#` 标题，`-`、`*`、`+` 列表，`**粗体**`、`` `代码` `` 和 `[文字](https://...)` 链接。

用户跨好几个版本更新时，只会看到最新那个包的说明。所以每份说明都要能单独看懂，别写“同上一版”。

## 构建测试 APK

运行 **Build AIO APK**，产物在这次运行的 Artifacts 里，保留 14 天。

| 输入 | 填写 |
| --- | --- |
| `baas_repository` | 一般留空，用子模块的仓库。想试别的仓库就填 `owner/repo` |
| `baas_ref` | 一般留空，用子模块固定的提交。也可以填提交、标签或分支 |
| `variant` | `release` 或 `debug`，默认 `release` |
| `ocr_revision` | OCR 预编译库的提交，保持默认 |

没有配置 APK 签名 secrets 时，release 构建会失败。

## 发布 APK

1. 改 `app/build.gradle.kts` 里的 `versionName` 和 `versionCode`。`versionName` 必须是三段式。`versionCode` 按约定写成 主×10000 + 次×100 + 修订，比如 `1.2.0` 对应 `10200`。工作流不检查 `versionCode`，自己别写错。
2. 确认子模块 `baas-core` 指向想内置的 core 提交。
3. 写好 `.github/release-notes/v<版本号>.md`。
4. 提交并推送。在这个提交上打标签，推送标签：

   ```bash
   git tag v1.2.0
   git push origin v1.2.0
   ```

5. 运行 **Prepare AIO Release**，`tag` 填 `v1.2.0`。
6. 到 Releases 页面检查草稿，没问题就手动发布，并确认它是 Latest。

工作流会先检查这几件事，有一条不满足就停下：

- 标签的格式是 `vX.Y.Z`，并且正好是 `v` 加上 `versionName`；
- 运行工作流的那个分支上有对应的更新说明；
- 子模块和 OCR 预编译库都固定在完整的 40 位提交号上。

然后它用 APK 签名 secrets 构建 release 和 debug 两个 APK，上传到 Artifacts，再建一个标题为标签名的 **草稿** Release，说明取自更新说明文件。

失败了直接重跑就行。草稿已经存在时，会覆盖里面的 APK 并更新说明。已经正式发布的 Release 不会被覆盖。

应用里的“前往下载”打开的是 Releases 的 Latest 页面，所以 APK 的 Release 一定要标成 Latest。core 包的 Release 都不会标成 Latest，不会抢位置。

## 发布 core 包

1. 把要发布的 core 提交推到 fork，见 [maintenance.md](maintenance.md)。
2. 写好 `.github/release-notes/core-<sha7>.md`，提交到 `aio_ref` 要用的分支，一般是 `main`。这次如果改了 `config-catalog.json` 或 `help-catalog.json`，也要在这个分支上。
3. 运行 **Publish core package**：

   | 输入 | 填写 |
   | --- | --- |
   | `core_repository` | 保持默认 `sk1lyn3/blue_archive_auto_script` |
   | `core_ref` | 分支名，或者 **完整的 40 位提交号**。短提交号拉不下来，工作流会直接失败 |
   | `aio_ref` | 用本仓库哪个分支或提交里的打包脚本、配置目录、帮助文本和更新说明，默认 `main` |
   | `notes` | 一般留空，自动读说明文件。填了就用填的内容 |
   | `min_version_code` | 一般留空。只有这个 core 必须配合某个 APK 修复才能跑，而 `coreApi` 又没变时才填 |

4. 跑完后，在这次运行的 Summary 里能看到写进索引的那一项，核对一下提交号和说明。

工作流按这个顺序执行：

1. 拉取 core 提交，算出标签 `core-<sha7>`。
2. 如果这个标签的 Release 已经存在就停下。发过的 core 包不能替换。
3. 从 `core-channel` 下载当前的 `stable.json`。
4. 用 `CORE_BUNDLE_SIGNING_KEY` 打包、签名，更新索引。
5. 用应用里的公钥验证索引签名、包的哈希和大小，以及包内清单的签名。
6. 先建 `core-<sha7>` Release 并上传 zip，再把 `stable.json` 和 `stable.json.sig` 传到 `core-channel`。

用户下次检查更新时就能看到新包。

### 第一次发布

仓库里还没有 `core-channel` 时，工作流会从空索引开始，并自动创建 `core-channel` Release，不用手动建。只要事先配好 `CORE_BUNDLE_SIGNING_KEY`，并且对应的公钥已经在 `core_bundle_keys.json` 里。

### 失败了怎么办

- 同一时间只会跑一个 core 发布，后面的会排队等。
- 在建 `core-<sha7>` Release 之前失败的，比如说明文件缺了、签名密钥没配、验证没通过，改好后直接重跑。
- `core-<sha7>` 已经建好，但上传到 `core-channel` 时失败了：这时索引还没指向新包，用户看不到它。删掉这个 Release 和它的标签，再重跑一次：

  ```bash
  gh release delete core-<sha7> --cleanup-tag --repo sk1lyn3/BAAS-AIO
  ```

  先确认 `core-channel` 里的 `stable.json` 确实没有这个提交，再删。

## coreApi 加一时

`coreApi` 变了，旧 APK 就装不了新的 core 包。要按这个顺序来：

1. 在本仓库把 `core_loader.py` 的 `CORE_API` 加一，连同胶水代码的修改一起提交。
2. 更新子模块到新 core，按上面的步骤发 APK。新 APK 的内置版本就是新 core，装上就能用。
3. 用包含这些修改的 `aio_ref` 发 core 包，这个包的 `coreApi` 就是新值。

旧 APK 的用户检查更新时，会看到提示，让他们先更新应用。旧 `coreApi` 的包还留在索引里，他们在更新应用之前仍然可以继续用。
