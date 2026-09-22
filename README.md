# OriginOS FCM Guardian

面向国行 vivo / iQOO OriginOS 7 的非 Root FCM 可靠性、修复、监测与诊断工具。

本仓库已经预置 Codex 项目规则、多智能体配置、项目状态文件和完整开发规范。

## 在 Codex 中开始

1. 解压整个 `OriginOS-FCM-Guardian` 文件夹。
2. 在 Codex 中直接打开**这个根目录**，不要只打开 `docs/` 或未来的 `app/` 子目录。
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

仓库初始化完成，尚未开始 Android 功能实现。

当前阶段：**Phase 0 — OriginOS 7 真机只读探测**。

Phase 0 完成前禁止执行任何系统状态写入。
