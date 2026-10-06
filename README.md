# OriginOS FCM Guardian

> **项目已暂停（2026-10-06）**。代码与记录保存到 Gitea；仓库不设为只读。
> 当前手机安装 v57，最新 v58 源码尚未完整重打包／部署；完整 FCM 修复目标未完成。
> 暂停点与未完成项见 [暂停存档](docs/PAUSED_CHECKPOINT_20261006.md)，下方旧版本均为历史。

面向国行 vivo / iQOO OriginOS 7 的非 Root FCM 可靠性、修复、监测与诊断工具。

## 当前试用版本

当前收口为 **[应用唤醒恢复试用版 v54](docs/TRIAL_DELIVERY.md)**。明确要求的参考界面已更新，
优先解决真实保护中断；GMS自动修复/重连等仍是能力缺口，没有新线索不反复研究。
不代表完整FCM目标已完成。下面版本经过保留为历史，唯一当前状态在
[PROJECT_STATE](docs/PROJECT_STATE.md)，不要据历史段落重选应用、安装或重复验收。

当前已安装v54：[干净接续合同](docs/GMS_HEARTBEAT_CONTINUITY_V54.md)Astra源码/conditional device GO，
专用reader finally清理已补；build1m19s/716应用+94core=810 PASS/零失败错误跳过、lint0E20W3H，
原签名/产物门及53→54正常覆盖PASS；AndroidTest仅编译未执行。新明确持久同意与epoch/GMS-user0
精确安装签名绑定原子保存，schema1/v53 configured不自动增权，同意不等于运行权。
仅同进程干净host loss/外部Shizuku返回，在原端点ACK/死亡确证、无pending/UNKNOWN/sticky阻断后，
每次重核验宿主/特权/绑定，新代际/new lifetime arm，重新等完整900000ms；全部cold重建仍需确认。
OFF_PENDING不借新绑定绕过。旧schema1/gen2/OFF不增权；新明确UI同意gen3/schema2/durable1，
单次生产reattach→gen4/ARMED/epoch保持/新session/重新等待15min，到期前OFF→gen5无pending/session/durable。
无新HB attempt/result，lastREQUESTED仅旧53；原端点ACK或确认死亡成立、路径未独立区分。
当前54/心跳OFF/目标ON；28ACTIVE/gen94/journal35/invalid0/两精确哈希/noReconcile/单FGS/通知ignore/
boot14保持、XML2删除/回桌面。仅接续路径，不是真实服务死亡/Shizuku重启/cold/真机15min计时验证。
下一仅完整App进程重建的停止持久性/旧owner证明窄合同，尚无source/event批准，不重debug/HB/旧验收。
证据[fixture](docs/fixtures/protection/20261005-heartbeat-continuity/verification.json)独立有限证据复核GO（非Phase终审）。
真实stale重连/重启特权/日常耗电未证，不承诺零操作开机，Phase1–3/CAP仍OPEN、效果估计不升。

### 历史v53基线（下方Next为历史）

