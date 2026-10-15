# OriginOS FCM Guardian

面向国行 vivo / iQOO OriginOS 7 的非 Root 应用唤醒恢复与 FCM 保活工具。

**当前安装：`0.1.0`／内部88，INSTALLED / LIMITED UI PASS。**

v88统一首页、二级与三级界面的紧凑布局：顶部Shizuku卡提供描边“设置”入口，仍进入连接与后台设置，底部重复设置卡移除；
自动保护与FCM卡统一中性色表面/边框，FCM状态条缩小并保留点击刷新。应用详情标题/包名缩小、复检改描边、开关文字适配大字体，确认按钮及导出选项间距统一。
保护后端、检测时序、授权和报告schema沿用v85。1306项单测零失败/错误/跳过，Debug/Release(R8)/AndroidTest APK构建、lint 0E31W及两类产物门通过；AndroidTest仅构建。
22:33:43已原签名正常覆盖，未卸载/清数据；原21项选择、授权、FCM意愿及计数hash逐项不变，86条/高水位95/42日桶，首页今日5/本周20，总开关与FCM保持开启。
实机深色模式截图核对首页、设置上下部、NoTouch帮助、应用页/详情、诊断及导出选择；导出选择已取消，报告序列化/实际导出沿用未变更的v85证据。
未将全部错误/确认状态、浅色实机或长期资源效果算作本轮通过。见[v88说明](docs/UI_COMPACT_LAYOUT_V88.md)及[验证记录](docs/fixtures/protection/20261015-ui-v88/verification.json)。
本包有限UI增量已完成，下一步按自然日常使用观察存活/资源，仅围绕具体新问题定向处理，不重跑历史矩阵。

**v85保护与诊断基线（本次沿用）：**

v85新增独立保护核心的会话/资源记录、有界多日摘要与JSON v13报告，并修复首次迁移在标记写入前失败后无法再尝试的问题。
约30秒检测、四路读取、串行恢复不变；资源采样只借用既有检查机会、至少间隔五分钟，不新增后台定时器。
2016条样本约相当于七天名义容量，不是七天留存期限；未知结束不推断精确被杀时间或原因。
旧App历史标注`GUARDIAN_APP_PROCESS_HISTORY`，新核心历史标注`GUARDIAN_PRIVILEGED_DAEMON_HISTORY`，不能混作同一进程的存活/耗电证据。
1306项单测（1261 app＋45 core）零失败/错误/跳过，Debug/Release(R8)/AndroidTest APK构建、lint 0E31W及两类产物门通过；AndroidTest仅构建。
PowerShell合成回归、Kotlin实际生成JSON v13→分析器和主机源码审查通过；21:15:47原签名正常覆盖成功，安装APK与主机构建hash一致，未清数据。
原21项选择/授权/FCM意愿/计数hash不变，确认86条/高水位95，首页今日5/本周20。
正常JSON v13导出97032字节及离线分析通过；新核心READY、两条资源样本间隔305683ms，实际stopped/FCM检查推进，FCM ACTIVE/recoveryReady=true。
本包增量完成，无新增待连接验收；下一步按日常使用观察存活/资源，不重跑旧矩阵，不宣称长期不杀、耗电改善或所有OTA通过。见[当前状态](docs/PROJECT_STATE.md)。
本包[主机记录](docs/fixtures/protection/20261015-daemon-diagnostics-v85/verification.json)／[设备记录](docs/fixtures/protection/20261015-daemon-diagnostics-v85/device-verification.json)；私有原始报告不进入Git。
2026-10-15本次用户要求备份全部当前源码、文档和进度，并可在另一台电脑继续开发；接续入口为[START_HERE](START_HERE.md)和[换电脑指南](docs/CROSS_COMPUTER_HANDOFF.md)。实际提交/远端同步结果以Git记录为准，本说明不预先宣称推送完成。

