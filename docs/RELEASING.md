# 发布说明

正式 `1.0.0` 使用 `versionCode = 99`、application ID `io.github.originosfcmguardian`、minSdk 30、targetSdk 37。维护者必须使用原有稳定签名身份，以保持与已有安装的升级兼容性。项目代码按 Apache-2.0 发布，第三方组件仍遵循各自许可和声明。

正常源码和 CI 构建无需正式签名材料。私钥、密码、设备数据和本地配置不得提交到 Git、写入文档或作为 Releases 附件；不通过公开 CI 执行正式签名。

## 发布前检查

1. 核对版本配置、README、CHANGELOG、使用/构建说明、LICENSE、NOTICE、第三方声明与当前行为一致。
2. 在干净源码检出中按[构建说明](BUILDING.md)运行核心测试、Android 主机单元测试、lint、release 构建及依赖归属检查，记录实际结果。涉及设备行为时记录设备/系统环境及实际验证；没有运行的检查明确标记未验证。
3. 审查将公开的文件、完整 Git 历史、标签和附件，排除私人配置、设备诊断、凭据、调试包及内部资料。
4. 使用下面的本地签名流程，核验 APK 元数据、签名和 SHA-256。保存与该 APK 匹配的源码提交及本地 R8 映射。

## 本地签名助手

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

Gitea 的 Git 推送镜像仅同步提交、分支和标签，不能自动复制 Release 说明与附件。Gitea 是正式发行的来源；`.github/workflows/sync-gitea-releases.yml` 在 GitHub 使用托管 Linux 运行器，将已发布版本复制到 [GitHub Releases](https://github.com/XXhaos/OriginOS-FCM-Fix/releases)。这与前面的 Gitea 构建 CI 是两个独立工作流，不需要 `guardian-ci` 运行器，也不在云端重新构建或签名 APK。

- 在维护者 `main` 分支推送、手动运行和每小时第 23、53 分钟检查；定时任务可能延迟，并非即时同步。首次先确认工作流已随 Git 镜像抵达 GitHub，Actions 已启用且任务成功。
- 使用 GitHub 自动提供、仅作用于当前仓库的 `GITHUB_TOKEN`，权限为 `contents: write`；无需添加 PAT 或把签名材料放入 Secrets。现有 Gitea 镜像凭据仍需有推送 `.github/workflows/` 文件的权限。
- 源站地址通过 GitHub 仓库的 Actions Secret `GITEA_SOURCE_URL` 配置，值为 HTTPS 站点根地址，不带账号、密码、路径或查询参数。不要把真实域名写进公开源码、文档、日志或发行附件；本地核验通过当前进程环境变量提供同名配置。未配置时同步直接停止。
- 只从已配置的 Gitea 源站读取固定仓库的已发布版本，并复制前述四份附件。两端标签必须已经存在，且与 manifest 的源码提交一致；不会自动创建或移动标签。
- 发布说明和附件（包括解压后的源码 ZIP 与 APK 内容）若含源站域名，同步会停止，避免再次公开地址。
- 上传前核对 manifest、原签名证书指纹声明、文件名、大小和 SHA-256；上传后校验 GitHub 附件。复用既有正式 APK 的字节，不重新签名；源 APK 的实际证书验收仍由正式签名流程负责。
- 新版本先创建草稿，四份附件全部就绪后才公开。重复运行跳过已匹配附件；同名附件校验不同则失败并保留原文件，不自动覆盖或删除。

维护者可在 GitHub **Actions → Mirror Gitea releases → Run workflow** 立即同步，分支选 `main`，可填 `v1.0.0` 等标签；留空检查所有已发布版本。同步失败时检查任务日志，特别是源站可达性、Git 镜像是否滞后、附件校验和 GitHub 权限。GitHub 定时任务只运行默认分支；公共仓库连续 60 天无活动可能被自动停用，可在 Actions 中重新启用。工作流不会改变仓库可见性，私有仓库的发行也需要对应访问权限。

2026-10-31 的地址清理只更新公开历史中的文档链接及维护脚本，发布标签中的应用源码与正式 APK 保持不变；源码 ZIP、manifest 和校验文件随新提交标识更新。旧 SHA 缓存与第三方副本仍需平台或持有者单独处理，历史改写不等于彻底删除互联网副本。

普通中断留下的草稿可以重跑续传；若 GitHub 上传错误留下 `starter` 状态或零字节的同名附件，脚本会安全停止。维护者需核对并手动移除该未完成的草稿附件后重跑，不自动删除已上传内容。

本机只读验证源发行（先在当前进程提供 `GITEA_SOURCE_URL`，不访问目标、无需凭据）：

```sh
python tools/sync_gitea_releases.py --source-only --tag v1.0.0
python -m unittest discover -s tools/tests -p 'test_sync_gitea_releases.py'
```

不带 `--source-only` 的默认运行也只检查，不发布；仅明确传入 `--publish` 才会写入 GitHub，且需要当前进程的 `GITHUB_TOKEN`。不要将 token 值粘贴到命令、文档或日志中。正常发行只需在 Gitea 完成发布后等待工作流，或手动运行一次。

参考：[Gitea 镜像范围](https://docs.gitea.com/usage/repository/repo-mirror/)、[GitHub Release API](https://docs.github.com/en/rest/releases/releases)、[GITHUB_TOKEN](https://docs.github.com/en/actions/tutorials/authenticate-with-github_token)、[定时工作流](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule)。

签名工具参考：[Android 应用签名](https://developer.android.com/studio/publish/app-signing)、[apksigner](https://developer.android.com/tools/apksigner)、[apkanalyzer](https://developer.android.com/tools/apkanalyzer)。