v53 / `0.1.0-auto-heartbeat-session`已实现并正常原签名52→53安装：低频心跳独立默认OFF，
明确确认后仅当前服务会话等待完整15分钟，不从旧偏好恢复自动写权。
诊断增量Astra源码/一次新鲜确认GO；最终构建694应用+94core（788）PASS/41s，零失败/错误/跳过，
lint0E20W3H、原签名/产物门及正常诊断53升级PASS。首次03:48:46–48无snapshot/arm/事件，原因UNKNOWN。
04:01:30确认/04:01:51已ARMED，04:10:54后台无结果；04:17:21首读REQUESTED/COMMAND_ACCEPTED。
GMS HB04:16:32.558/ACK04:16:32.793（235ms），自报true/schedulerOFF；仅时间关联，
自然巧合未独立排除，精确arm完成时刻未捕获（源码900000ms下界未改且测试通过）。
04:18:21 OFF/04:18:24停止确认，gen2/configfalse/pendingfalse/remoteSessionfalse，lastREQUESTED保持；
原端点ACK或确认死亡的路径未独立区分，无第二周期，Astra最终bounded-evidence GO。
当前心跳OFF/目标保护ON；原28ACTIVE/gen94/journal35/精确哈希、invalid0/noReconcile、
单FGS/通知ignore/user0/boot14及外置Shizuku保持。下次会话再确认，未确认OFF时手动BUSY。
下一优先服务重建/外部Shizuku返回后的心跳接续合同及独立审查，仅未来有限本机工作：
显式持久授权设计+每次新会话重核验，不以现存配置充当执行权，本轮不实现/再发事件。
现版仍current-session-only；自然stale/断连及日常反馈未验证，不重复健康首发/C1，不承诺零操作重启权限。
Phase1–3及全部CAP仍OPEN；Phase4最小支持已收口、请求的Phase5界面切片已交付，不等于项目完成。
健康HB不提高60–75%工程估计（非实测成功率）。
证据[verification](docs/fixtures/protection/20261005-auto-heartbeat/verification.json)已补齐finalPreservation/cleanup。
见[有限会话合同](docs/AUTOMATIC_GMS_HEARTBEAT_V53.md)。不证明断连恢复、重连或通知收益。

包含v52手动心跳：一次生产请求观察到客户端HB/ACK265ms及alarm重排；
见[手动心跳合同](docs/MANUAL_GMS_HEARTBEAT_V52.md)，不重复该资格。

包含v51 / `0.1.0-gcm-self-report`：GcmService自报连接状态接入现有Shizuku读取，
实际App验证通过；647应用+89 core单测、原签名构建/升级通过，28项授权/日志保持。
见[GMS只读合同](docs/GMS_READ_ONLY_OBSERVATION.md)。自报连接不证明真实活性，自动重连仍待验证。
包含v50 / `0.1.0-reference-ui`：主页卡片、应用滑块/分组、底部详情及系统日夜配色，
644应用单测、真机外观/主题切换核对，28项授权保持；不代表GMS修复/重连新增能力。
见[界面合同](docs/REFERENCE_UI_V50.md)。包含v49：启动PM读取增加整批期限，超时保留
配置、阻止写入并等待重核验；642应用单测通过，28项/总ON保留。见[启动修复](docs/STARTUP_PM_READ_BOUND.md)。
包含v48改进：复检增加5秒等待预算与批量入口，
实测28应用后选择4路并行读取、恢复仍串行；普通切页/后台保持复检结果。
删除整个App的自启动权限检测，仅留设置入口。635项应用单测通过；28项/总ON保留。
详见[修改及实测](docs/BOUNDED_RECIPIENT_CHECKS.md)。不代表全部平台限制或长期效果已解决。
下方v45为前一包记录，当前接续只看PROJECT_STATE/CODEX_HANDOFF。

