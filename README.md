# OriginOS FCM Guardian

面向国行 vivo / iQOO OriginOS 7 的非 Root FCM 可靠性、修复、监测与诊断工具。

本仓库已经预置 Codex 项目规则、多智能体配置、项目状态文件和完整开发规范。

## 在 Codex 中开始

1. 解压整个 `OriginOS-FCM-Guardian` 文件夹。
2. 在 Codex 中直接打开**这个根目录**，不要只打开 `docs/` 或 `app/` 子目录。
3. 如果 Codex 询问是否信任项目，请确认信任，以便加载项目级 `.codex/config.toml`。
4. 在主线程选择本轮希望使用的父模型和 reasoning；仓库不会固定父模型。
5. 发送 `START_HERE.md` 中的启动提示词。

Codex 应自动读取 `AGENTS.md`，随后根据其中规则读取 Development Spec 与项目状态文档。

## 文件职责

- `AGENTS.md`：Codex 长期工作规则、安全边界、动态多 Agent 调度、上下文恢复规范。
- `OriginOS_FCM_Guardian_Development_Spec.md`：产品、架构、Phase、功能与验收规范。
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

Phase0已验收；Phase1按当前设备/手机优先手动降级范围验收。Phase2的目标应用
自启动状态、系统设置返回复检和低频只读巡检已完成功能真机验证，全天耗电仍待验。
Phase3已接入真实Firebase项目，自有Guardian在“智能控制”下的两次锁屏Doze回调及其中
一次新进程处理取得限定条件通过；不代表第三方修复或不间断长连接已获证明。
Phase4-A 通知事实记录/停止/撤权、历史筛选和本地导出已完成当前设备范围验收；独立Toolbox充电锁屏接收通过，完整Phase4与Telegram修复仍未完成。详见[项目状态](docs/PROJECT_STATE.md)、
[测试矩阵](docs/TEST_MATRIX.md)和[Phase3验证计划](docs/PHASE3_FCM_VALIDATION.md)。

手机端优先独立使用，受限功能保留明确手动设置入口。正常UI的一键修复/恢复仍禁用；
受控自有目标的事务/回滚证据不等于实际GMS或FCM改善。自启动状态和通知回调分别
记录，不能把系统读数当作长连接健康或应用已处理消息的证明。

FCM功能默认不自动注册；没有`app/google-services.json`时仍可构建，界面显示未配置。
配置文件不进入版本控制，发送授权绝不打包进APK。测试发送工具默认离线预演，
[使用方法](tools/FCM_PROBE_SENDER.md)说明单次发送、隐私和证据边界。
[HyperOS参考评估](docs/HYPEROS_REFERENCE_REVIEW.md)用于交互与实验设计，
小米私有机制不作为vivo效果证据。

## 构建与检查

需要 JDK 17、Android SDK 37 与 Build Tools 36；在被忽略的 `local.properties` 设置 `sdk.dir`。
使用仓库内校验过分发摘要的 Gradle Wrapper：

```bash
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。
`core/` 为纯 Kotlin 检测与计划模型，`app/` 为 Android UI 与受限 Shizuku 执行层。
构建成功不代表真机验收；当前结果见 [测试矩阵](docs/TEST_MATRIX.md) 和
[Phase 1 验证记录](docs/PHASE1_VALIDATION.md)。

当前本地开发版包含手动深度监控（单应用、30分钟、有限进程/UID事件与停止入口），
以及会话时间/事件筛选和点击时快照导出。主机验证状态见[手动监控边界](docs/PHASE4_DEEP_MONITORING.md)；
尚未安装或真机验收，系统事件不能直接证明由 FCM 拉起。

换电脑继续开发与返回原机的简短提示词、环境版本、1Password端口和私有资料说明：
[跨电脑接续](docs/CROSS_COMPUTER_HANDOFF.md)。当前本地版还包含可搜索的合并保留时间线；
界面/FGS实际行为尚待设备验收。所有阶段的当前状态以 PROJECT_STATE/CODEX_HANDOFF 为准。