**v84已部署基线：磁贴与报告PASS，有限工作包完成。**
1271项测试零失败/错误，Debug/Release(R8)/AndroidTest APK构建、lint 0E27W及两类产物门通过；原签名覆盖、未清数据。
仅展开QS、不启动GuardianActivity时盾牌已点亮，系统显示“推送保护，检测已开启”；Home保护ON、21项与FCM ON保留。
正常JSON v12导出及语义核对通过：远端投影与App进程历史区分，FCM ACTIVE/recoveryReady=true，daemon资源明确`NOT_COLLECTED`。
recoveryReady按内存原子样本与资格证据显示，当前pending/失效不沿用旧true；磁贴只在面板内作最多4次有界只读刷新。
真实重启QS辅助核心接续沿用v83结果，不重复故障；报告确认新boot一次自动FLAG_CLEARED，计数85→86，原21项选择/授权SHA不变。
不宣称无需QS、仅事件恢复、长期不杀/耗电改善或所有OTA通过；daemon低频资源取样仅为后续待办。
[本轮脱敏证据](docs/fixtures/protection/20261015-daemon-owner-foundation/resume-v84.json)；旧已验收项沿用、不重测。

**v82已验收基线（本次沿用）：** 实际保护已迁入单一长期 Shizuku shell 核心，
界面负责设置、状态和报告；普通退出界面不应撤销保护。新版已原签名覆盖，
全程锁屏且Guardian App缺席时，同一核心18,482ms实际解除临时测试应用的停止标记，自动记录完成、计数84→85。
独立恢复及Home OFF→QS仅展开仍OFF→正常ON已限定通过；临时测试选择已移除，原21项精确恢复、计数保留。
无新FCM授权，下一正常检查心跳会话恢复且无pending。API_V23撤销后安全停入、重新授予后QS辅助恢复已有限通过，
原选择/计数和ACTIVE rev6保留；该权限试验不代表所有撤权竞态或Shizuku server重启通过。
修复系统更新后总开关立即关闭的问题；普通应用授权的固件指纹仅保留诊断来源，按当前权限、
安装身份、实际接口和结果核验能力。应用选择与日/周计数继续保留。
FCM 沿用服务缺失 SEND、明确断开 MCS 两条恢复路径；MCS 优先读取当前接收能力，版本号不再参与断开状态判定。
旧 dump 缺导出字段时仅保留已核对的证据兼容，不支持的入口只影响对应操作。
移除没有停止标记或 FCM 修复作用的定期巡检及高级工具嵌套页，保留简洁诊断和有界报告导出。
固定 FCM RPC 的进程级容量，可靠清理绑定，并减少资源记录中的重复分配；不降低检测频率。
1248 项单测零失败、Debug/Release/AndroidTest 构建、lint 0错误27警告与两包产物门通过；AndroidTest仅构建。
升级保留原21项选择、授权及计数；无需卸载、清数据或重新选择。v81首次ARM不确定已由v82精确证明自动结算，未手改记录。
本次自然FCM断连为NOT_SENT后自行恢复，不计新的修复效果；旧FCM限定验收不重复计数。

持续不中断是常驻优化目标，自动重建是兜底。两类 FCM 恢复功能链已有
[限定实机证据](docs/fixtures/protection/20261012-v77-present-disconnected/verification.json)，
不以此宣称全天候不中断或所有通知必达。当前设备结果和剩余项见[项目状态](docs/PROJECT_STATE.md)。
正常覆盖保留数据；无需卸载/清数据。本次按用户要求维护跨设备接续说明，未来提交推送仍需对应授权。

首页总开关保存持续启用设置。系统回收、清后台、重启、OTA 或暂时失去 Shizuku，不应把设置改成关闭；
外置 Shizuku 由用户启动并授权后，Guardian 自动连接并核对原设置，控制中心磁贴提供补充恢复触发。
主动关闭总开关会保持关闭，应用选择仍保留；磁贴监听不会强制重开。独立关闭的应用/FCM 同样受尊重。
明确撤权、系统主动停止、身份或未知操作结果仍会安全阻止执行，不以重新连接清除这些记录。
不负责自动启动外置 Shizuku，不开关无线调试。真实重启后的QS辅助接续仅为本机限定结果，不承诺所有设备免于终止。