2026-10-04，v45 / `0.1.0-master-switch` 已实现、主机已验证、有限手机验证通过。用户明确要求首页总开关
关闭只停止运行、保留全部所选应用；应用页单项OFF只移除对应选择，退出界面、权限
不可用和普通失败不清空配置。总开关、逐应用选择与实际就绪分别显示，不能把勾选当作
正在保护。Astra设计GO，单一原子schema3保留USER_PAUSED/SUSPENDED配置，仅ACTIVE且
通过原身份/特权/journal/写票据等门才执行。Astra架构/源码条件GO的两项P2已修复，独立
Sol有限最终冻结源码复核GO、无剩余P1/P2；这不是一次新的Astra最终审查。
最终完整构建1分49秒、610项应用单测/88 suites零失败、错误、跳过，core79/12缓存复用，
lint零错误19警告3提示；原签名/实际版本45/外置唯一后端产物边界通过。AndroidTest仅
编译未执行，不是Android运行时或事故故障端到端验收。最终主包/明细见
[v45验证记录](docs/fixtures/protection/20261004-master-switch/verification.json)。
合同见[主开关与应用配置](docs/MASTER_SWITCH_APP_SELECTION.md)。
ON的历史确认时间取自获准的用户动作，不在排队/预检后重取；配置读取失败保留缓存
选择并显示配置不可用，缓存不作为执行授权。只有成功读取返回null才把该null解释为空配置。
历史v44：正常原签名覆盖后17项授权、预算、journal字节保持；稍后出现DISABLED/
gen61/0项，准确提示匹配Runtime.stop保存成功路径，用户否认手动关闭，触发者UNKNOWN。
v45修复停止运行会清配置的设计缺陷，不是已证明旧事故根因；不从备份恢复旧17项。
45正常原签名覆盖保留原空OFF的schema2、授权/预算/journal29字节，不自动迁移或补回旧项。
显式新选Toolbox后总OFF→ON→OFF，选择/token/policy/同意时间及预算/journal保持；ON时
实际检测与单FGS，OFF后退出重进选择仍勾选，保护FGS为0。最终45为总OFF/USER_PAUSED，
保留1项Toolbox测试选择；旧17项需用户重新选择，不从备份重授。
其他UI由用户报告已接受，不重复；报告格式/JSON MIME/取消已观察，正常JSON与Markdown
保存读回仍延期。历史事故证据见[v44设备记录](docs/fixtures/protection/20261004-v44-device-batch/verification.json)。
本文件不操作ADB/手机。Phase1–3/CAP01–07仍OPEN，60–75%工程估计
（非实测成功率）与日常收益/耗电证据不变，接续只看最新state/handoff/plan/queue。

历史2026-10-04 19:16，v44 / `0.1.0-home-shizuku` 已实现、主机已验证、手机未验证：补齐规范§9.1已要求的
主页特权未就绪直接操作入口。在现有“运行状态”卡片按既有Shizuku状态映射显示动作，
只在点击时复用原授权/打开管理器回调；READY_SHELL无动作，root/错误仍引导管理器，
不标作就绪。原两个按钮与设置入口保留，不新增页面、状态、后端、权限、服务、同意、
特权写入或保护策略。原点击处理仍做实时核验，可能按原行为接续已有有效保护，不等于
新增保护同意。独立Sol有限源码GO，完整构建、原签名/版本44/产物边界通过；590项应用
单测执行通过，core79项复用原缓存，lint零错误。新增4项Compose用例仅编译、未执行，
不作为手机UI/授权链路证据。只按最新queue正常升级最终44主包一次，合并37–43待验项；
主页仅观察自然当前状态，不撤权/启停Shizuku制造场景，不装中间或测试APK。
手机未连接，无ADB或安装，Phase1–3/CAP01–07与60–75%工程估计（非实测成功率）、
日常收益/耗电证据不变。产物/hash见[验证记录](docs/fixtures/protection/20261004-home-shizuku/verification.json)，
接续只看最新state/handoff/plan/queue。

历史2026-10-04 18:59，v43 / `0.1.0-notification-continuity` 已实现、主机已验证、手机未验证：
有限修复运行中后续保护状态通知刷新异常进入通用收集失败、清空整组持久同意的路径。
仅显示构建/notify中的普通Exception被隔离，取消继续抛出、Error继续传播；新鲜精确
授权检查在try外，初次必需前台通知/启动失败与原安全门保持。独立Astra最终源码GO。
修复前条件代码路径已确认，旧现场原因仍未知，也不证明系统隐藏通知会抛异常。
无新日志、页面、状态、权限、服务或重试，既有调度保持。完整构建、590项应用单测、
lint零错误、原签名/版本43/产物边界通过；core79项复用原缓存结果。7项新增helper回归
使用真实内存授权/记录/安全门和手写外层pause模型，不是Android运行时端到端证据；
AndroidTest仅编译、未执行。只按最新queue一次升级最终43主包，合并37–42待验项，
不装中间/测试APK或制造故障、重启、消息、设置变化。
手机未连接，不访问ADB或安装；Phase1–3/CAP01–07仍OPEN，日常收益/耗电与60–75%
工程估计（非实测成功率）不变。产物与明细见[验证记录](docs/fixtures/protection/20261004-notification-continuity/verification.json)，
当前结果与调度以[项目状态](docs/PROJECT_STATE.md)、[唯一执行计划](docs/ACCELERATED_PLAN.md)
和[集中真机清单](docs/DEVICE_ACCEPTANCE_QUEUE.md)为准。

