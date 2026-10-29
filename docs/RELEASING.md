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

核对输出与源提交对应后，在[项目 Releases](https://github.com/XXhaos/OriginOS-FCM-Fix/releases)按授权的发布流程创建正式 `v1.0.0`，明确它对应的提交、版本和测试情况。正式附件为：

- `OriginOS-FCM-Guardian-1.0.0.apk`
- `OriginOS-FCM-Guardian-1.0.0-source.zip`
- `artifact-manifest.json`
- `SHA256SUMS.txt`

源码 ZIP 必须使用最终发布提交的 `git archive` 生成，包含根目录 LICENSE、NOTICE、第三方声明及 licenses/，不得直接压缩维护者工作目录。Git 标签也应指向该提交。公开 manifest 记录该源码提交、版本、APK 签名及实际检查范围，不包含本机路径或私人诊断。

签名助手先生成 APK 的校验值；发布前补齐最终 `SHA256SUMS.txt`，覆盖正式 APK、源码 ZIP 和公开 manifest，不包含校验文件自身。上传前重新校验四个文件，并在发布后核对下载文件与本地 SHA-256 一致。

不上传签名材料、本地 Gradle/SDK 缓存、依赖导出输入中的本地路径、R8 映射、原始私人诊断、内部工作记录或 debug APK。保留旧正式发行文件及其真实版本信息，不把旧测试包重新标记为本次正式包。

签名工具参考：[Android 应用签名](https://developer.android.com/studio/publish/app-signing)、[apksigner](https://developer.android.com/tools/apksigner)、[apkanalyzer](https://developer.android.com/tools/apkanalyzer)。
