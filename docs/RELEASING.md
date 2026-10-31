# 发布说明

正式 `1.0.0` 使用 `versionCode = 99`、application ID `io.github.originosfcmguardian`、minSdk 30、targetSdk 37。维护者必须使用原有稳定签名身份，以保持与已有安装的升级兼容性。项目代码按 Apache-2.0 发布，第三方组件仍遵循各自许可和声明。

正常源码和 CI 构建无需正式签名材料。私钥、密码、设备数据和本地配置不得提交到 Git、写入文档或作为 Releases 附件。正式云端签名仅允许使用下述隔离环境中的加密 Secrets。

## 发布前检查

1. 核对版本配置、README、CHANGELOG、使用/构建说明、LICENSE、NOTICE、第三方声明与当前行为一致。
2. 在干净源码检出中按[构建说明](BUILDING.md)运行核心测试、Android 主机单元测试、lint、release 构建及依赖归属检查，记录实际结果。涉及设备行为时记录设备/系统环境及实际验证；没有运行的检查明确标记未验证。
3. 审查将公开的文件、完整 Git 历史、标签和附件，排除私人配置、设备诊断、凭据、调试包及内部资料。
4. 使用下述自动发行或本地签名流程，核验 APK 元数据、原证书和 SHA-256，并保存与该 APK 对应的源码提交。

## 本地签名助手（现有 1.0.0 备用流程）

`tools/build_release_apk.ps1` 支持 Windows PowerShell，使用 Android SDK 的 `apksigner.bat` 与 `apkanalyzer.bat`。请先按构建说明设置 JDK 17、`ANDROID_HOME`（或 `ANDROID_SDK_ROOT`），安装 Build Tools 36.0.0 与 Command-line Tools。SDK 若同时设置两个环境变量，助手优先使用 `ANDROID_HOME`。

通过本机安全方式在当前进程注入以下环境变量，不在 shell 历史中粘贴密码，不把变量值写入仓库或公开构建日志：

| 环境变量 | 含义 |
| --- | --- |
| `GUARDIAN_RELEASE_KEYSTORE` | 原有正式 keystore 的本机路径 |
| `GUARDIAN_RELEASE_KEY_ALIAS` | 原有正式签名条目 alias |
| `GUARDIAN_RELEASE_STORE_PASSWORD` | keystore 密码 |
| `GUARDIAN_RELEASE_KEY_PASSWORD` | 签名条目密码 |

上述变量设置完成后，从仓库根目录执行：

```powershell
.\gradlew.bat :core:test :app:testDebugUnitTest :app:lintDebug :app:exportReleaseDependencies
python tools/generate_dependency_notices.py --check
.\tools\build_release_apk.ps1
```

如执行策略要求，也可用已安装的 `pwsh -File .\tools\build_release_apk.ps1`。助手默认重新执行 `:app:assembleRelease`；只有确认当前未签名 APK 与 R8 映射来自本次待发布源码时，才可使用 `-SkipBuild`。可通过 `-Repository` 指定源码根目录，通过 `-BuildToolsVersion` 指定已安装的兼容工具版本（默认 `36.0.0`）。助手使用密码的环境变量引用传给 apksigner，不将密码展开为命令行参数。

助手会检查未签名及签名 APK 的版本、包名、SDK 和非 debuggable 属性，确认 R8 映射和必需许可 assets 存在，并要求 APK 只有一个签名者，证书 SHA-256 为：

```text
d662d39e97573ed1759994033584109eb26d06b57c7cae81a7cf3d4d840dfa9d
```

这是可公开的证书指纹，不是私钥。证书不匹配时助手拒绝产出正式 APK；不得生成新密钥替代原身份。

成功输出到 `build/releases/v1.0.0/`：

- `OriginOS-FCM-Guardian-1.0.0.apk`：正式签名 APK。
- `SHA256SUMS.txt`：APK 的 SHA-256 校验值。
- `artifact-manifest.json`：版本、大小、包名、SDK 和签名证书指纹等核验结果。

助手只在本机构建、签名和检查，不创建 Git 提交/标签、不连接设备、不安装 APK、不上传或创建 Release。签名成功也不能替代设备验证。签名变量使用完后从当前进程清除，R8 映射留在维护者安全存档中。