历史2026-10-04 18:37，v42 / `0.1.0-settings-about` 已实现、主机已验证、手机未验证：补齐规范要求但设置页
遗漏的“关于”卡片。在“设置 → 关于”直接显示OriginOS FCM Guardian及构建生成的
版本名称/版号`0.1.0-settings-about (42)`，不增加按钮或详情页。完整构建、583项应用单测、
原签名/产物边界及独立Sol源码审查通过；core79项复用缓存，lint零错误。新增1项合成
导航/版本/不停止保护用例仅编译、未执行，尚未手机验收。
只按最新queue一次升级最终42主包，合并37–41
待验项和关于版本显示观察。手机未连接，核心/CAP仍OPEN，效果/耗电及工程估计未变。
唯一调度与验证层级仍见[当前执行计划](docs/ACCELERATED_PLAN.md)、[项目状态](docs/PROJECT_STATE.md)
和[验证记录](docs/fixtures/protection/20261004-settings-about/verification.json)。

历史2026-10-04 18:19，v41 / `0.1.0-all-apps-recheck` 已实现、主机验证通过、手机未验证：补齐“所有应用”中从未加入关注/保护的
当前可见应用也能点击行展开、只读复检的入口。展开只显示复检和结果，右侧保护开关
独立；展开或复检不会开启保护，原OFF保持OFF。未关注应用只读取普通PackageManager
停止标记，不新增Shizuku/OEM查询或写入；结果仅在本次可见列表暂存，刷新后清除。
旧安装记录、缺失身份或身份变化如实不可用，不按包名替换当前安装。合同见
[应用详情](docs/APP_DETAIL_EXPANSION.md)。有限Astra设计与独立Sol最终源码审查GO，无P1/P2。
最终主机583项应用单测全部通过（新增12项），完整构建/原签名/产物边界通过；core79项
复用缓存，lint零错误。新增4项Compose用例仅编译、未执行；本轮未手机验收。
当时手机未连接；设备queue只排一个v41主包正常升级，合并37–40的既有待验项
及一次从未保护的当前应用只读复检，不安装测试APK。核心/CAP仍OPEN，效果/耗电证据
和工程估计未变。主包/hash及明细见[项目状态](docs/PROJECT_STATE.md)和
[验证记录](docs/fixtures/protection/20261004-all-apps-recheck/verification.json)；唯一调度见[当前执行计划](docs/ACCELERATED_PLAN.md)。

历史v40 / `0.1.0-diagnostic-handoff` 已实现P4-R1有限保护/恢复摘要与P5-R1可读Markdown，
两种格式来自同一脱敏点击快照。现有“导出脱敏报告”按钮可选“JSON（结构化）”、
“Markdown（便于阅读）”或“取消”；不增加采集或修复操作。恢复历史不证明当前安装
状态或通知送达，自动保护标志仅为应用内快照。报告明确升级为`guardian.combined-diagnostic.v2`，旧部分形状保留，
严格v1消费者须适配或拒绝v2。见[报告合同](docs/DIAGNOSTIC_REPORT_HANDOFF.md)。
两份独立源码审查GO；17:46最终主机验证通过：571项应用单测（含31项诊断专项）、debug/
release/test构建、原签名/产物边界通过，core79项复用缓存；AndroidTest仅编译、未执行。
尚未手机验收。当时设备queue合并39/38/37，
新确认连接后只覆盖最新主机验证主包一次。核心/CAP保持OPEN，没有新增推送收益或
耗电证据。唯一调度见[当前执行计划](docs/ACCELERATED_PLAN.md)。

