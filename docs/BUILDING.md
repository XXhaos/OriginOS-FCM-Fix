# 从源码构建

从仓库根目录执行以下命令。需要 JDK 17、Android SDK Command-line Tools，以及网络访问项目配置的依赖仓库。项目使用 Gradle Wrapper 9.3.1（已记录分发包 SHA-256），不需要另装系统 Gradle。Android SDK 需要 API 37 平台、Build Tools 36.0.0 和 platform-tools。

Windows PowerShell，在已设置 `JAVA_HOME` 和 `ANDROID_HOME` 后：

```powershell
& "$env:ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager.bat" --licenses
& "$env:ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager.bat" 'platform-tools' 'platforms;android-37.0' 'build-tools;36.0.0'
.\gradlew.bat --version
.\gradlew.bat :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
.\gradlew.bat :app:assembleDebug
```

Linux/macOS，在已设置 `JAVA_HOME` 和 `ANDROID_HOME` 后：

```sh
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" 'platform-tools' 'platforms;android-37.0' 'build-tools;36.0.0'
bash ./gradlew --version
bash ./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
bash ./gradlew :app:assembleDebug
```

SDK 路径也可写入本机 `local.properties` 的 `sdk.dir`，不要提交该文件。执行 `--licenses` 时阅读并接受适用的 Android SDK 许可。API 37 平台必须能由当前 SDK 仓库安装，不能用旧平台替代后声称完成本项目构建。

| 文件 / 目录 | 用途 |
| --- | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | 使用本地 debug 签名的调试包 |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | R8 压缩后的未签名 release 包 |
| `app/build/outputs/mapping/release/mapping.txt` | 对应 release 的 R8 映射，维护者本地保存 |
| `core/build/reports/tests/test/` | 核心模块主机测试报告 |
| `app/build/reports/tests/testDebugUnitTest/` | Android 模块主机单元测试报告 |
| `app/build/reports/lint-results-debug.html` | Debug lint 报告 |

调试包不是官方签名版本，不能覆盖使用正式证书的已有安装。release 默认无签名；正式签名步骤见[发布说明](RELEASING.md)。正常构建不需要任何维护者密钥。`GUARDIAN_DEBUG_KEYSTORE` 仅是可选的本地调试密钥路径覆盖项，公开构建无需设置。

设备测试需要兼容的 Android 设备、Shizuku 和适用的授权环境。主机测试及 lint 不能验证 OriginOS 后台行为、真实 FCM 到达率或设备兼容性。

## 依赖归属检查

`licenses/` 作为 APK assets 打包，保存项目及运行时依赖的许可与上游声明。Python 3 仅用于维护、检查生成内容，不是普通 Android 构建依赖：

```powershell
.\gradlew.bat :app:exportReleaseDependencies
python tools/generate_dependency_notices.py --check
```

Linux/macOS 对应使用 `bash ./gradlew :app:exportReleaseDependencies` 和 `python3 tools/generate_dependency_notices.py --check`。若调整依赖后检查失败，去掉 `--check` 重新生成，审阅 `licenses/dependencies/`、根目录第三方声明和许可证后一起提交。导出的 `app/build/reports/dependencies/release-artifacts.json` 含本地缓存路径，仅作生成工具输入，不作为公开附件。

## Gitea CI 与执行权限

`.gitea/workflows/ci.yml` 为 `main` push 和手动触发提供主机测试、Debug lint、未签名 release 构建及依赖归属检查；不签名、不上传 APK、不自动发布。工作流存在不代表服务器已配置 runner 或 CI 已通过，实际结果以对应提交的 Actions 日志为准。

维护者必须先配置标签为 `guardian-ci` 的隔离 Linux runner，提供 Bash、Git、Python 3、可运行 Node 20 actions 的运行环境及足够磁盘空间，并允许访问 Gitea、GitHub action 源、JDK/SDK 下载和 Gradle 依赖源。该标签应映射到专门的临时容器或临时虚拟机；不得映射到持有私人数据、签名材料、Docker 管理套接字或其他生产凭据的日常工作主机。Android SDK 安装 action 会接受 SDK 许可，启用前应由维护者确认。

工作流显式从 GitHub 获取 `checkout@v4`、`setup-java@v4` 和 `setup-android@v3`；升级或启用前应审阅这些外部执行依赖与 runner 配置。checkout 不保留认证信息。Gitea 版本对 token 权限支持可能不同，维护者应在服务器/仓库设置中确保任务 token 只有读取源码所需权限。仓库 Actions 功能与手动触发也需当前服务器版本支持并启用。

公开 fork 和外部 PR 不自动触发此工作流。手动运行仅选择维护者已审阅的源码分支；审阅应包含 Gradle 脚本与工作流本身，因为构建会执行仓库代码。若要验证外部贡献，先审阅，再放入可信分支并在同样隔离环境运行；不得添加 `pull_request_target` 或在未知自托管主机上直接执行未审阅代码。

参考：[Gitea Actions 快速开始](https://docs.gitea.com/usage/actions/quickstart/)、[与 GitHub Actions 的差异](https://docs.gitea.com/usage/actions/comparison/)、[Android 命令行构建](https://developer.android.com/build/building-cmdline)。