## 人工发布与附件边界

核对输出与源提交对应后，在维护者源站按授权的发布流程创建正式 `v1.0.0`，明确它对应的提交、版本和测试情况；公开下载统一使用 [GitHub Releases](https://github.com/XXhaos/OriginOS-FCM-Fix/releases)。正式附件为：

- `OriginOS-FCM-Guardian-1.0.0.apk`
- `OriginOS-FCM-Guardian-1.0.0-source.zip`
- `artifact-manifest.json`
- `SHA256SUMS.txt`

源码 ZIP 必须使用最终发布提交的 `git archive` 生成，包含根目录 LICENSE、NOTICE、第三方声明及 licenses/，不得直接压缩维护者工作目录。Git 标签也应指向该提交。公开 manifest 记录该源码提交、版本、APK 签名及实际检查范围，不包含本机路径或私人诊断。

在 Windows 上导出时，使用 `git -c core.autocrlf=false -c core.eol=lf archive`，以免本机默认行尾转换破坏生成文件的逐字节校验；仓库明确指定的文件属性仍生效。

签名助手先生成 APK 的校验值；发布前补齐最终 `SHA256SUMS.txt`，覆盖正式 APK、源码 ZIP 和公开 manifest，不包含校验文件自身。上传前重新校验四个文件，并在发布后核对下载文件与本地 SHA-256 一致。

不上传签名材料、本地 Gradle/SDK 缓存、依赖导出输入中的本地路径、R8 映射、原始私人诊断、内部工作记录或 debug APK。保留旧正式发行文件及其真实版本信息，不把旧测试包重新标记为本次正式包。

2026-10-30 的公开历史清理保留 `v1.0.0` 的源码树及 APK 原字节，只迁移标签对应的提交标识，并重新导出源码 ZIP、更新 manifest 与校验文件。原有测试声明保持不变。`main` 中新的诊断导出提示尚未包含在该旧版 APK 中。

## GitHub 发行同步

日常源码、标签和发行说明仍在 Gitea 维护。Git 推送镜像同步提交和标签；GitHub 上的 `.github/workflows/sync-gitea-releases.yml` 独立检查 Gitea 已发布的 Release，不需要在源站部署 runner。

### 发布新版本

1. 在源码中更新 `versionName` 与 `versionCode`：例如标签 `v1.0.1` 对应 `versionName = "1.0.1"`，版本码必须高于所有已发布版本（现有正式版为 99）。审查、提交到 Gitea 的 `main`。
2. 在 Gitea 选择该提交创建标签并发布 Release，填写标题、更新说明及预发布标记；新版本不上传附件。草稿不会触发发布。
3. 等待 Git 镜像和 GitHub 工作流。工作流在 main 推送时及每小时第 23、53 分钟检查，每次处理一个待构建版本；定时任务可能延迟。也可在 GitHub **Actions → Mirror Gitea releases → Run workflow** 选择 main、填写标签立即处理。
4. GitHub 针对标签的精确提交运行主机测试、lint、依赖声明和权限边界检查，构建 R8 Release；随后在独立临时运行器使用原证书签名，生成 APK、精确提交的源码 ZIP、manifest 和 SHA256SUMS。全部上传并核验后才公开对应 GitHub Release。Gitea 只需保留版本说明与标签，生成附件位于 GitHub。

两端标签必须已经存在、指向同一提交，而且该提交必须在可信 main 的历史中。标签、APK 版本、版本码或证书不匹配时停止，不自动修改版本号、移动标签或更换密钥。主机检查不代表已经做过真机或模拟器测试；manifest 如实记录检查范围。

Gitea 已经有四份正式附件的版本继续复用原字节同步，现有 v1.0.0 不重新构建或签名。只有零附件的新发行才进入构建；部分附件会停止并要求维护者处理。GitHub 已有完整匹配发行时跳过重建并同步说明；同名不同内容绝不自动覆盖。上传失败留下的完整草稿可续发；部分附件需核对后人工处理，避免重建字节覆盖原文件。

### 签名配置与边界

源站 HTTPS 根地址只保存在仓库 Actions Secret `GITEA_SOURCE_URL`，不得写入公开源码、日志或附件。发布说明和附件（含嵌套 APK/ZIP）若含源站域名，发布会停止。已有镜像凭据仍需能推送 `.github/workflows/`。

正式签名 Secrets 放在 GitHub 环境 `guardian-release`，部署分支只允许 `main`：

| Environment Secret | 内容 |
| --- | --- |
| `GUARDIAN_RELEASE_KEYSTORE_BASE64` | 原正式 keystore 的 Base64 内容 |
| `GUARDIAN_RELEASE_KEY_ALIAS` | 原条目 alias |
| `GUARDIAN_RELEASE_STORE_PASSWORD` | 原 keystore 密码 |
| `GUARDIAN_RELEASE_KEY_PASSWORD` | 原条目密码 |

Base64 只是文件编码，保密由 GitHub 加密 Secrets 提供。不要把值贴到聊天、命令历史或仓库。不得用新密钥代替原签名。构建 job 无签名 Secrets；签名 job 使用全新托管运行器，只执行可信 main 中的签名工具，不运行标签源码、Gradle 或构建缓存。keystore 仅在签名步骤写入临时目录（目录 700、文件 600），密码通过环境变量引用传给 apksigner，结束后删除临时文件。

只有规划及发布 job 使用仓库自动提供的 `GITHUB_TOKEN`，权限为 `contents: write`，无需额外 PAT。公开 fork、PR 和手动选择的其他分支不能进入此发布流程。源码镜像以外的环境与 Secrets 在 GitHub 上初始化，需要时也在 GitHub 维护；后续 Gitea 提交不会覆盖或自动修复这些设置。

运行间传递的发布计划和未签名候选包只保留 1 天，不包含 keystore、密码、调试包、R8 映射或含本机路径的依赖导出输入。公开的四份发行文件长期保留。需要 R8 映射用于后续排障时，维护者应从对应源码在安全环境自行构建存档；当前云端流程不公开或存储该映射。

### 核验与故障处理

日常只需维护 Gitea；GitHub 正常运行时无需手动操作。若出现任务失败、同步或发行缺失、凭据失效、环境规则阻止执行或定时任务被停用，Codex 在处理当前维护任务时应主动检查 GitHub 的实际运行、日志和相关设置，并在已有授权与访问能力内处理，不应把“Gitea 是主源”理解为无需排查 GitHub，也不默认要求维护者自行操作。

- 代码和工作流文件的问题：在 Gitea 修复、提交，再由镜像同步到 GitHub。
- GitHub 服务端设置的问题：在 GitHub 维护 Actions 启用状态、Secrets、环境与权限等配置；缺少必要私密输入或确需额外授权时再向维护者确认，保留原签名与发布安全边界。
- 恢复后：核对两站目标分支/标签指向一致，并确认对应的实际工作流成功或 Release 附件完整、签名和校验正常；只看到推送成功或标签存在不算修复完成。

首次配置后，用 main 手动运行、填写已有标签并勾选 `validate_only`：会真实构建、签名、核验，最后不创建或修改任何 Release/附件。普通自动运行不要勾选此项。验证不会升级手机，设备测试仍需单独授权及实际执行。

失败时查看对应工作流，检查源站可达性、Git 镜像是否滞后、标签与源码版本、签名 Secrets、环境分支限制及附件校验。重跑会重新核对源 Release 仍已发布且标签未移动。公共仓库的定时任务连续 60 天无活动可能被停用，可到 Actions 重新启用。

保留只读的旧附件检查入口（当前进程提供 `GITEA_SOURCE_URL`，不访问目标、无需 GitHub 凭据）：

```sh
python tools/sync_gitea_releases.py --source-only --tag v1.0.0
python -m unittest discover -s tools/tests -p 'test_*.py'
```

2026-10-31 的地址清理保留旧正式 APK，历史源码 ZIP、manifest 和校验文件随清理后的提交标识更新。旧 SHA 缓存与第三方副本仍需平台或持有者单独处理，历史改写不等于删除所有互联网副本。

参考：[Gitea 镜像范围](https://docs.gitea.com/usage/repository/repo-mirror/)、[GitHub Secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)、[GitHub Release API](https://docs.github.com/en/rest/releases/releases)、[定时工作流](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule)、[Android 应用签名](https://developer.android.com/studio/publish/app-signing)、[apksigner](https://developer.android.com/tools/apksigner)。