历史v39 / `0.1.0-app-row-details` 已完成主机验证、尚未安装，现已并入v40：取消正常恢复5分钟/每小时3次
配额，保留原约30秒最佳努力检测与全部授权/身份/未决安全门。应用行点击展开/收起，
展开只留复检及结果，右侧开关独立。547项应用单测、构建/原签名/产物边界及独立审查
通过；原设备待验的正常升级/状态保持/最简UI/实际磁贴长按已并入v40集中queue。
见[当前恢复策略](docs/RECOVERY_FREQUENCY_POLICY.md)和[待真机清单](docs/DEVICE_ACCEPTANCE_QUEUE.md)。
候选含Firebase客户端配置，仅私人传输、不公开上传。早前[PRIVATE v37说明包](docs/LIMITED_DELIVERY_V37.md)
保持冻结，属于历史材料，不是当前APK/调度策略。

2026-10-04 13:10：**v36 / 0.1.0-recovery-admission 已安装**，修复执行入口竞争导致误清
自动保护授权的路径，包含v34的失效目标隔离和v35的磁贴接线修复。
524项应用测试、完整构建、独立审查及原签名检查通过；本轮正常升级、原双授权与记录保持、
单保护服务自动接续、实际权限就绪/检测时间更新和通知隐藏核对通过。
[历史v36集中验收](docs/DEVICE_ACCEPTANCE_QUEUE.md)已收口；当前仅按最新queue排一个最终主包，不装中间版本。
新增故障分支仍仅主机验证，本轮正常升级不等于这些分支的Android真机验收。
这不是新增GMS自动修复、FCM重连或推送效果验收；完整核心阶段仍OPEN。

最近真机验收（历史，2026-10-04 10:58）：**v33 / 0.1.0-gms-publication** 在目标手机覆盖安装。
支持用户逐应用授权的标准停止标记恢复、持久授权、共享限频、主页/应用双页和解除限制统计。
v29避免有旧恢复记录时，从重启后首次检查再额外等待整整一小时；原限频和异常时间保护保留。
最终构建通过，app501项/core79项（核心结果复用），独立Astra审查通过；v33修复手动/
后台GMS检查旧结果覆盖新异常的竞争，真实只读检查与正常升级保持已核对，不代表GMS
自动修复、真实重启或推送效果通过。v29重启算法新分支仍只主机验证。
完整证据见[项目状态](docs/PROJECT_STATE.md)和[GMS检查合同](docs/GMS_CONFIG_CHECK.md)。

v30已安装并验收：把每应用今日/本周
确认解除次数直接显示在已保护列表中；无统计项时显示暂不可用，原安装身份隔离保留。
见[列表计数](docs/APP_COUNTS_LIST.md)。v31进一步消除展开详情的重复标题和收起按钮，
保留状态、统计时间与操作；v32把重复检测/共享限频说明集中到应用页顶部，逐应用详情更简洁。
三包均有限验收通过，原保护授权和通知隐藏设置保留；v32的3项新界面用例仅编译，未执行。

GMS修复、FCM重连、重启特权等剩余能力及后续条件集中在
[项目能力缺陷与待办](docs/PROJECT_CAPABILITY_GAPS.md)，可跨电脑接续。

唯一特权后端为**外置Shizuku**，需另行安装、启动并授权。已取消Guardian内置配对、
PrivKit激活和自动开关无线调试路线；重启后Guardian能等待外部服务，不负责恢复Shizuku本身。
见[外置方案](docs/EXTERNAL_SHIZUKU_V18.md)。历史v9启动实验保存在
[启动验证](docs/STARTUP_AUTOMATIC_PROTECTION.md)，不作为当前待办。