版本名与“关于”统一为 `0.1.0`；内部升级编号与诊断记录继续保留，历史构建记录不改写。
首页自动保护卡显示单行 `已开启时长 HH:mm:ss`，按实际远端保护核心会话计时；
页面重开不归零，核心重建才重新计时。首页、应用详情、设置、诊断与确认弹窗已精简，
应用管理统一从底部“应用”进入；统计不再显示时区标识。新版已安装，检测与恢复机制保留。

### v75历史限定恢复验证

2026-10-12 v75 / `0.1.0-fcm-recovery-fix` 原签名正常覆盖。同一APK SHA-256
`e0264be220f6cade472aae2a264c335fa63ff21393db90cb81a7ddfa19126530`；签名证书SHA-256
`d662d39e97573ed1759994033584109eb26d06b57c7cae81a7cf3d4d840dfa9d`。912项单测零失败；
Debug/Release/AndroidTest构建、lint、产物门、PowerShell分析通过。21项选择、master ON及原授权hash保留，
无需重新同意；临时测试入口/helper/私有武装状态已从正常源码移除。

一次受控正常 `SERVICE_ABSENT` 自动恢复观察到完整功能链：故障1791788763509，ABSENT +474ms、consume +534ms，
Guardian实际Shizuku请求被接受 +2854ms，新service/socket连接 +4566ms；之后正常完整连接 +30223ms，
rearm +34040ms。息屏锁定持续到 +54585ms；+61018ms时已解锁，该时点不算锁屏证据。功能链有限通过，
但controller整体结果为 **NOT_PASSED**（过早journal GAP及后续锁屏变化），不宣称120秒整体PASS；历史episode15 GAP未改写。

新增日志修复仅在请求完成后等待完整合格 `CONNECTED + NO_MATCH` 暂态，最多120秒，实际观察到连接才记成功；
UNKNOWN/身份变化/OFF仍为GAP。6项真实顺序回放和2项身份测试通过。2.285MB读取超过旧1MiB限制，
采用8MiB原始流/256KiB主区/8192字节行上限，严格保留完整EOF/error/exit/stderr判定；native-t3/local 2500ms
仍在既有8秒总runner期限内。30秒最佳努力、4路读取、串行恢复和检测频率不变。
正常JSON v12导出106803bytes、来源AVAILABLE；15条留存片段/8条预览/7条遗漏，写失败/丢弃/未决为0，
21项目标、授权与FCM现场状态匹配，旧episode15 GAP/REQUESTED保持不变。AndroidTest仅编译，未运行instrumentation。

这不证明服务仍在但断开场景、第三方通知实际送达或长期发热/耗电/自然存活改善；外置Shizuku重启后仍是外部依赖。
不重复该次故障或健康心跳复验；MCS断开分支随后已由v77完成限定验收。详见[当前状态](docs/PROJECT_STATE.md)、
[能力缺口](docs/PROJECT_CAPABILITY_GAPS.md)及[v75验证记录](docs/fixtures/protection/20261012-v75-recovery-fix/verification.json)。

高级监控、通知事实采集、时间线和Guardian推送自测的前后端/服务/Firebase依赖已从日常APK移除；
原源码和必要集成快照见[归档说明](archive/20261012-advanced-tools/README.md)。v70以来的资源/退出证据仍为历史，
真实耗电、发热和自然退出率收益不能由测试数或APK大小推断。

### v70历史日志准备包

