# OriginOS FCM Guardian

面向国行 vivo / iQOO OriginOS 7 的非 Root 应用唤醒恢复与 FCM 保活工具。

**2026-10-07 v62日常试用就绪。** 按用户要求删除独立GMS配置保护，保留应用stopped恢复，
一个“FCM保护”统一低频维护与准确服务缺失时的单次恢复试用。首次开启/共同OFFON及
实际JSON/Markdown日志导出通过；不是所有自然断连/耗电效果已验收。
用户当前2项保护及FCM保护开启；未恢复卸载时已删除的旧26项数据/授权。按用户要求暂停等体验。
当前构建、验证与下一步只看 [PROJECT_STATE](docs/PROJECT_STATE.md)。

## 本机备份保存点 — 2026-10-07

本次按用户要求将当前v62源码、测试、计划、进度及脱敏验证记录提交到Gitea `main`，
用途是本机开发备份，不是重新启动开发、发布正式版或宣称所有阶段验收完成。
已通过798项测试、Debug/Release构建和本轮手机差异验收；当前停在日常试用反馈阶段。
交接文档保留历史，不维护、不生成迁移包。

Git备份不包含签名私钥、Firebase配置/发送凭据、手机密码、原始诊断/私有报告、
GMS APK、构建缓存或安装产物。这些文件仍保留本机，不因推送删除；
所以这是一份可追溯的源码与文档备份，不是包含所有私人文件的整机备份。

## 功能范围

- 应用页选择保护对象，首页总开关控制运行；关闭总开关不清空选择。
- 外置 Shizuku 就绪且授权有效时，检测并解除目标应用的标准停止标记，不要求目标常驻。
- 单应用/全部已选择应用复检：后台任务、有限并行读取、串行恢复；正常恢复不设固定次数配额。
- FCM连接：观察Google Play服务自己的传统端口socket和GcmService自报值；提供原生
  FCM Diagnostics入口；一个试用开关控制连接时低频心跳及精确缺失时单次恢复。
  socket存在或自报已连接不保证实际消息送达。
- 主页/应用、底部应用详情、跟随系统深浅主题、控制中心磁贴、恢复次数和最小脱敏报告。

**不再提供**独立GMS健康/配置检查、AppOps/Doze/PEM修复、白名单回滚与自动策略保护。
FCM仍由Google Play服务承载，因此保留FCM所需的GMS身份和连接读取，不是保留上述独立功能。

## 已知边界

- 精确服务缺失的有界冷初始化候选已部署；自然同进程缺失/实际App派发效果仍待日常观察。
  服务存在但断开或状态未知不会盲发。旧版GCM_RECONNECT被导出策略拒绝，不循环重试它。
- 现有心跳只在自报已连接且资格有效时发送，不能宣传为完整断连修复。
- 外置Shizuku是唯一特权后端；Guardian不会自行启动它，重启后需实际恢复shell服务。
- 检测可能受系统休眠限制，不承诺永久常驻、30秒硬时限或通知必达。

参考结论与后续范围：[FCM简化路线](docs/FCM_KEEPALIVE_SCOPE_20261006.md)。
执行顺序：[ACCELERATED_PLAN](docs/ACCELERATED_PLAN.md)。

## 使用

安装最终验证包后，先启动外置Shizuku并授权Guardian；应用授权和保护同意是两回事。
在“应用”中选择目标，在主页开启总开关；按新的统一确认开启“FCM保护（试用）”。
手机重启后恢复分支需要重新确认当前开机身份，且外置Shizuku必须实际可用。
自启动权限由用户在系统中手动设置；
Guardian仅提供设置入口，不检测或修改自启动。目标应用无需保留后台。

应用保护检查约30秒一次，系统休眠可能延后；“今日/本周确认解除”是停止限制解除记录，
不是通知数量。Guardian的通知显示由系统设置控制，隐藏通知不应清空应用选择。
控制中心磁贴只作辅助启动/恢复，不能独立获得Shizuku权限或保证永久保活。
之前卸载已经移除了旧配置，不从历史记录恢复旧26项选择或保护授权。

日常按自己的Wi-Fi/移动数据与亮屏/锁屏习惯使用，不必持续插USB或反复发送健康测试。
若遇真实异常，反馈应用、时间、网络和当前保护状态即可，不提交通知正文、token或密钥。

## 本机开发

阅读 [START_HERE](START_HERE.md)、`AGENTS.md` 和当前状态；不再维护跨电脑handoff。
手机默认未连接，只有用户本轮确认后才按
[DEVICE_ACCEPTANCE_QUEUE](docs/DEVICE_ACCEPTANCE_QUEUE.md)集中验证受影响差异。
历史源码和结论仍保留在Git、功能文档与fixtures，不按历史Next重复验收。

需要JDK17、Android SDK37、Build Tools36；在被忽略的 `local.properties` 配置SDK。

```powershell
.\gradlew.bat :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-parallel --max-workers=2
```

调试包：`app/build/outputs/apk/debug/app-debug.apk`。覆盖安装使用原调试签名，
`GUARDIAN_DEBUG_KEYSTORE`指向私人保存的keystore，不为签名问题卸载/清数据。
Firebase自测配置 `app/google-services.json`可选；缺失仍能构建，发送凭据不得打包或提交。
签名、Firebase配置、原始诊断、GMS APK和密钥仅留本地。

[暂停点](docs/PAUSED_CHECKPOINT_20261006.md)为历史记录，不是当前在机状态。