| 能力 | 当前边界 |
|---|---|
| 应用接收保护 | 已授权且符合资格时检查并解除标准stopped；不是让目标常驻，不保证通知必达 |
| Google Play服务配置 | 低频只读检查已交付；本机受限配置自动修复未支持 |
| FCM连接 | 有限只读观察及FCM Diagnostics入口；自动重连无已验证可用路径 |
| 后台与重启 | Guardian单一前台服务、持久授权及等待外置Shizuku；检测受休眠和系统策略影响 |
| 效果 | 手动/自动恢复有有限证据，真实日常通知增益与耗电仍待试用 |

完整Phase1–3仍OPEN；[交付差距](docs/CORE_DELIVERY_STATUS.md)区分已交付、平台受限和待验证。
不把停止标记解除、配置正常、socket存在或恢复次数当作消息送达证明。

## 日常试用

以下v45交互已有限手机核对，最终手机45总OFF并保留1项Toolbox测试选择；未恢复旧17项。

1. 保持外置Shizuku运行并授予Guardian权限；未授权时可直接在“首页”的“运行状态”卡片
   使用“Shizuku 授权”，未运行时按“打开 Shizuku”处理；原“设置 → 工作模式”入口保留。
   服务授权不等于应用保护同意。
2. 在“应用”页勾选目标并确认“授权并选择”；项进入“已选择”。在“首页”的“运行状态”
   卡片用“自动保护总开关”控制运行；总OFF保留选择，单项取消只移除该应用。
   目标应用应已开启自启动，无需留在后台。新合同下保留的有效勾选无需重新授权；
   已退休的旧17项不会自动补回。点击应用行可展开复检和结果。
   v41补齐从未选择保护的当前应用入口：只想检查时直接展开并点“复检”，无需先开启保护。
   这只观察当次停止标记，不证明自启动已允许、通知送达或已授权保护。
3. 日常按自己的Wi-Fi/移动数据、亮屏/锁屏习惯使用；无需保持USB，也无需反复强停或发测试消息。
   Guardian的通知可以由系统关闭；通知隐藏不等于停止保护，保留自己的原通知选择即可。

“今日确认解除”“本周确认解除”统计已保存的停止限制解除记录，可能少计，不是通知数量。
v45将首页总开关与应用选择分离：总OFF停止执行但保留逐项勾选，单项OFF只
移除该应用；退出界面或权限/普通失败不取消选择。所选应用不代表当前正在执行恢复，
应同时查看总开关和实际运行/等待/不可用状态。已退休的旧17项不会自动补回。
正常恢复没有固定次数/冷却配额；检测约每30秒一次，休眠可能延后，不承诺标记永久为false。
控制中心“推送保护”磁贴只作辅助恢复，不保证永久常驻。

若再次漏收：在方便的前提下先不要打开目标应用或点其通知，记下应用、发送时间、网络和
是否清过后台，反馈Guardian显示的状态即可。不要提交聊天/银行通知正文、FCM token或密钥。
需要及时使用应用时可照常打开，只需说明现场已改变；不为取证耽误正常使用。

## 在 Codex 中开始

1. 在 Codex 中打开包含 `AGENTS.md`、`gradlew.bat` 和 `.git` 的仓库目录，
   不要只打开 `docs/` 或 `app/` 子目录。本机仓库位于外层工作目录下的 `OriginOS-FCM-Fix`。
2. 先阅读[跨电脑接续](docs/CROSS_COMPUTER_HANDOFF.md)，保留已有改动和原调试签名。
3. 如果 Codex 询问是否信任项目，请确认信任，以便加载项目级 `.codex/config.toml`。
4. 默认推荐 GPT-6.1 Sol High；主线程沿用你本轮手动选择的模型和 reasoning，不强制切换。
5. 发送 `START_HERE.md` 中的启动提示词。

Codex 应自动读取 `AGENTS.md`，随后根据其中规则读取 Development Spec 与项目状态文档。
模型路由见 `AGENTS.md` §5：常规实现6.1 Sol Medium/High，机械任务Luna/Terra，六类高难度/高风险任务由Astra审阅。

