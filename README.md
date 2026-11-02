# OriginOS FCM Guardian

OriginOS FCM Guardian 是一个 Android 工具，帮助维护所选应用的停止标记状态及 FCM 连接。它依赖用户自行安装、启动并授权的外部 Shizuku。实际效果取决于系统、权限和应用状态，不能保证第三方推送消息一定送达。

## 使用前

- Android 11 或更高版本；主要面向 OriginOS，其他系统的可用性取决于运行时接口和权限。
- 单独安装并启动 [Shizuku](https://shizuku.rikka.app/)，以 shell 模式运行，并在 Guardian 中授权。
- 设备重启后，用户需要重新启动 Shizuku；Guardian 在连接和条件恢复后会尝试接续原有开启设置。Guardian 不会自动启动 Shizuku 或启用无线调试。

从 [Releases](https://github.com/XXhaos/OriginOS-FCM-Fix/releases) 获取正式 APK，或参照[构建说明](docs/BUILDING.md)自行构建。覆盖安装要求与已安装版本的签名兼容；签名不同的 APK 无法直接覆盖。

## 开始使用

1. 启动并授权 Shizuku，然后打开 Guardian，检查连接状态。
2. 在应用页面选择需要维护的应用，按界面提示完成授权。
3. 打开“FCM推送保护”总开关并确认，后台就绪后会继续完成开启；等待期间可离开页面，无需额外点击完成按钮。关闭总开关会取消待启动请求、暂停执行并保留应用选择。

若在系统设置中强行停止 Guardian，执行会暂停。重新打开后，独立后台会在当前停止状态已解除、身份与 Shizuku 权限核验通过时接续原来保存的开启设置；首页主动关闭总开关仍会保持关闭。

如果旧修复包已停在“开启结果待核对”，覆盖升级后请关闭总开关，待关闭完成，再重新开启并确认一次；无需清除数据，应用选择会保留。新包会区分尚未执行开启的短暂失败与执行结果不明，并保留精简的开启诊断。

Guardian 会检查可用的系统接口与权限，并根据当前条件执行恢复；它不是设备型号白名单。系统回收、权限撤销或外部服务不可用时，功能可能暂停。有关权限及数据的说明见[隐私说明](PRIVACY.md)。

主页保留保护开启时长，FCM 连接信息可从顶部的“FCM Diagnostics”查看。移除主页的连接计时和额外刷新不影响后台 FCM 检测、心跳维护与自动恢复；反馈问题时可导出已有状态的脱敏诊断摘要。

主页底部的“关于”显示版本并提供“检查更新”，仅在点击时访问本项目 GitHub 最新正式发行。发现新版后可下载并交由系统确认安装；包名、版本、校验值和签名均需核验，现有设置保留。

日志导出位于“Shizuku → 设置”最下方。点击“导出日志”直接保存一份 JSON，应用名称和包名替换为报告内的匿名引用；保留系统版本、运行与资源摘要用于排查，不上传日志。正式安装包以 Releases 中通过签名和校验的附件为准。

Google Play 服务使用共享 UID 时，固定组件的操作独立核验 Google Play 服务本身，不查询其他共享成员的签名；无法归属的套接字不作为断线依据。升级修复包会尝试接续已确认的开启请求，不需要清除数据。

## 开发与反馈

需要 JDK 17、Android SDK 37，使用仓库中的 Gradle wrapper。构建与验证命令见[构建说明](docs/BUILDING.md)，协作约定见[贡献指南](CONTRIBUTING.md)。

代码在 Gitea 维护并同步到 GitHub；同步后的 `main` 会触发 [Android 自动构建](https://github.com/XXhaos/OriginOS-FCM-Fix/actions/workflows/android-ci.yml)。测试报告、调试 APK 和未签名 Release APK 可在对应运行的 Artifacts 下载，保留 14 天。CI 包使用临时调试签名或尚未签名，正式安装请使用 Releases。

请通过 [Issues](https://github.com/XXhaos/OriginOS-FCM-Fix/issues) 报告普通问题；提交前删除账号、设备标识、应用列表和其他私人信息。敏感漏洞请遵循[安全说明](SECURITY.md)。

本项目采用 [Apache License 2.0](LICENSE)；第三方组件信息见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。版本变化见[更新记录](CHANGELOG.md)。

Gitea 作为正式发行来源；发布新版本的标签和说明后，GitHub 自动测试、构建、使用原证书签名并上传对应 Release。已有四份附件的旧发行保持原文件同步，详见[发行说明](docs/RELEASING.md#github-发行同步)。
