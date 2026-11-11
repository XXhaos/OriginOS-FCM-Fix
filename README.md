# OriginOS FCM Guardian

面向 vivo / iQOO OriginOS 的 Android 推送辅助工具。通过 Shizuku 自动解除所选应用的停止标记，并监测、维护 Google FCM 推送连接，帮助改善后台消息延迟和推送中断的问题，无需 Root。

[下载 APK](https://github.com/XXhaos/OriginOS-FCM-Fix/releases) · [更新记录](CHANGELOG.md)

## 主要功能

- **应用自动恢复**：检查所选应用的停止状态，按用户授权自动尝试解除停止标记。
- **FCM 连接保护**：监测 Google Play 服务的推送连接，定期进行心跳维护，并在连接异常、条件满足时尝试恢复。
- **后台运行**：开启保护后无需一直停留在界面；保留开关和应用选择，在运行条件恢复后尝试接续保护。
- **状态与诊断**：查看保护时长、应用恢复统计和 FCM 连接信息，按需导出诊断日志。
- **便捷操作**：通过控制中心“推送保护”磁贴查看状态、进入应用，并可在应用内检查和下载更新。

## 使用条件

- Android 11 或更高版本，在手机主用户空间使用。
- 安装并启动 [Shizuku](https://shizuku.rikka.app/)，使用通过无线调试或 ADB 启动的非 Root（shell）模式。
- 使用 FCM 相关功能时，需要已安装并启用 Google Play 服务，且网络能够连接 FCM。

项目主要面向 OriginOS；其他系统的可用性取决于实际提供的系统接口和权限。

## 安装与使用

1. 安装 Shizuku，按其说明完成启动。
2. 从 [Releases](https://github.com/XXhaos/OriginOS-FCM-Fix/releases) 下载正式 APK 并安装。
3. 打开 Guardian，授予 Shizuku 权限，在应用页面选择需要保护的应用，按提示确认。
4. 返回首页，打开“FCM推送保护”总开关并确认，等待保护状态显示已开启。

可在 Shizuku 卡片的“设置”中进入系统自启动管理，并调整通知和最近任务列表显示等选项。

## 日常使用

- **暂停保护**：关闭首页总开关，已选应用会保留，之后可以重新开启。
- **手机重启**：解锁后重新启动 Shizuku；Guardian 会在授权及运行条件恢复后尝试接续此前已开启的保护。
- **强行停止后**：在系统设置中强行停止 Guardian 会暂停执行，重新打开应用后再检查保护状态。
- **查看连接**：通过首页顶部的“FCM Diagnostics”访问 Google Play 服务提供的连接诊断页面，部分版本可能不提供此页面。
- **导出日志**：进入“Shizuku → 设置”，滑到最下方点击“导出日志”，保存 JSON 文件。日志中的应用名称和包名会匿名化，不会自动上传。数据处理详情见[隐私说明](PRIVACY.md)。
- **更新应用**：在首页底部“关于 → 检查更新”中下载新版，由系统确认安装。使用签名兼容的正式 APK 覆盖升级可保留现有设置。

推送效果受目标应用、Google Play 服务、网络和系统后台策略影响，无法保证每条消息送达。

## 从源码构建

需要 JDK 17 和 Android SDK 37，使用仓库自带的 Gradle Wrapper。具体步骤见[构建说明](docs/BUILDING.md)。

## 开源许可

本项目采用 [Apache License 2.0](LICENSE)。第三方组件声明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