## 文件职责

- `AGENTS.md`：Codex 长期工作规则、安全边界、动态多 Agent 调度、上下文恢复规范。
- `OriginOS_FCM_Guardian_Development_Spec.md`：产品、架构、Phase、功能与验收规范。
- `docs/PRODUCT_ROADMAP.md`：当前产品范围、页面职责、稳定性优先的开发路径。
- `docs/ACCELERATED_PLAN.md`：唯一当前执行队列与下一有限工作包。
- `.codex/config.toml`：项目级 Codex 多智能体配置；父模型不固定，子模型动态路由。
- `.codex/agents/`：额外稳定角色定义，目前包含 `utility` 与 `reviewer`。
- `docs/PROJECT_STATE.md`：当前项目状态的紧凑快照。
- `docs/CODEX_HANDOFF.md`：上下文/额度中断后的精确恢复点。
- `docs/DEVICE_FINDINGS.md`：OriginOS 真机已验证事实。
- `docs/DECISIONS.md`：重要工程决策。
- `docs/WORKLOG.md`：简洁的开发流水记录。
- `docs/TEST_MATRIX.md`：功能实际验证矩阵。
- `THIRD_PARTY_NOTICES.md`：直接复用第三方代码时维护许可证与来源。

## 当前状态

主线是目标应用自启动开启、无常驻进程时按需接收并通知。Toolbox仅作对照，不能替代
Telegram等真实应用收益。Phase0及历史功能切片的限定验收不等于当前完整Phase1–3通过；
Phase4保留按需最小诊断，Phase5未自动展开。以[执行计划](docs/ACCELERATED_PLAN.md)、
[项目状态](docs/PROJECT_STATE.md)及[测试矩阵](docs/TEST_MATRIX.md)的最新入口为准。

FCM功能默认不自动注册；没有`app/google-services.json`时仍可构建，界面显示未配置。
配置文件不进入版本控制，发送授权绝不打包进APK。测试发送工具默认离线预演，
[使用方法](tools/FCM_PROBE_SENDER.md)说明单次发送、隐私和证据边界。
[HyperOS参考评估](docs/HYPEROS_REFERENCE_REVIEW.md)用于交互与实验设计，
小米私有机制不作为vivo效果证据。

## 构建与检查

需要 JDK 17、Android SDK 37 与 Build Tools 36；在被忽略的 `local.properties` 设置 `sdk.dir`。
使用仓库内校验过分发摘要的 Gradle Wrapper：

```bash
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-parallel --max-workers=2
```

调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。
`core/` 为纯 Kotlin 检测与计划模型，`app/` 为 Android UI 与受限 Shizuku 执行层。
构建成功不代表真机验收；当前结果见 [测试矩阵](docs/TEST_MATRIX.md) 和
[Phase 1 验证记录](docs/PHASE1_VALIDATION.md)。

覆盖现有安装必须使用原调试签名：通过 `GUARDIAN_DEBUG_KEYSTORE` 指向私人保存的原keystore；
不要为解决签名不匹配卸载应用或清除数据。Firebase配置、发送凭据、原始诊断和签名不公开提交。

当前本地开发版包含手动深度监控（单应用、30分钟、有限进程/UID事件与停止入口），
以及会话时间/事件筛选和点击时快照导出。现已安装并完成代表性真机验证，
范围见[最小诊断出口](docs/PHASE4_EXIT_CHECKLIST.md)；系统事件不能直接证明由 FCM 拉起。

换电脑继续开发与返回原机的简短提示词、环境版本、1Password端口和私有资料说明：
[跨电脑接续](docs/CROSS_COMPUTER_HANDOFF.md)。当前本地版还包含可搜索的合并保留时间线；
已验证能力与残余限制以 PROJECT_STATE/CODEX_HANDOFF 为准，历史记录不能覆盖当前接续点。
