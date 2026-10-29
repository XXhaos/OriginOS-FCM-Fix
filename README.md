# OriginOS FCM Guardian

OriginOS FCM Guardian 是一个 Android 工具，帮助维护所选应用的停止标记状态及 FCM 连接。它依赖用户自行安装、启动并授权的外部 Shizuku。实际效果取决于系统、权限和应用状态，不能保证第三方推送消息一定送达。

## 使用前

- Android 11 或更高版本；主要面向 OriginOS，其他系统的可用性取决于运行时接口和权限。
- 单独安装并启动 [Shizuku](https://shizuku.rikka.app/)，以 shell 模式运行，并在 Guardian 中授权。
- 设备重启后，用户需要重新启动 Shizuku；Guardian 在连接和条件恢复后会尝试接续原有开启设置。Guardian 不会自动启动 Shizuku 或启用无线调试。

从 [Releases](https://github.com/XXhaos/OriginOS-FCM-Fix/releases) 获取正式 APK，或参照[安装说明](docs/INSTALLATION.md)自行构建。覆盖安装要求与已安装版本的签名兼容；签名不同的 APK 无法直接覆盖。

## 开始使用

1. 启动并授权 Shizuku，然后打开 Guardian，检查连接状态。
2. 在应用页面选择需要维护的应用，按界面提示完成授权。
3. 打开保护总开关，查看状态与提示。关闭总开关会暂停执行并保留设置。

Guardian 会检查可用的系统接口与权限，并根据当前条件执行恢复；它不是设备型号白名单。系统回收、权限撤销或外部服务不可用时，功能可能暂停。有关权限及数据的说明见[隐私说明](PRIVACY.md)。

## 开发与反馈

需要 JDK 17、Android SDK 37，使用仓库中的 Gradle wrapper。构建与验证命令见[构建说明](docs/BUILDING.md)，协作约定见[贡献指南](CONTRIBUTING.md)。

请通过 [Issues](https://github.com/XXhaos/OriginOS-FCM-Fix/issues) 报告普通问题；提交前删除账号、设备标识、应用列表和其他私人信息。敏感漏洞请遵循[安全说明](SECURITY.md)。

本项目采用 [Apache License 2.0](LICENSE)；第三方组件信息见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。版本变化见[更新记录](CHANGELOG.md)。