**2026-10-10 v70 / 0.1.0-survival-evidence：减少重复读取，补齐中断分析。**
正常合格目标每轮签名包信息读取4→3，21项84→63；停止标记双重身份核验、四路有界检查、
串行恢复、30秒最佳努力检测及FCM保护不变。新增后台资源侧记录，复用已有循环，每个进程首次
采样后至少间隔五分钟；没有新唤醒定时器或保活角色，不向普通页面增加技术内容。
报告v9增加同一进程内存/线程/CPU、系统压力、读取超时/拒绝、实际FCM读取年龄及退出关联；
新日志失败不影响保护。详见[证据与应对方案](docs/LOW_MEMORY_LIFECYCLE_REVIEW_20261010.md)。
自然LOW_MEMORY频率与耗电是否改善仍需日常对照；不能保证系统公开每次退出的完整原因。

本机分析：`tools/analyze_survival_reports.ps1 -ReportPath <JSON报告路径>`，可输入最多8份v8–v13
报告，每份不超过1MiB；App历史退出、FCM记录及v13核心历史分范围分析，片段汇总只统计有界预览，明确遗漏及证据空档，
不把请求接受或请求后连接当成唯一修复原因或通知送达。报告仍含设备和应用信息，请私下保存，不公开上传。
新版验证与当前进度以[PROJECT_STATE](docs/PROJECT_STATE.md)为准；本轮未提交/推送。

### v69历史有限交付

**2026-10-10 v69 / 0.1.0-stable-continuation已原签名覆盖安装。** 针对v68三小时中断反馈，
修复已复现的FCM初始化丢状态竞态，并回退空闲线程回收/新增资源收缩，当前安装恢复v67的Debug构建模式作对照。
905项单测、完整构建/lint/签名与产物门通过；一次息屏、自身进程冷重建后，无UI/QS辅助便恢复新维护会话和完整FCM观察。
21项选择和原授权保留，频率/并发/恢复资格不变。目标仍是持续实际保护；这不是LOW_MEMORY已解决或永久存活证明。
本轮结束，按自然使用对照退出间隔并每次核对FCM工作记录。见[当前状态](docs/PROJECT_STATE.md)。

### v68历史有限交付（后续留存效果未通过）

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

## 历史本机备份 — 2026-10-10

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
  FCM Diagnostics入口；统一开关控制连接时心跳及合格服务缺失/断开时的单次恢复。
  socket存在或自报已连接不保证实际消息送达。
- 主页/应用、底部应用详情、跟随系统深浅主题、控制中心磁贴、恢复次数和最小脱敏报告。
  新保护生命周期日志见 [v63说明](docs/PROTECTION_LIFECYCLE_V63.md)，最多64条，无聊天/异常正文。

**不再提供**独立GMS健康/配置检查、AppOps/Doze/PEM修复、白名单回滚与自动策略保护。
FCM仍由Google Play服务承载，因此保留FCM所需的GMS身份和连接读取，不是保留上述独立功能。

## 已知边界

- SERVICE_ABSENT与服务仍在但断开的自动恢复链均已在受控场景有限通过；状态未知不盲发，不保证所有故障可恢复。
  该单次功能链不代表所有场景或第三方通知送达通过。
- 现有心跳只在自报已连接且资格有效时发送，不能宣传为完整断连修复。
- 外置Shizuku是唯一特权后端；Guardian不会自行启动它，重启后需实际恢复shell服务。
- 检测可能受系统休眠限制，不承诺永久常驻、30秒硬时限或通知必达。
- 运行时适配按具体能力判断；普通应用 policy4 授权跨 OTA 保留，安装身份变化仍需核验。
  FCM 的 GMS 安装身份变化仍需核验；正常重启在原有效授权和完整资格下自动接续，不以固件版本白名单禁用整个 App。

参考结论与后续范围：[FCM简化路线](docs/FCM_KEEPALIVE_SCOPE_20261006.md)。
执行顺序：[ACCELERATED_PLAN](docs/ACCELERATED_PLAN.md)。

## 使用

