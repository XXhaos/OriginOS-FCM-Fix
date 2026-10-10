# OriginOS FCM Guardian

面向国行 vivo / iQOO OriginOS 7 的非 Root 应用唤醒恢复与 FCM 保活工具。

**2026-10-10 v68 / 0.1.0-lean-runtime已覆盖安装。** 日常包使用本机原开发签名，
为非调试 Release，启用R8和资源收缩；启动读取与生命周期日志的空闲线程30秒后回收，
占用/卡住的工作线程及原有队列、期限和恢复资格保持有界。
898项单测（52core+846app）、Debug/Release/AndroidTest构建、lint0E23W及原签名/产物门通过；
instrumentation仅编译，未运行。实机保留21项选择，自动检测、FGS、Shizuku shell与正常FCM读取通过；
从既有FCM暂停基线经普通确认恢复“已开启·低频维护”。

Debug包39,175,145bytes，日常包4,152,623bytes，仅证明APK缩小，尚不证明RAM、终止率或耗电改善。
未新增优先级/绑定服务或通知访问，未作目标/GMS/网络故障注入或重启；正常JSON导出/解析通过，
包含原21项选择与64条生命周期记录、dropped0。本包结束，交日常观察；功能不为资源指标让步。
见[v68有限证据](docs/fixtures/protection/20261010-v68-lean/verification.json)及
[当前状态](docs/PROJECT_STATE.md)。不据此重开已完成历史验收。

## v67历史记录 — 2026-10-07

**2026-10-07 v67已安装，本包有限验证完成。** 应用页仅显示实际用户安装App，
预装及其更新隐藏但不清配置；不按Google前缀或桌面入口误排应用。
普通用户应用的共享UID经精确身份和实际成员归属核验后可保护，保留核心/禁用/异常身份边界。
运行时能力替代单vivo固件/唯一GMS版本准入，保留权限、同意和操作后复查，不兼容只停对应能力。
今日/本周解除计数移至首页FCM之后，高级FCM/自动详情/恢复操作历史入口移除，日志/报告与复查摘要保留。

895项单测、完整构建/编译/lint、原签名/产物门和独立Astra审查通过；Calendar实际选中与自动解除
自然停止标记通过，邻居同步组件标记不变。当前21项ACTIVE，原20项tokens/identity全保留，
FCM正常确认开启、实际FGS存在，手机已回Home息屏锁定。详见[v67合同](docs/PORTABLE_PROTECTION_V67.md)。
共享进程影响、普遍机型/GMS兼容和消息必达不由该有限验收保证；旧两次BLOCKED因果仍未知。

保留v63生命周期日志/磁贴、v64固定取样时长、v65青绿深浅主题和v66直接读取/切页保留。
主动总OFF不会被磁贴重新开启，自然中断根因尚未捕获；不恢复独立GMS保护或内置Shizuku，
不新增检测频率，不从历史恢复卸载时已删除的旧26项授权。
本包关闭，交正常使用；当前构建、验证与下一步看[PROJECT_STATE](docs/PROJECT_STATE.md)。

## 本轮本机备份 — 2026-10-10

用户已授权将v68资源优化源码、测试、三日观察与限定验证证据，以及最新README、操作入口、
计划和进度提交并推送Gitea `main`。提交标识和远端同步结果以Git历史/refs为准。
本机Git连接直接使用`~/.ssh`中的对应私钥，禁用SSH Agent；私钥不进入仓库或Release。
提交作者与提交者统一使用`XXhaos <53092039+XXhaos@users.noreply.github.com>`；本轮只修正最新备份提交的身份，更早历史不改写。
本轮检查Gitea无已有Release、远端无标签；此次仅源码/文档备份，不新建正式发布或上传私人APK。
v68功能与验证边界保持不变，继续日常试用，不维护handoff；本次授权不延伸为未来自动推送。

## 历史本机备份保存点 — 2026-10-07

用户已明确授权本轮Gitea本机备份。本次提交收录v63–v67源码、测试、计划/进度和脱敏证据，
包括生命周期/磁贴及日志、连接时长、视觉、首页直接读取、应用资格/运行时、共享UID及回调修复。
v67 CLOSED/895及实机结果保持不变；提交标识和远端同步状态以Git记录为准。

此前按用户要求将v62源码、测试、计划、进度及脱敏验证记录提交到Gitea `main`，
用途是本机开发备份，不是重新启动开发、发布正式版或宣称所有阶段验收完成。
该v62历史保存点通过798项测试、Debug/Release构建和手机差异验收，与本轮v63–v67备份分开。
当时v67有限包完成，交正常使用；该历史授权不延伸为未来自动提交或推送。
交接文档保留历史，不维护、不生成迁移包。

Git备份不包含签名私钥、Firebase配置/发送凭据、手机密码、原始诊断/私有报告、
GMS APK、构建缓存或安装产物。这些文件仍保留本机，不因推送删除；
所以这是一份可追溯的源码与文档备份，不是包含所有私人文件的整机备份。

## 功能范围

- 应用页选择保护对象，首页总开关控制运行；关闭总开关不清空选择。
- 仅展示用户安装应用，预装及其更新隐藏；资格按真实安装/UID/组件状态判断，隐藏不删除配置。
- 外置 Shizuku 就绪且授权有效时，检测并解除目标应用的标准停止标记，不要求目标常驻。
- 单应用/全部已选择应用复检：后台任务、有限并行读取、串行恢复；正常恢复不设固定次数配额。
- FCM连接：观察Google Play服务自己的传统端口socket和GcmService自报值；提供原生
  FCM Diagnostics入口；一个试用开关控制连接时低频心跳及精确缺失时单次恢复。
  socket存在或自报已连接不保证实际消息送达。
- 主页/应用、底部应用详情、跟随系统深浅主题、控制中心磁贴、恢复次数和最小脱敏报告。
  新保护生命周期日志见 [v63说明](docs/PROTECTION_LIFECYCLE_V63.md)，最多64条，无聊天/异常正文。

**不再提供**独立GMS健康/配置检查、AppOps/Doze/PEM修复、白名单回滚与自动策略保护。
FCM仍由Google Play服务承载，因此保留FCM所需的GMS身份和连接读取，不是保留上述独立功能。

## 已知边界

- 精确服务缺失的有界冷初始化候选已部署；自然同进程缺失/实际App派发效果仍待日常观察。
  服务存在但断开或状态未知不会盲发。旧版GCM_RECONNECT被导出策略拒绝，不循环重试它。
- 现有心跳只在自报已连接且资格有效时发送，不能宣传为完整断连修复。
- 外置Shizuku是唯一特权后端；Guardian不会自行启动它，重启后需实际恢复shell服务。
- 检测可能受系统休眠限制，不承诺永久常驻、30秒硬时限或通知必达。
- 运行时适配不保证所有固件/GMS接口可用；保存的安装/固件/boot变化仍需核验与对应重新同意。

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
日常包构建：设置Android SDK路径及上述原开发keystore后，运行
`powershell -ExecutionPolicy Bypass -File tools/build_daily_apk.ps1`，产物为
`app/build/outputs/apk/release/app-release-daily.apk`。它以本机原开发签名签署非调试Release，
不是公开生产发行签名配置；脚本校验与Debug证书一致，不自动安装。
Firebase自测配置 `app/google-services.json`可选；缺失仍能构建，发送凭据不得打包或提交。
签名、Firebase配置、原始诊断、GMS APK和密钥仅留本地。

[暂停点](docs/PAUSED_CHECKPOINT_20261006.md)为历史记录，不是当前在机状态。