安装最终验证包后，先启动外置Shizuku并授权Guardian；应用授权和保护同意是两回事。
在“应用”中选择目标，在主页开启总开关；按统一确认开启“FCM保护”。
手机重启后由用户启动外置Shizuku；Guardian在原配置、授权和当前资格有效时自动接续，控制中心磁贴提供辅助触发。
主动关闭仍保持关闭；明确撤权、身份变化或未决结果按实际证据处理，不用重复确认绕过安全屏障。
自启动权限由用户在系统中手动设置；
Guardian仅提供设置入口，不检测或修改自启动。目标应用无需保留后台。

应用保护检查约30秒一次，系统休眠可能延后；“今日/本周确认解除”是停止限制解除记录，
不是通知数量。Guardian的通知显示由系统设置控制，隐藏通知不应清空应用选择。
控制中心磁贴只作辅助启动/恢复，不能独立获得Shizuku权限或保证永久保活。
之前卸载已经移除了旧配置，不从历史记录恢复旧26项选择或保护授权。

日常按自己的Wi-Fi/移动数据与亮屏/锁屏习惯使用，不必持续插USB或反复发送健康测试。
原App退出历史与新核心会话/资源记录分别保存；v85新核心采样和正常导出已有限核对，未知结束不等于已确认被杀。
明显第三方漏收可补充反馈，
因为Guardian不能仅凭连接记录判断第三方实际送达。不提交通知正文、token或密钥。

## 本机开发

阅读 [START_HERE](START_HERE.md)、`AGENTS.md` 和当前状态。换电脑环境、Git同步及原签名私人同步见[接续指南](docs/CROSS_COMPUTER_HANDOFF.md)，当中的2026-10-15当前说明取代旧备份记录中的“不维护handoff”。
手机默认未连接，只有用户本轮确认后才按
[DEVICE_ACCEPTANCE_QUEUE](docs/DEVICE_ACCEPTANCE_QUEUE.md)集中验证受影响差异。
历史源码和结论仍保留在Git、功能文档与fixtures，不按历史Next重复验收。

需要JDK17、Android SDK `platforms;android-37.0`、`build-tools;36.0.0`、platform-tools和command-line tools；设置本机`JAVA_HOME`、`ANDROID_HOME`或被忽略的`local.properties`。仓库wrapper固定Gradle9.3.1，AGP9.1.1、Kotlin2.4.10；依赖下载需Google Maven、Maven Central及Gradle站点。PowerShell脚本用于产物门及报告分析，Python3.9+标准库用于`tools/tests`中的离线脚本测试。

```powershell
.\gradlew.bat :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-parallel --max-workers=2
```

调试包：`app/build/outputs/apk/debug/app-debug.apk`。覆盖安装使用原调试签名，
`GUARDIAN_DEBUG_KEYSTORE`指向私人保存的keystore，不为签名问题卸载/清数据。
当前手机已原签名覆盖v88，新旧限定证据以[当前状态](docs/PROJECT_STATE.md)为准。
备用非调试包构建：设置Android SDK路径及上述原开发keystore后，运行
`powershell -ExecutionPolicy Bypass -File tools/build_daily_apk.ps1`，产物为
`app/build/outputs/apk/release/app-release-daily.apk`。它以本机原开发签名签署非调试Release，
不是公开生产发行签名配置；脚本校验与Debug证书一致，不自动安装。
Firebase自测已移除，当前构建不读取或打包 `app/google-services.json`；旧私有文件保留，不进入归档。
Git不包含构建缓存、APK、签名、Firebase历史配置、原始诊断、GMS APK或SSH密钥。原开发签名另行私人同步才能覆盖已安装版本；原始报告按需私人同步用于历史对照，手机内授权和计数不由Git恢复。没有私人文件仍可开发/构建；缺原签名时不得卸载或清数据绕过覆盖限制。

[暂停点](docs/PAUSED_CHECKPOINT_20261006.md)为历史记录，不是当前在机状态。
